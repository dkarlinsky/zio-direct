package zio.direct

import zio.Task
import scala.quoted._
import zio.direct.core.Transformer
import zio.ZIO
import zio.direct.core.metaprog.Instructions
import zio.direct.core.metaprog.InfoBehavior
import zio.direct.core.metaprog.Collect
import zio.direct.core.metaprog.Unliftables
import zio.direct.core.metaprog.Verify
import zio.direct.core.NotDeferredException
import zio.direct.core.util.TraceType
import zio.direct.core.metaprog.TypeUnion
import zio.direct.core.metaprog.RefineInstructions

def unsafe[T](value: T): T = NotDeferredException.fromNamed("unsafe")

trait deferCall[F[_, _, _], F_out] {
  transparent inline def impl[T](inline value: T, inline info: InfoBehavior, inline use: Use) = ${ zio.direct.Dsl.impl[T, F, F_out]('value, 'info, 'use) }

  // @scala.reflect.macros.internal.macroImpl("nothing")
  transparent inline def apply[T](inline value: T): F_out = impl(value, InfoBehavior.Silent, Use)
  // @scala.reflect.macros.internal.macroImpl("nothing")
  transparent inline def info[T](inline value: T): F_out = impl(value, InfoBehavior.Info, Use)
  // @scala.reflect.macros.internal.macroImpl("nothing")
  transparent inline def verbose[T](inline value: T): F_out = impl(value, InfoBehavior.Verbose, Use)
  // @scala.reflect.macros.internal.macroImpl("nothing")
  transparent inline def verboseTree[T](inline value: T): F_out = impl(value, InfoBehavior.VerboseTree, Use)

  // @scala.reflect.macros.internal.macroImpl("nothing")
  transparent inline def apply[T](inline params: Use)(inline value: T): F_out = impl(value, InfoBehavior.Silent, params)
  // @scala.reflect.macros.internal.macroImpl("nothing")
  transparent inline def info[T](inline params: Use)(inline value: T): F_out = impl(value, InfoBehavior.Info, params)
  // @scala.reflect.macros.internal.macroImpl("nothing")
  transparent inline def verbose[T](inline params: Use)(inline value: T): F_out = impl(value, InfoBehavior.Verbose, params)
  // @scala.reflect.macros.internal.macroImpl("nothing")
  transparent inline def verboseTree[T](inline params: Use)(inline value: T): F_out = impl(value, InfoBehavior.VerboseTree, params)
}

object defer extends deferCall[ZIO, ZIO[?, ?, ?]]

extension [R, E, A](value: ZIO[R, E, A]) {
  def run: A = NotDeferredException.fromNamed("run")
}

object Dsl {
  import InfoBehavior._

  // def implZIO[T: Type](value: Expr[T], infoExpr: Expr[InfoBehavior], useTree: Expr[Use])(using q: Quotes) =
  //   impl[T, ZIO, ZIO[?, ?, ?]](value, infoExpr, useTree)

  def impl[T: Type, F[_, _, _]: Type, F_out: Type](value: Expr[T], infoExpr: Expr[InfoBehavior], useTree: Expr[Use])(using q: Quotes): Expr[F_out] =
    import quotes.reflect._
    val infoBehavior = Unliftables.unliftInfoBehavior(infoExpr.asTerm.underlyingArgument.asExprOf[InfoBehavior])
    val instructionsRaw = Instructions.default.copy(info = infoBehavior)
    val instructions = RefineInstructions.fromUseTree(useTree, instructionsRaw)
    (new Transformer[F, F_out](q)).apply(value, instructions)
    // (new Transformer[ZIO, ZIO[?, ?, ?]](q)).apply(value, instructions)

}

object Internal {
  // An indicator to mark which parts of code have already been transformed by the defer macro
  // this is so that in cases where you have `defer { defer(code) }` the inner defer will yield
  // something like `defer { deferred(code) }` and the outer one knows to wrap the entire
  // body of the inner code into an IR.Monad block
  // (Note that in cases where the `defer(code)` is in some previous operation in the program
  // e.g. `val x = defer {code}; defer {x}` the `defer(code)` information will be lost again but we
  // don't care about it because all the macro-engine sees at that point is an identifier i.e.
  // `defer { Ident(x) }` (unless it's a Scala 3 inline-def that propagates the tree).
  //  This is an important note to understand about metaprogramming in general,
  // you have to not be able to care about the structure of things transformed in other macros
  // in other parts of the application.)
  // def deferred[F[_, _, _], A, B, C](effect: F[A, B, C]) = effect
  // def deferred[A, B, C](effect: ZIO[A, B, C]) = effect
  def deferred[T](effect: T): T = effect

  def ignore[T](code: T): T =
    throw new NotDeferredException(s"The construct `ignore` be used inside of a `defer { ... }` block and should only be used for testing purposes!")

  // Artificial markers use to deconstruct types in the Transformer. Not necessary otherwise.
  object Marker {
    type A
    type B
    type C
  }
}
