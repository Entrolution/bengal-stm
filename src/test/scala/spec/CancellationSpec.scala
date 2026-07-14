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

/** The cancellation contract of `commit`: cancelling the returned `F` ABANDONS the transaction.
  *
  * Once the cancellation completes, the transaction never begins executing again, never parks or retries, and holds no
  * scheduler state. A commit window already executing when cancellation arrives runs to completion (the window is
  * uncancelable by design, so its writes may be published) — cancellation prevents every FUTURE window, promptly and
  * without waiting on the in-flight one.
  *
  * The dangerous pre-contract behaviour these pin against: a parked `waitFor` transaction surviving its caller's
  * cancellation in the retry map, being woken by a later conflicting commit, and publishing its effects into a
  * completion signal nobody holds — resurrection at unbounded distance.
  */
class CancellationSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with StmSuite {

  /** An effect that passes its FIRST evaluation (the static-analysis pass) and gates its SECOND (the log run) on the
    * supplied Deferred — parking the transaction mid-execute-window without gating the analysis phase, which also
    * forces `fromF` thunks. Completes `entered` just before blocking, so a test can await "the window is genuinely
    * executing" instead of guessing with a sleep.
    */
  private def gateSecondRun(
    gate: cats.effect.Deferred[IO, Unit],
    entered: cats.effect.Deferred[IO, Unit]
  ): IO[IO[Unit]] =
    IO.ref(0).map { counter =>
      counter.getAndUpdate(_ + 1).flatMap { n =>
        if (n == 0) IO.unit else entered.complete(()).void >> gate.get
      }
    }

  "a cancelled parked transaction never resurrects" in withRuntime { implicit stm =>
    for {
      gate <- TxnVar.of(false)
      out  <- TxnVar.of(0)
      waiter <- (for {
                  g <- gate.get
                  _ <- STM[IO].waitFor(g)
                  _ <- out.set(1)
                } yield ()).commit.start
      _ <- IO.sleep(50.millis) // let the waiter reach the park with high probability
      _ <- waiter.cancel
      _ <- gate.set(true).commit // pre-contract, this woke the parked waiter
      // Serialized conflicting commits create happens-after windows in which a
      // (buggy) resurrected waiter's write would land before the final read.
      _      <- out.modify(identity).commit
      _      <- IO.sleep(50.millis)
      result <- out.get.commit
    } yield result
  }
    .asserting(_ shouldBe 0)

  // The cancellation-vs-wake RACE — a wake closure in flight while abandon
  // completes — is pinned deterministically in runtime/AbandonProtocolSpec
  // ("a wake action that fired before abandonment cannot re-register"),
  // which constructs that exact schedule white-box. A behavioural IO.both
  // race here would additionally race the wake pipeline against the flag
  // WRITE, where the contract legitimately permits a publish (a window that
  // began before the flag was set) — an assertion that can only be made
  // strict by relying on scheduler timing, i.e. a flake.

  "abandoning a queued transaction releases the peers queued behind it" in withRuntime { implicit stm =>
    // T0 holds var A mid-window; T1 (A+B) queues behind T0; T2 (B) queues
    // behind T1 only. Cancelling T1 must let T2 complete WHILE T0 is still
    // gated — pre-contract, T2 could only run after T0 released and T1 ran.
    for {
      varA      <- TxnVar.of(0)
      varB      <- TxnVar.of(0)
      t0Gate    <- IO.deferred[Unit]
      t0Entered <- IO.deferred[Unit]
      t0Body    <- gateSecondRun(t0Gate, t0Entered)
      t0 <- (for {
              a <- varA.get
              _ <- STM[IO].fromF(t0Body)
              _ <- varA.set(a + 1)
            } yield ()).commit.start
      // Deterministic staging: T0 is provably mid-window before T1 submits, so
      // Contract C (A-incompatibility) makes it impossible for T1 to execute
      // before the cancel below, whatever the pool load.
      _ <- t0Entered.get
      t1 <- (for {
              a <- varA.get
              b <- varB.get
              _ <- varA.set(a + 10)
              _ <- varB.set(b + 10)
            } yield ()).commit.start
      _ <- IO.sleep(50.millis) // T1 queued behind T0
      t2 <- (for {
              b <- varB.get
              _ <- varB.set(b + 100)
            } yield ()).commit.start
      _ <- IO.sleep(50.millis) // T2 queued behind T1
      _ <- t1.cancel
      // T2 must complete while T0 is still gated: its only obstacle was T1.
      _      <- t2.joinWithNever.timeout(5.seconds)
      bEarly <- varB.get.commit
      _      <- t0Gate.complete(())
      _      <- t0.joinWithNever.timeout(5.seconds)
      aFinal <- varA.get.commit
    } yield (bEarly, aFinal)
  }
    .asserting { case (bEarly, aFinal) =>
      bEarly shouldBe 100 // T2 ran; T1's +10 never happened
      aFinal shouldBe 1 // T0 completed after release
    }

  "cancellation during an executing window is prompt and the window still completes" in withRuntime { implicit stm =>
    for {
      counter <- TxnVar.of(0)
      gate    <- IO.deferred[Unit]
      entered <- IO.deferred[Unit]
      body    <- gateSecondRun(gate, entered)
      t1 <- (for {
              c <- counter.get
              _ <- STM[IO].fromF(body)
              _ <- counter.set(c + 1)
            } yield ()).commit.start
      _ <- entered.get // T1 provably into its gated window
      // Cancel must return promptly — it may not wait for the gated window.
      _ <- t1.cancel.timeout(2.seconds)
      _ <- gate.complete(())
      _ <- IO.sleep(100.millis) // window runs to completion per contract
      // The scheduler must be fully quiescent afterwards: an unrelated
      // transaction on the same var runs normally.
      _      <- counter.modify(_ + 10).commit
      result <- counter.get.commit
    } yield result
  }
    .asserting(_ shouldBe 11) // window's +1 published, follow-up +10 applied

  "a timed-out blocked commit raises TimeoutException and leaves no residue" in withRuntime { implicit stm =>
    for {
      gate <- TxnVar.of(false)
      out  <- TxnVar.of(0)
      outcome <- (for {
                   g <- gate.get
                   _ <- STM[IO].waitFor(g)
                   _ <- out.set(1)
                 } yield ()).commit.timeout(100.millis).attempt
      _      <- gate.set(true).commit
      _      <- IO.sleep(50.millis)
      result <- out.get.commit
    } yield (outcome, result)
  }
    .asserting { case (outcome, result) =>
      outcome.left.toOption.get shouldBe a[java.util.concurrent.TimeoutException]
      result shouldBe 0
    }
}
