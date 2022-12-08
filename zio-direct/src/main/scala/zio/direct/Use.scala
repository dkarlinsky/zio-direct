package zio.direct

import zio.direct.core.NotDeferredException

trait Use {
  def withLenientCheck: Use = NotDeferredException.fromNamed("Use.withLenientCheck")
  def withParallelEval: Use = NotDeferredException.fromNamed("Use.withParallelEval")
  def withAbstractError: Use = NotDeferredException.fromNamed("Use.withAbstractError")
}
object Use extends Use
