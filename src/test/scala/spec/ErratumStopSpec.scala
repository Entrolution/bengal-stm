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

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.STM
import bengal.stm.model._
import bengal.stm.syntax.all._

/** Behavioural pins for the erratum-stop analysis: both interpreter passes stop at the first `waitFor` retry or
  * `abort`, so a step positioned after one runs in NEITHER pass for that attempt. A woken transaction reuses its parked
  * footprint (wakes fire from pre-publish sweeps, so re-analysis could not see the satisfied predicate); the woken
  * run's undeclared accesses are refined from the actual log — one bounded refinement lap per wake that writes.
  */
class ErratumStopSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with StmSuite {

  "a thunk after a blocking waitFor never runs while parked, and runs both passes on the successful attempt" in {
    withRuntime { implicit stm =>
      for {
        counter <- TxnVar.of(0)
        probe   <- IO.ref(0)
        started <- IO.deferred[Unit]
        waiter <- (for {
                    v <- counter.get
                    // Handshake BEFORE the waitFor: it fires during the very
                    // analysis pass that would have run the phantom thunk, so
                    // the parked-probe read below is sequenced after the pass
                    // in question — the red-check needs no timing luck.
                    _ <- STM[IO].fromF(started.complete(()).attempt.void)
                    _ <- STM[IO].waitFor(v > 10)
                    _ <- STM[IO].fromF(probe.update(_ + 1))
                  } yield ()).commit.start
        _      <- started.get
        _      <- IO.sleep(50.millis)
        parked <- probe.get
        _      <- counter.set(11).commit
        _      <- waiter.joinWithNever.timeout(10.seconds)
        after  <- probe.get
      } yield (parked, after)
    }
      .asserting { case (parked, after) =>
        parked shouldBe 0 // the unreachable thunk ran in NO pass (pre-stop: 1, from analysis)
        after shouldBe 1 // exactly the log pass of the lap that finally sailed past the waitFor
      }
  }

  "a woken transaction with post-waitFor writes publishes exactly once, after one refinement lap" in {
    withRuntime { implicit stm =>
      // The structural cost of parking on the predicate's exact dependency
      // set: the woken run's writes are undeclared by the parked footprint
      // (the analysis stopped at the waitFor, and no wake-time analysis can
      // see the satisfied predicate — wakes fire from pre-publish sweeps), so
      // coverage refuses the publish, refines from the actual log, and one
      // more lap commits. runLaps counts log-pass executions of the write (an
      // F-variant setter value is never forced during analysis) on laps that
      // reach it; the result pins exactly-once publication regardless.
      for {
        buf     <- TxnVar.of(0)
        out     <- TxnVar.of(0)
        runLaps <- IO.ref(0)
        consumer <- (for {
                      b <- buf.get
                      _ <- STM[IO].waitFor(b > 0)
                      _ <- out.modifyF(v => runLaps.update(_ + 1).as(v + b))
                    } yield ()).commit.start
        _      <- IO.sleep(50.millis)
        _      <- buf.set(5).commit
        _      <- consumer.joinWithNever.timeout(10.seconds)
        laps   <- runLaps.get
        result <- out.get.commit
      } yield (laps, result)
    }
      .asserting { case (laps, result) =>
        laps shouldBe 2 // the divergent lap (refused by coverage) + the refined lap that publishes
        result shouldBe 5 // published exactly once — the refused lap's write never landed
      }
  }

  "a waitFor inside handleErrorWith still parks, wakes, and completes with the block's value" in {
    withRuntime { implicit stm =>
      for {
        gate <- TxnVar.of(false)
        waiter <- (for {
                    g <- gate.get
                    _ <- STM[IO].waitFor(g)
                    v <- STM[IO].pure(42)
                  } yield v)
                    .handleErrorWith(_ => STM[IO].pure(-1))
                    .map(_ + 1)
                    .commit
                    .start
        _      <- IO.sleep(50.millis)
        _      <- gate.set(true).commit
        result <- waiter.joinWithNever.timeout(10.seconds)
      } yield result
    }
      .asserting(_ shouldBe 43) // the retry was not absorbed; the woken block's value flowed past the handler
  }

  "a transaction that analysed past the gate but retries at run time parks and completes cleanly" in {
    withRuntime { implicit stm =>
      // The passes diverge deliberately: the ANALYSIS reads the gate open,
      // then suspends on the blocker while the test closes the gate (nothing
      // is registered yet, so the closing commit cannot queue), then resumes
      // past the satisfied waitFor into a post-region that throws there —
      // flagging the footprint. The RUN reads the gate closed and retries;
      // the retry path refines the flagged footprint from the actual log
      // instead of parking incompatible-with-everything, so the transaction
      // parks quietly on its true read set and the reopened gate wakes it.
      for {
        gate    <- TxnVar.of(true)
        scratch <- TxnVarMap.of(Map.empty[String, Int])
        calls   <- IO.ref(0)
        reached <- IO.deferred[Unit]
        release <- IO.deferred[Unit]
        blockOnlyFirstCall = calls.getAndUpdate(_ + 1).flatMap { n =>
                               if (n == 0) reached.complete(()).void >> release.get else IO.unit
                             }
        waiter <- (for {
                    g  <- gate.get
                    _  <- STM[IO].fromF(blockOnlyFirstCall)
                    _  <- STM[IO].waitFor(g)
                    _  <- scratch.set("k", 1)
                    ov <- scratch.get("k")
                    _  <- STM[IO].delay(ov.get)
                  } yield ()).commit.start
        _ <- reached.get // the analysis holds gate=true and is suspended mid-walk
        _ <- gate.set(false).commit
        _ <- release.complete(())
        _ <- IO.sleep(100.millis) // analysis flags; run retries on the closed gate; refined park
        _ <- gate.set(true).commit
        _ <- waiter.joinWithNever.timeout(10.seconds)
        k <- scratch.get("k").commit
      } yield k
    }
      .asserting(_ shouldBe Some(1))
  }
}
