// package zio.direct

// import zio.test._
// import zio.direct.core.util.debug.PrintMac

// object TrySpec extends AsyncAwaitSpec {

//   val e = new Exception()

//   def spec =
//     suite("TrySpec") (
//       suiteSuccess,
//       suiteFailure,
//       suiteFinally
//     ) @@ TestAspect.sequential

//   val suiteSuccess =
//     suiteAll("success") {
//       suiteAll("try pure") {
//         test("catch pure") {
//           runLiftTest(1) {
//             try 1
//             catch {
//               case `e` => 2
//             }
//           }
//         }
//         test("catch impure") {
//           runLiftTest(1) {
//             try 1
//             catch {
//               case `e` => run(defer(2))
//             }
//           }
//         }
//         test("catch pure/impure") {
//           runLiftTest(1) {
//             try 1
//             catch {
//               case `e`          => 2
//               case _: Throwable => run(defer(3))
//             }
//           }
//         }
//       }
//     }

//   val suiteFailure =
//     suite("failure") (
//       suite("try pure") {
//         test("catch pure") {
//           runLiftTest(2) {
//             try {
//               throw e
//             } catch {
//               case `e` => 2
//             }
//           }
//         }
//         +
//         test("catch impure") {
//           runLiftTest(2) {
//             try {
//               throw e
//             } catch {
//               case `e`         => run(defer(2))
//             }
//           }
//         }
//         +
//         test("catch pure/impure") {
//           runLiftTest(1) {
//             try {
//               throw e
//             } catch {
//               case `e`          => 1
//               case _: Throwable => run(defer(2))
//             }
//           }
//         }
//       }
//     )

//   val suiteFinally = suiteAll("finally") {
//     test("pure") {
//       var called = false
//       def c(): Unit = called = true
//       runLiftTest(true) {
//         val _ =
//           try {
//             run(defer(1))
//           }
//           finally {
//             c()
//           }
//         called
//       }
//     }
//     test("without catch") {
//       var called = false
//       def c() = called = true
//       runLiftTest(true) {
//         try
//           run(defer(1))
//         finally {
//           c()
//         }
//         called
//       }
//     }
//     test("as the only impure") {
//       var called = false
//       def c() = called = true
//       runLiftTest(true) {
//         val _ =
//           try 1
//           catch {
//             case `e`          => 2
//             case _: Throwable => 3
//           } finally {
//             c()
//           }
//         called
//       }
//     }
//   }
// }