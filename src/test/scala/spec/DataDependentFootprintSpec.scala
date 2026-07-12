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
package spec

import scala.concurrent.duration._

import cats.effect.unsafe.implicits.global
import cats.effect.{ Deferred, IO, Ref }
import cats.syntax.all._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.STM
import bengal.stm.model._
import bengal.stm.syntax.all._

/** H6 — DATA-DEPENDENT FOOTPRINT DIVERGENCE, behaviourally.
  *
  * The TLA+ model (specs/commit/CommitH6.cfg) shows that a transaction scheduled on a footprint that names the WRONG
  * IDS breaks serializability by exactly the H3 mechanism — reads are never commit-validated and hold no lock, so the
  * scheduler is the only defence, and it cannot defend against a footprint that does not describe the transaction.
  *
  * This suite shows the same thing on the shipped code, and DETERMINISTICALLY rather than by racing a microsecond
  * window. The lever is that `staticAnalysisCompiler` executes `TxnDelay` thunks — so `STM[F].fromF` lets the test
  * suspend the ANALYSIS pass itself, mid-flight, and act while it is parked.
  *
  * The mechanism, step by step. Steps 1 to 5 STILL HAPPEN — the fix does not close the divergence, it catches it — and
  * step 6 is what it catches:
  *
  *   1. `analysis` reads kv1/kv2 — the key SOURCES — and parks on `analysisGate`. It has already decided it will touch
  *      map entries "c" and "d".
  *   2. With it parked, the test flips kv1 := "a", kv2 := "b". The transaction is NOT yet in `activeTransactions` (it
  *      has not been submitted — the analysis has not returned), so nothing serializes the flip against it. This is the
  *      gap: TxnRuntime.commit runs the walker BEFORE submitTxn, outside any Contract-C window.
  *   3. The analysis resumes and declares reads of entries "c" and "d". That footprint is now simply WRONG, and NOTHING
  *      THREW — so the H3 under-approximation flag never fires and the scheduler trusts it.
  *   4. The real log run reads kv1/kv2 again, gets "a"/"b", and touches entries "a" and "b" — which the scheduler never
  *      knew about, and which it therefore never protected.
  *   5. A `transfer` transaction (which moves 1 from entry "a" to entry "b", so a+b is invariantly 0) is judged
  *      COMPATIBLE with the declared footprint, because that footprint mentions "c" and "d". It runs concurrently and
  *      commits between the reader's two reads. The reader's LOG now holds a torn view of the transfer: entry "a" from
  *      before it, entry "b" from after, summing to 1 — an outcome no serial order produces, since a + b is 0 in every
  *      committed state.
  *   6. THE FIX. That torn view never reaches the caller. Before publishing, and UNDER THE COMMIT LOCKS,
  *      `AnalysedTxn.commit` asks whether the DECLARED footprint COVERS the one the run actually built
  *      (`IdFootprint.covers`). Declaring reads of "c" and "d" covers nothing about "a" and "b", so the answer is no,
  *      the placement was unsound, and the transaction takes the same road as a dirty log: release the locks, refine
  *      the footprint from the ACTUAL log, and re-run. The re-run is scheduled on a footprint that describes it, so the
  *      transfer is now a conflict and cannot slip between its reads.
  *
  * Which is why the observable below is a sum of 0 and not an absence of tearing: the tear still happens, and is thrown
  * away.
  */
class DataDependentFootprintSpec extends AnyFreeSpec with Matchers {

  /** Observed sum of the two map entries the data-dependent reader actually read. The transfer preserves a + b = 0 in
    * every committed state, so any value other than 0 is an outcome no serial order can produce.
    */
  private def observedSum: Int =
    STM
      .runtime[IO]
      .flatMap { implicit stm =>
        for {
          // Parks the ANALYSIS pass after it has read the key sources but before it
          // has decided which map entries it will touch.
          analysisGate <- Deferred[IO, Unit]
          // Parks the LOG RUN between its two map reads, so the transfer can land
          // in the middle. Counting passes is what distinguishes the two: the
          // analysis is pass 1 and sails through; the log run is pass 2 and waits.
          readGate <- Deferred[IO, Unit]
          passes   <- Ref[IO].of(0)

          m   <- TxnVarMap.of[IO, String, Int](Map("a" -> 0, "b" -> 0, "c" -> 0, "d" -> 0))
          kv1 <- TxnVar.of[IO, String]("c")
          kv2 <- TxnVar.of[IO, String]("d")
          obs <- TxnVar.of[IO, Int](0)

          reader = for {
                     k1 <- kv1.get
                     k2 <- kv2.get
                     _  <- STM[IO].fromF(analysisGate.get)
                     v1 <- m.get(k1)
                     _ <- STM[IO].fromF(
                            passes.updateAndGet(_ + 1).flatMap(n => IO.whenA(n >= 2)(readGate.get))
                          )
                     v2 <- m.get(k2)
                     _  <- obs.set(v1.getOrElse(0) + v2.getOrElse(0))
                   } yield ()

          // Moves 1 from entry "a" to entry "b". a + b is 0 in every committed state.
          transfer = for {
                       a <- m.get("a")
                       b <- m.get("b")
                       _ <- m.set("a", a.getOrElse(0) - 1)
                       _ <- m.set("b", b.getOrElse(0) + 1)
                     } yield ()

          readerFiber <- reader.commit.start
          _           <- IO.sleep(100.millis) // let the analysis read kv1/kv2 and park
          _           <- (kv1.set("a") >> kv2.set("b")).commit
          _           <- analysisGate.complete(()) // analysis resumes and declares "c"/"d"
          _           <- IO.sleep(100.millis) // let the log run read entry "a" and park
          _           <- transfer.commit // slips through: judged compatible with "c"/"d"
          _           <- readGate.complete(()) // log run resumes and reads entry "b"
          _           <- readerFiber.joinWithNever
          result      <- obs.get.commit
        } yield result
      }
      .timeout(60.seconds)
      .unsafeRunSync()

  "H6 regression — a data-dependent footprint cannot expose a torn read" in {
    val reps     = 20
    val observed = (1 to reps).map(_ => observedSum)

    withClue(
      "the reader summed two map entries whose invariant is a + b = 0, and got something else — it saw one entry " +
        "from before a transfer and the other from after. It was scheduled on a footprint naming entries 'c' and 'd' " +
        "while actually reading 'a' and 'b', so the scheduler never serialized it against the transfer. If this is " +
        "red, the commit-time coverage check (IdFootprint.covers, applied in AnalysedTxn.commit) is no longer " +
        s"catching the divergence. Observed sums: ${observed.distinct.mkString(", ")}: "
    ) {
      observed.foreach(_ shouldBe 0)
    }
  }
}
