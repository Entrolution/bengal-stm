/*
 * Copyright 2023 Greg von Nessi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.entrolution
package bengal.stm.runtime

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.STM
import bengal.stm.model._
import bengal.stm.model.runtime._

/** The static-analysis walker stops at the first terminal erratum, and the footprint it carries out is COMPLETE.
  *
  * These fold `staticAnalysisCompiler` directly, pinning the mechanism that keeps a blocked transaction's footprint
  * clean: no post-`waitFor` node is ever analysed, so no throw there can mark the footprint under-approximated — the
  * flag that would make the parked transaction incompatible with everything (woken by every commit, waking every other
  * parked transaction on each resubmission).
  */
class ErratumStopAnalysisSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with StmSuite {

  private def analyse(stm: STM[IO])(txn: Txn[_]): IO[Either[Throwable, IdFootprint]] =
    txn.foldMap[stm.IdFootprintStore](stm.staticAnalysisCompiler).run(IdFootprint.empty).map(_._1).attempt

  "the walk stops at a waitFor retry with a complete, unflagged footprint" in withRuntime { implicit stm =>
    // The storm shape: everything after the waitFor would throw during analysis
    // (read-your-own-write yields None there; `.get` forces it). Pre-stop, that
    // throw marked the footprint under-approximated; now the walk never gets
    // that far, and the probe proves the post-waitFor thunk did not run.
    for {
      counter <- TxnVar.of(0)
      scratch <- TxnVarMap.of(Map.empty[String, Int])
      probe   <- IO.ref(0)
      txn = for {
              v  <- stm.getTxnVar(counter)
              _  <- stm.waitFor(v > 10)
              _  <- stm.setTxnVarMapValue("k", 1, scratch)
              ov <- stm.getTxnVarMapValue("k", scratch)
              _  <- stm.fromF(probe.update(_ + 1))
              _  <- stm.delay(ov.get)
            } yield ()
      outcome   <- analyse(stm)(txn)
      probeRuns <- probe.get
      counterId <- IO(counter.runtimeId)
    } yield {
      val fp = outcome.left.toOption.get match {
        case e: stm.StaticAnalysisErratumStopException => e.idFootprint
        case other => fail(s"expected an erratum stop, got $other")
      }
      fp.isUnderApproximated shouldBe false
      fp.readIds shouldBe Set(counterId)
      fp.updatedIds shouldBe empty
      probeRuns shouldBe 0
    }
  }

  "the walk stops at an abort without flagging the footprint" in withRuntime { implicit stm =>
    // The Scala 3 gotcha shape: abort followed by a value-binding continuation.
    // Pre-stop, the analysis walked past the abort, handed the bind a Unit, the
    // forced cast threw, and the generic handler flagged the footprint — the
    // transaction then silently ran alone forever. The stop makes the abort
    // terminal in analysis exactly as it is at run time.
    for {
      counter <- TxnVar.of(0)
      txn = for {
              _ <- stm.getTxnVar(counter)
              _ <- stm.abort(new RuntimeException("boom"))
              v <- stm.pure("x")
              n <- stm.pure(v.length)
            } yield n
      outcome <- analyse(stm)(txn)
    } yield {
      val fp = outcome.left.toOption.get match {
        case e: stm.StaticAnalysisErratumStopException => e.idFootprint
        case other => fail(s"expected an erratum stop, got $other")
      }
      fp.isUnderApproximated shouldBe false
    }
  }

  "a retry inside handleErrorWith stops the WHOLE analysis with the block's reads included" in withRuntime {
    implicit stm =>
      // Mirrors the runtime: a retry is not absorbable by handlers. If the
      // handler containment swallowed the stop instead, the block's reads
      // would vanish from the footprint AND the post-handler continuation
      // would force a garbage Unit — a throw that would flag the footprint,
      // recreating the storm in this exact shape.
      for {
        gate <- TxnVar.of(false)
        txn = stm
                .handleErrorWithInternal(
                  for {
                    g <- stm.getTxnVar(gate)
                    _ <- stm.waitFor(g)
                    v <- stm.pure(42)
                  } yield v
                )(_ => stm.pure(-1))
                .map(_ + 1)
        outcome <- analyse(stm)(txn)
        gateId  <- IO(gate.runtimeId)
      } yield {
        val stop = outcome.left.toOption.get match {
          case e: stm.StaticAnalysisErratumStopException => e
          case other => fail(s"expected an erratum stop, got $other")
        }
        stop.idFootprint.isUnderApproximated shouldBe false
        stop.idFootprint.readIds shouldBe Set(gateId)
      }
  }
}
