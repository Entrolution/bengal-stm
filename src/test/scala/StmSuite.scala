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

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import bengal.stm.STM

/** The shared entry point for standing up an STM runtime in a test. It exists for the TIMEOUT, not for the two lines of
  * boilerplate it saves.
  *
  * A LOST WAKEUP HANGS. A parked transaction that is never woken yields no value, no exception and no stack — the fiber
  * simply never runs again. There is nothing to assert against, so the timeout IS the assertion. Every failure mode
  * this project's model found in the park/wake and commit machinery has that shape: a lost wakeup (H1, and the
  * absent-key variant), a commit-lock cycle (H2), an unbounded retry loop (the null-result defect). None of them
  * produce a wrong value to catch; they produce silence.
  *
  * Without a timeout the consequence is not a failing test, it is a failing JOB: CI allots 60 minutes
  * (.github/workflows/ci.yml, `timeout-minutes: 60`) and a hung fiber burns all of it, then reports a cancelled run
  * with no failure message and no stack. Five park/wake tests were written that way. Baking the timeout into the shared
  * entry point is what stops the sixth — the ergonomic way to open a runtime is now the protected one.
  *
  * The default is deliberately generous. It is a HANG DETECTOR, not a performance assertion, and it must not fire under
  * load: a spurious fire costs a LEAKED FIBER, because fibers deadlocked inside `withLock` are uninterruptible
  * (`AnalysedTxn.commit` wraps it in `Async[F].uncancelable` and discards the poll, so a fiber blocked on
  * `Semaphore.permit` cannot be cancelled). Suites whose workload legitimately runs longer take the explicit overload
  * rather than dropping the guard.
  */
trait StmSuite {

  /** Long enough that scheduling pressure never trips it; short enough that a hang is a red test rather than a dead
    * job.
    */
  protected val DefaultTimeout: FiniteDuration = 30.seconds

  protected def withRuntime[A](f: STM[IO] => IO[A]): IO[A] =
    withRuntime(DefaultTimeout)(f)

  protected def withRuntime[A](timeout: FiniteDuration)(f: STM[IO] => IO[A]): IO[A] =
    STM.runtime[IO].flatMap(f).timeout(timeout)

  // The synchronous sibling, for AnyFreeSpec suites that drive the runtime
  // with unsafeRunSync: same runtime, same hang-detector timeout, one place
  // to open both.
  protected def withRuntimeSync[A](f: STM[IO] => IO[A]): A =
    withRuntimeSync(DefaultTimeout)(f)

  protected def withRuntimeSync[A](timeout: FiniteDuration)(f: STM[IO] => IO[A]): A =
    withRuntime(timeout)(f).unsafeRunSync()
}
