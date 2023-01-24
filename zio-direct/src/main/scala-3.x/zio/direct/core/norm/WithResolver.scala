package zio.direct.core.norm

import zio.direct.core.metaprog.WithIR
import scala.quoted._
import zio.direct.core.metaprog.Embedder._
import zio.ZIO
import zio.direct.core.metaprog.WithPrintIR
import zio.Chunk
import zio.direct.core.util.Format
import zio.direct.core.util.WithInterpolator
import zio.Exit.Success
import zio.Exit.Failure
import zio.direct.core.metaprog.Instructions
import zio.direct.core.metaprog.Collect
import zio.direct.core.metaprog.WithZioType
import zio.direct.core.util.ZioUtil
import zio.direct.core.util.Unsupported
import org.scalafmt.util.LogLevel.info
import zio.direct.core.metaprog.Collect.Sequence
import zio.direct.core.metaprog.Collect.Parallel
import java.lang.reflect.WildcardType

trait WithResolver {
  self: WithIR with WithZioType =>

  implicit val macroQuotes: Quotes
  import macroQuotes.reflect._

  case class ParallelBlockExtract(monadExpr: ZioValue, monadSymbol: Symbol, tpe: TypeRepr)

  private object CommonTypes {
    val anyToNothing = TypeBounds(TypeRepr.of[Nothing], TypeRepr.of[Any])
    val inf = Inferred(anyToNothing)
  }

  class Resolver(zpe: ZioType) {
    def applyFlatMap(monad: ZioValue, applyLambda: ZioValue): ZioValue =
      monad.zpe.valueType match
        case '[t] =>
          '{
            ${ monad.term.asExpr }.asInstanceOf[ZIO[?, ?, t]].flatMap(
              ${ applyLambda.term.asExprOf[t => ZIO[?, ?, ?]] }
            )
          }.toZioValue(zpe)

    def applyFlatMapWithBody(monad: ZioValue, valSymbol: Option[Symbol], body: ZioValue): ZioValue = {
      val applyLambdaTerm =
        '{
          // make the lambda accept anything because the symbol-type computations for what `t` is are not always correct for what `t` is are not always
          // maybe something like this is needed for the flatMap case too?
          ${ makeLambda(TypeRepr.of[ZIO[?, ?, ?]])(body.term, valSymbol).asExpr }.asInstanceOf[Any => ZIO[?, ?, ?]]
        }.asTerm

      applyFlatMap(monad, ZioValue(applyLambdaTerm, body.zpe))
    }

    def applyMap(monad: ZioValue, applyLambdaTerm: Term): ZioValue =
      monad.zpe.valueType match
        case '[t] =>
          '{
            ${ monad.term.asExpr }.asInstanceOf[ZIO[?, ?, t]].map(
              ${ applyLambdaTerm.asExprOf[t => ?] }
            )
          }.toZioValue(zpe)

    def applyMapWithBody(monad: ZioValue, valSymbol: Option[Symbol], bodyTerm: Term): ZioValue = {
      val applyLambdaTerm =
        '{
          // make the lambda accept anything because the symbol-type computations for what `t` is are not always correct for what `t` is are not always
          // maybe something like this is needed for the flatMap case too?
          ${ makeLambda(TypeRepr.of[Any])(bodyTerm, valSymbol).asExpr }.asInstanceOf[Any => ?]
        }.asTerm

      applyMap(monad, applyLambdaTerm)
    }

    def applyCatchSome(tryClause: ZioValue, body: ZioValue): ZioValue =
      (tryClause.zpe.asTypeTuple, body.zpe.asTypeTuple) match
        case (('[rr], '[er], '[ar]), ('[r], '[e], '[b])) =>
          '{
            ${ tryClause.term.asExpr }.asInstanceOf[ZIO[rr, er, ar]]
              .catchSome { ${ body.term.asExpr }.asInstanceOf[PartialFunction[er, ZIO[r, e, b]]] }
          }.toZioValue(zpe)
        case ((_, _, _), (_, _, _)) =>
          report.errorAndAbort("Invalid match case, this shuold not be possible")

    def applyEnsuring(monad: ZioValue, finalizer: ZioValue): ZioValue =
      monad.zpe.asTypeTuple match
        case ('[r], '[e], '[a]) =>
          // when generalizing to non-zio check there result-type and change ZIO[?, ?, ?] representation to the appropriate one for the given type
          '{ ${ monad.term.asExpr }.asInstanceOf[ZIO[r, e, a]].ensuring(ZioUtil.wrapWithThrowable(${ finalizer.term.asExprOf[ZIO[?, ?, ?]] }).orDie).asInstanceOf[ZIO[r, e, a]] }.toZioValue(zpe)

    def applyFlatten(block: ZioValue): ZioValue =
      // when generalizing to non-zio check there result-type and change ZIO[?, ?, ?] representation to the appropriate one for the given type
      '{ ZIO.succeed(${ block.term.asExprOf[ZIO[?, ?, ?]] }).flatten }.toZioValue(zpe)

    def applyExtractedUnlifts(aliasedTree: IRT.Leaf, unlifts: List[ParallelBlockExtract], collectStrategy: Collect) = {
      val unliftTriples = unlifts.map(Tuple.fromProductTyped(_))
      val (terms, names, types) = unliftTriples.unzip3
      val termsExpr = Expr.ofList(terms.map(_.term.asExprOf[ZIO[?, ?, ?]]))
      val collect =
        collectStrategy match
          case Collect.Sequence =>
            '{ ZIO.collectAll(Chunk.from($termsExpr)) }
          case Collect.Parallel =>
            '{ ZIO.collectAllPar(Chunk.from($termsExpr)) }

      def makeVariables(iterator: Expr[Iterator[?]]) =
        unliftTriples.map((monad, symbol, tpe) =>
          tpe.asType match {
            case '[t] =>
              ValDef(symbol, Some('{ $iterator.next().asInstanceOf[t] }.asTerm))
          }
        )

      val output =
        aliasedTree.zpe.transformA(_.widen).asTypeTuple match
          case ('[r], '[e], '[t]) =>
            aliasedTree match
              case IRT.Pure(code) =>
                '{
                  $collect.map(terms => {
                    val iter = terms.iterator
                    ${ Block(makeVariables('iter), code).asExpr }.asInstanceOf[t]
                  }).asInstanceOf[ZIO[?, ?, t]]
                }
              case IRT.Monad(code, _) =>
                '{
                  $collect.flatMap(terms => {
                    val iter = terms.iterator
                    ${ Block(makeVariables('iter), code).asExpr }.asInstanceOf[ZIO[?, ?, t]]
                  }).asInstanceOf[ZIO[?, ?, t]]
                }

      output.asTerm.toZioValue(zpe)
    }

    def makeLambda(outputType: TypeRepr)(body: Term, prevValSymbolOpt: Option[Symbol]) = {
      val prevValSymbolType =
        prevValSymbolOpt match {
          case Some(oldSymbol) => oldSymbol.termRef.widenTermRefByName
          case None            => TypeRepr.of[Any]
        }

      val mtpe = MethodType(List("sm"))(_ => List(prevValSymbolType), _ => outputType)
      println(s"lambda-type:  => ${outputType.show}") // ${inputType.show}

      Lambda(
        Symbol.spliceOwner,
        mtpe,
        {
          case (methSym, List(sm: Term)) =>
            replaceSymbolInBodyMaybe(using macroQuotes)(body)(prevValSymbolOpt, sm).changeOwner(methSym)
          case _ =>
            report.errorAndAbort("Not a possible state")
        }
      )
    }
  }
}