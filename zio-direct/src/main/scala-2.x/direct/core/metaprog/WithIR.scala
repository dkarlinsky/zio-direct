package zio.direct.core.metaprog

//import zio.direct.core.util.Format
//import zio.direct.core.util.Unsupported
import scala.reflect.macros.whitebox.Context
import zio.ZIO

trait MacroBase {
  val c: Context
  type Uni = c.universe.type
  // NOTE: u needs to be lazy otherwise sets value from c before c can be initialized by higher level classes
  lazy val u: Uni = c.universe

  import c.universe._

  def isZIO(tpe: Type) =
    tpe <:< typeOf[ZIO[Any, Any, Any]] && !(tpe =:= typeOf[Nothing])

  object report {
    def errorAndAbort(msg: String) = c.abort(c.enclosingPosition, msg)
    def errorAndAbort(msg: String, tree: Tree) = c.abort(tree.pos, msg)

    def warning(msg: String) = c.warning(c.enclosingPosition, msg)
    def warning(msg: String, tree: Tree) = c.warning(tree.pos, msg)

    def info(msg: String) = c.info(c.enclosingPosition, msg, true)
    def info(msg: String, tree: Tree) = c.info(tree.pos, msg, true)
  }
}

trait WithIR extends MacroBase {
  import c.universe._

  protected sealed trait IR
  // can't be sealed or type-checking on IR.Monadic causes this: https://github.com/scala/bug/issues/4440
  object IR {
    sealed trait Monadic extends IR
    sealed trait Leaf extends IR {
      def code: c.universe.Tree
    }

    case class Fail(error: IR) extends Monadic

    case class While(cond: IR, body: IR) extends Monadic

    case class ValDef(originalStmt: c.universe.Block, symbol: Symbol, assignment: IR, bodyUsingVal: IR) extends Monadic

    case class Unsafe(body: IR) extends Monadic

    case class Try(tryBlock: IR, cases: List[IR.Match.CaseDef], resultType: c.universe.Type, finallyBlock: Option[IR]) extends Monadic

    case class Foreach(list: IR, listType: c.universe.Type, elementSymbol: Symbol, body: IR) extends Monadic

    case class FlatMap(monad: Monadic, valSymbol: Option[Symbol], body: IR.Monadic) extends Monadic
    object FlatMap {
      def apply(monad: IR.Monadic, valSymbol: Symbol, body: IR.Monadic) =
        new FlatMap(monad, Some(valSymbol), body)
    }
    case class Map(monad: Monadic, valSymbol: Option[Symbol], body: IR.Pure) extends Monadic
    object Map {
      def apply(monad: Monadic, valSymbol: Symbol, body: IR.Pure) =
        new Map(monad, Some(valSymbol), body)
    }

    class Monad private (val code: c.universe.Tree, val source: Monad.Source) extends Monadic with Leaf {
      private val id = Monad.Id(code)
      override def equals(other: Any): Boolean =
        other match {
          case v: Monad => id == v.id
          case _        => false
        }
    }
    object Monad {
      def apply(code: c.universe.Tree, source: Monad.Source = Monad.Source.Pipeline) =
        new Monad(code, source)

      def unapply(value: Monad) =
        Some(value.code)

      sealed trait Source
      case object Source {
        case object Pipeline extends Source
        case object PrevDefer extends Source
        case object IgnoreCall extends Source
      }

      private case class Id(code: c.universe.Tree)
    }

    case class Block(head: c.universe.Tree, tail: Monadic) extends Monadic
    case class Match(scrutinee: IR, caseDefs: List[IR.Match.CaseDef]) extends Monadic
    object Match {
      case class CaseDef(pattern: Tree, guard: Option[c.universe.Tree], rhs: Monadic)
    }
    case class If(cond: IR, ifTrue: IR, ifFalse: IR) extends Monadic
    case class Pure(code: c.universe.Tree) extends IR with Leaf
    case class And(left: IR, right: IR) extends Monadic
    case class Or(left: IR, right: IR) extends Monadic

    case class Parallel(originalExpr: c.universe.Tree, monads: List[(IR.Monadic, Symbol)], body: IR.Leaf) extends Monadic
  }

  object WrapUnsafes extends StatelessTransformer {
    override def apply(ir: IR.Monadic): IR.Monadic =
      ir match {
        case IR.Unsafe(body) =>
          // we can actually remove the IR.Unsafe at that point but it is still useful
          // as a marker of where we did those operations.
          IR.Unsafe(MakePuresIntoAttemps(body))
        case _ =>
          super.apply(ir)
      }

    def makePuresIntoAttemps(i: IR) =
      MakePuresIntoAttemps(i)

    private object MakePuresIntoAttemps extends StatelessTransformer {
      private def monadify(pure: IR.Pure) =
        IR.Monad(q"dev.ZIO.attempt(${pure.code})")

      // Monadify all top-level pure calls
      override def apply(ir: IR): IR =
        ir match {
          case v: IR.Pure => monadify(v)
          case _          => super.apply(ir) // // //
        }

      // Monadify pure calls inside IR.Leaf instances (inside IR.Parallel)
      override def apply(ir: IR.Leaf): IR.Leaf =
        ir match {
          case v: IR.Pure  => monadify(v)
          case v: IR.Monad => v
        }

      override def apply(ir: IR.Monadic): IR.Monadic =
        ir match {
          case IR.Map(monad, valSymbol, pure)          => IR.FlatMap(apply(monad), valSymbol, monadify(pure))
          case v @ IR.Parallel(origExpr, monads, body) =>
            // Unsupported.Error.withTree(origExpr, Messages.UnsafeNotAllowedParallel, InfoBehavior.Info)
            // TODO Add back, need formatter for Unsupported
            ???
          case b @ IR.Block(head, tail) =>
            // basically the only thing that can be inside of a block head-statement is a ValDef
            // or a Term of pure-code. Since val-defs are handled separately as an IR.ValDef basically
            // there should be nothing on than a pure-term in this slot
            val wrappedHead =
              if (head.isTerm)
                monadify(IR.Pure(head))
              else
                monadify(IR.Pure(c.universe.Block(List(head), q"()")))

            IR.FlatMap(wrappedHead, None, tail)
          case _ => super.apply(ir)
        }
    }
  }

  trait StatelessTransformer {
    def apply(ir: IR): IR =
      ir match {
        case v: IR.Pure    => apply(v)
        case v: IR.Monadic => apply(v)
      }

    def apply(ir: IR.Pure): IR.Pure = ir
    def apply(ir: IR.Monad): IR.Monad = ir
    // Specifically used in this like IR.Parallel that can have either a Pure or Monad element
    // but either way it has to be a leaf node (i.e. can't have structures inside)
    def apply(ir: IR.Leaf): IR.Leaf = ir

    def apply(ir: IR.Monadic): IR.Monadic =
      ir match {
        case IR.While(cond, body) =>
          IR.While(apply(cond), apply(body))
        case IR.Try(tryBlock, cases, resultType, finallyBlock) =>
          val newCases = cases.map(apply(_))
          val newFinallyBlock = finallyBlock.map(apply(_))
          IR.Try(apply(tryBlock), newCases, resultType, newFinallyBlock)
        case IR.ValDef(orig, symbol, assignment, bodyUsingVal) =>
          IR.ValDef(orig, symbol, apply(assignment), apply(bodyUsingVal))
        case IR.FlatMap(monad, valSymbol, body) =>
          IR.FlatMap(apply(monad), valSymbol, apply(body))
        case IR.Foreach(list, listType, symbolType, body) =>
          IR.Foreach(apply(list), listType, symbolType, apply(body))
        case IR.Map(monad, valSymbol, body) =>
          IR.Map(apply(monad), valSymbol, apply(body))
        case IR.Fail(error) => IR.Fail(apply(error))
        case v: IR.Monad    => apply(v)
        case IR.Block(head, tail) =>
          IR.Block(head, apply(tail))
        case IR.Match(scrutinee, caseDefs) =>
          val newCaseDefs = caseDefs.map(apply(_))
          IR.Match(scrutinee, newCaseDefs)
        case IR.If(cond, ifTrue, ifFalse) => IR.If(cond, apply(ifTrue), apply(ifFalse))
        case IR.And(left, right)          => IR.And(apply(left), apply(right))
        case IR.Or(left, right)           => IR.Or(apply(left), apply(right))
        case IR.Parallel(orig, monads, body) =>
          val newMonads = monads.map { case (monad, sym) => (apply(monad), sym) }
          val newBody = apply(body)
          IR.Parallel(orig, newMonads, newBody)
        case IR.Unsafe(body) =>
          IR.Unsafe(body)
      }

    def apply(caseDef: IR.Match.CaseDef): IR.Match.CaseDef = {
      val newRhs = apply(caseDef.rhs)
      caseDef.copy(rhs = newRhs)
    }
  }
}