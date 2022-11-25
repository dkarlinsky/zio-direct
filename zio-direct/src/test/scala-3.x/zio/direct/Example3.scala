package zio.direct

import zio._
import zio.ZIO._
import zio.direct.core.util.debug.PrintMac
import java.sql.SQLException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import zio.direct.core.metaprog.Collect
import zio.direct.core.metaprog.Verify
import zio.direct.Dsl.Params
import scala.collection.mutable.ArrayBuffer

object Example3 {

  def main(args: Array[String]): Unit = {

    var buff = new ArrayBuffer[Int]()
    buff.addOne(1)

    // TODO add this as a unit test, it should work!
    val out =
      defer.info {
        (ZIO.succeed({ buff.update(0, buff(0) + 1); buff(0) }).run, ZIO.succeed(buff(0)).run)
      }

    // TODO Unit test that this is allowed! (figure out how to ignore the warning)
    var x = 1
    val out2 =
      defer.info {
        (ZIO.succeed({ x = x + 1 }).run, ZIO.succeed(x).run)
      }

    // TODO Test that lazy val not allowed
    // lazy val foo = 123

    // var x = 1
    // def incrementX() = { x = x + 1; x }
    // def getX() = x
    // val out2 = defer.info {
    //   (ZIO.succeed(incrementX()).run, ZIO.succeed(getX()).run)
    // }

    val outRun =
      zio.Unsafe.unsafe { implicit unsafe =>
        zio.Runtime.default.unsafe.run(out).getOrThrow()
      }
    println("====== RESULT: " + outRun)
  }

}
