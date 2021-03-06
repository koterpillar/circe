package io.circe.rs

import cats.{ Applicative, Eval, Traverse }
import cats.instances.tuple._
import cats.instances.vector._
import cats.kernel.Eq
import cats.kernel.instances.string._
import io.circe.{ Json, JsonNumber, JsonObject }

/**
 * A pattern-functor reflecting the JSON datatype structure in a
 * non-recursive way.
 */
sealed trait JsonF[+A]

object JsonF {
  // format: off
  final case object JNullF                                  extends JsonF[Nothing]
  final case class JBooleanF(b: Boolean)                    extends JsonF[Nothing]
  final case class JNumberF(n: JsonNumber)                  extends JsonF[Nothing]
  final case class JStringF(s: String)                      extends JsonF[Nothing]
  final case class JArrayF[A](value: Vector[A])             extends JsonF[A]
  final case class JObjectF[A](fields: Vector[(String, A)]) extends JsonF[A]
  // format: on

  /**
   * An co-algebraic function that unfolds one layer of json into
   * the pattern functor. Can be used for anamorphisms.
   */
  def unfoldJson(json: Json): JsonF[Json] = json.foldWith(unfolder)

  /**
   * An algebraic function that collapses one layer of pattern-functor
   * into Json. Can be used for catamorphisms.
   */
  def foldJson(jsonF: JsonF[Json]): Json = jsonF match {
    case JNullF           => Json.Null
    case JBooleanF(bool)  => Json.fromBoolean(bool)
    case JStringF(string) => Json.fromString(string)
    case JNumberF(value)  => Json.fromJsonNumber(value)
    case JArrayF(vec)     => Json.fromValues(vec)
    case JObjectF(fields) => Json.obj(fields: _*)
  }

  private[this] type Field[A] = (String, A)
  private[this] type Fields[A] = Vector[(String, A)]
  private[this] val fieldInstance: Traverse[Fields] = catsStdInstancesForVector.compose[Field]

  implicit val jsonFTraverseInstance: Traverse[JsonF] = new Traverse[JsonF] {
    override def traverse[G[_], A, B](fa: JsonF[A])(f: A => G[B])(implicit G: Applicative[G]): G[JsonF[B]] = fa match {
      case JNullF           => G.pure(JNullF)
      case x @ JBooleanF(_) => G.pure(x)
      case x @ JStringF(_)  => G.pure(x)
      case x @ JNumberF(_)  => G.pure(x)
      case JArrayF(vecA)    => G.map(catsStdInstancesForVector.traverse(vecA)(f))(vecB => JArrayF(vecB))
      case JObjectF(fieldsA) =>
        G.map(fieldInstance.traverse(fieldsA)(f))(fieldsB => JObjectF(fieldsB))
    }

    override def foldLeft[A, B](fa: JsonF[A], b: B)(f: (B, A) => B): B =
      fa match {
        case JNullF        => b
        case JBooleanF(_)  => b
        case JStringF(_)   => b
        case JNumberF(_)   => b
        case JArrayF(vecA) => vecA.foldLeft(b)(f)
        case JObjectF(fieldsA) =>
          fieldInstance.foldLeft(fieldsA, b)(f)
      }

    override def foldRight[A, B](fa: JsonF[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      fa match {
        case JNullF        => lb
        case JBooleanF(_)  => lb
        case JStringF(_)   => lb
        case JNumberF(_)   => lb
        case JArrayF(vecA) => catsStdInstancesForVector.foldRight(vecA, lb)(f)
        case JObjectF(fieldsA) =>
          fieldInstance.foldRight(fieldsA, lb)(f)
      }
  }

  implicit def jsonFEqInstance[A: Eq]: Eq[JsonF[A]] = Eq.instance {
    case (JNullF, JNullF)                       => true
    case (JBooleanF(b1), JBooleanF(b2))         => b1 == b2
    case (JStringF(s1), JStringF(s2))           => s1 == s2
    case (JNumberF(jn1), JNumberF(jn2))         => jn1 == jn2
    case (JArrayF(values1), JArrayF(values2))   => Eq[Vector[A]].eqv(values1, values2)
    case (JObjectF(values1), JObjectF(values2)) => Eq[Vector[(String, A)]].eqv(values1, values2)
    case _                                      => false
  }

  private[this] val unfolder: Json.Folder[JsonF[Json]] =
    new Json.Folder[JsonF[Json]] {
      def onNull = JNullF
      def onBoolean(value: Boolean) = JBooleanF(value)
      def onNumber(value: JsonNumber) = JNumberF(value)
      def onString(value: String) = JStringF(value)
      def onArray(value: Vector[Json]) = JArrayF(value)
      def onObject(value: JsonObject) =
        JObjectF(value.toVector)
    }
}
