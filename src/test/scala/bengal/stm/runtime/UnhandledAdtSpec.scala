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

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.free.Free
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import bengal.stm.model._

/** TxnAdt is UNSEALED (its cases are path-dependent inside TxnAdtContext, so the compiler cannot enforce interpreter
  * exhaustiveness), which means an ADT case an interpreter does not handle compiles clean. The interpreters' defaults
  * used to be silent no-ops — for the analysis walker that is an unrecorded footprint, the exact soundness-hole class
  * the footprint machinery exists to close. Both defaults now RAISE.
  *
  * What a caller observes: the analysis walker's raise is absorbed by analyseFootprint's catch-all into an
  * under-approximated footprint (the transaction runs alone — strictly safe), and the LOG walker's raise then fails the
  * commit, so the unhandled case surfaces as a failed F rather than a silently skipped operation. One carve-out: nested
  * inside a handleErrorWith, the raise is treated as a transaction error and routed to the recovery arm — the program's
  * own handler observes it, so even that path is not a silent no-op.
  */
class UnhandledAdtSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers with StmSuite {

  "an ADT case no interpreter handles" - {

    "fails the commit instead of silently no-oping" in withRuntime { implicit stm =>
      case object RogueAdt extends TxnAdt[Unit]

      val rogue: Txn[Unit] = Free.liftF[TxnOrErr, Unit](Right(RogueAdt))

      stm.commitTxn(rogue).attempt.map {
        case Left(ex) => ex shouldBe a[MatchError]
        case Right(v) => fail(s"commit unexpectedly succeeded with $v")
      }
    }
  }
}
