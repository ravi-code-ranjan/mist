package io.hydrosphere.mist.apiv2

import io.hydrosphere.mist.api.v2.JobResult
import org.apache.spark.SparkContext
import shapeless.ops.adjoin.Adjoin
import shapeless.ops.function.{FnToProduct, FnFromProduct}
import shapeless.ops.hlist.Tupler
import shapeless.{DepFn1, HList, ::, HNil}

trait ArgExtraction[+A]
case class Extracted[+A](value: A) extends ArgExtraction[A]
case class Missing[+A](description: String) extends ArgExtraction[A]

trait ArgDef[A] { self =>

  def extract(ctx: JobContext): ArgExtraction[A]

  def combine[B](other: ArgDef[B])(implicit adj: Adjoin[A :: B :: HNil]): ArgDef[adj.Out] = {
    cmb0(other, adj)
  }

  def &[B](other: ArgDef[B])(implicit adj: Adjoin[A :: B :: HNil]): ArgDef[adj.Out] = {
    cmb0(other, adj)
  }

  private def cmb0[B](b: ArgDef[B], adj: Adjoin[A :: B :: HNil]): ArgDef[adj.Out] = {
    new ArgDef[adj.Out] {
      override def extract(ctx: JobContext): ArgExtraction[adj.Out] = {
        (b.extract(ctx), self.extract(ctx)) match {
          case (Extracted(be), Extracted(ae)) =>
            val out = adj(ae :: be :: HNil)
            Extracted(out)
          case (Missing(errB), Missing(errA)) => Missing(errA + ", " + errB)
          case (x @ Missing(err1), _ ) => x.asInstanceOf[Missing[adj.Out]]
          case (_, x @ Missing(err2)) => x.asInstanceOf[Missing[adj.Out]]
        }
      }
    }
  }

  def map[B](f: A => B): ArgDef[B] = {
    new ArgDef[B] {
      override def extract(ctx: JobContext): ArgExtraction[B] = {
        self.extract(ctx) match {
          case Extracted(a) => Extracted(f(a))
          case m @ Missing(err) => m.asInstanceOf[Missing[B]]
        }
      }
    }
  }

  def validated(f: A => Boolean, description: Option[String] = None): ArgDef[A] = {
    new ArgDef[A] {
      override def extract(ctx: JobContext): ArgExtraction[A] = {
        self.extract(ctx) match {
          case Extracted(a) if f(a) => Extracted(a)
          case Extracted(a) =>
            val message = s"Arg $self was rejected by validation rule" + description.map(s=>":"+ s).getOrElse("")
            Missing(message)
          case m @ Missing(err) => m
        }
      }
    }
  }

  def apply[F, R](f: F)(implicit fntp: FnToProduct.Aux[F, A => R]): JobDef[R] = {
    new JobDef[R] {
      override def invoke(ctx: JobContext): JobResult[R] = {
        extract(ctx) match {
          case Extracted(args) =>
            try {
              val result = fntp(f)(args)
              JobResult.success(result)
            } catch {
              case e: Throwable => JobResult.failure(e)
            }
          case Missing(msg) =>
            val e = new IllegalArgumentException(s"Arguments does not conform to job [$msg]")
            JobResult.failure(e)
        }
      }
    }
  }

}

object ArgDef {

  def const[A](value: A): ArgDef[A:: HNil] = new ArgDef[A :: HNil] {
    override def extract(ctx: JobContext): ArgExtraction[A :: HNil] = Extracted(value :: HNil)
  }

  def missing[A](message: String): ArgDef[A :: HNil] = new ArgDef[A :: HNil] {
    override def extract(ctx: JobContext): ArgExtraction[A :: HNil] = Missing(message)
  }

}

class NamedArgDef[A](name: String)(implicit fromAny: FromAny[A]) extends ArgDef[A] {
  override def extract(ctx: JobContext): ArgExtraction[A] = {
    val r = ctx.params.get(name).flatMap(a => fromAny(a))
    r match {
      case Some(a) => Extracted(a)
      case None => Missing(s"Argument $name is missing or has incorrect type")
    }
  }
}

class OptionalNamedArgDef[A](name: String)(implicit fromAny: FromAny[A]) extends ArgDef[Option[A]] {
  override def extract(ctx: JobContext): ArgExtraction[Option[A]] = {
    ctx.params.get(name) match {
      case Some(any) =>
        fromAny(any) match {
          case Some(a) => Extracted(Some(a))
          case None => Missing(s"Argument $name has incorrect type")
        }
      case None => Extracted(None)
    }
  }
}

class SparkContextArgDef extends ArgDef[SparkContext] {
  override def extract(ctx: JobContext): ArgExtraction[SparkContext] =
    Extracted(ctx.setupConfiguration.context)
}
