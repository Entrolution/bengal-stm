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
package soak

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import soak.History._

/** The checker is the instrument, so the instrument gets calibrated first.
  *
  * A serializability checker that silently passes everything is worse than no checker at all: it manufactures
  * confidence. So before pointing it at the STM, feed it histories whose verdicts are known by construction — the
  * anomalies this project has actually fixed, hand-built — and confirm it names each one.
  *
  * This is the same discipline as the TLA+ negative controls: a clean run proves nothing until you have watched the
  * thing go red for the right reason.
  */
class HistorySpec extends AnyFreeSpec with Matchers {

  private val x: Key = VarKey(0)
  private val y: Key = VarKey(1)

  private def t(id: TxnId, reads: (Key, Vector[Tag])*)(appends: (Key, Tag)*): TxnRecord =
    TxnRecord(id, reads.toMap, appends.toMap)

  /** Map is invariant in its key type, so the final state has to be built at Key. */
  private def state(pairs: (Key, Vector[Tag])*): Map[Key, Vector[Tag]] = pairs.toMap

  "accepts a serializable history" - {

    "a purely serial history" in {
      // T1 appends to x, then T2 reads what T1 wrote and appends to y.
      val t1x        = tagFor(1, 0)
      val t2y        = tagFor(2, 0)
      val finalState = state(x -> Vector(t1x), y -> Vector(t2y))
      val txns = List(
        t(1)(x                   -> t1x),
        t(2, x -> Vector(t1x))(y -> t2y)
      )
      check(finalState, txns) shouldBe empty
    }

    "concurrent transactions touching disjoint keys" in {
      val t1x        = tagFor(1, 0)
      val t2y        = tagFor(2, 0)
      val finalState = state(x -> Vector(t1x), y -> Vector(t2y))
      check(finalState, List(t(1)(x -> t1x), t(2)(y -> t2y))) shouldBe empty
    }

    "a read that saw NOTHING is still serializable — it simply came first" in {
      // T2 read x as empty and T1 later appended. That is a single rw edge and
      // no cycle: T2 precedes T1. A checker that flagged this would be useless,
      // because it would flag every legitimate concurrent read.
      val t1x = tagFor(1, 0)
      check(state(x -> Vector(t1x)), List(t(1)(x -> t1x), t(2, x -> Vector.empty)())) shouldBe empty
    }
  }

  "catches the anomalies this project fixed" - {

    "WRITE SKEW (G2) — each read what the other then wrote: H3, H6" in {
      // T1: read y (empty), append x.   T2: read x (empty), append y.
      // Neither saw the other's write, so each must precede the other. Cycle.
      val t1x        = tagFor(1, 0)
      val t2y        = tagFor(2, 0)
      val finalState = state(x -> Vector(t1x), y -> Vector(t2y))
      val txns = List(
        t(1, y -> Vector.empty)(x -> t1x),
        t(2, x -> Vector.empty)(y -> t2y)
      )
      val violations = check(finalState, txns)
      violations should have size 1
      val cycle = violations.head.asInstanceOf[Cycle]
      cycle.edges.count(_.kind == RW) should be >= 2
      cycle.anomaly should include("G2")
    }

    "PHANTOM (G2) — a whole-map read missed an insert: H5" in {
      // Two transactions each read the whole map (observing NO entry for the
      // key the other will insert) and then insert their own. The empty
      // observation is what makes the phantom visible.
      val ka         = EntryKey(0, "a")
      val kb         = EntryKey(0, "b")
      val t1a        = tagFor(1, 0)
      val t2b        = tagFor(2, 0)
      val finalState = state(ka -> Vector(t1a), kb -> Vector(t2b))
      val txns = List(
        t(1, ka -> Vector.empty, kb -> Vector.empty)(ka -> t1a),
        t(2, ka -> Vector.empty, kb -> Vector.empty)(kb -> t2b)
      )
      val violations = check(finalState, txns)
      violations should have size 1
      violations.head.asInstanceOf[Cycle].anomaly should include("G2")
    }

    "READ SKEW (G-single) — a torn view across two keys: H6's probe" in {
      // A transfer T1 appends to both x and y. Reader T2 saw x BEFORE the
      // transfer and y AFTER it: exactly one rw edge, one wr edge, a cycle.
      val t1x        = tagFor(1, 0)
      val t1y        = tagFor(1, 1)
      val finalState = state(x -> Vector(t1x), y -> Vector(t1y))
      val txns = List(
        t(1)(x -> t1x, y -> t1y),
        t(2, x -> Vector.empty, y -> Vector(t1y))()
      )
      val violations = check(finalState, txns)
      violations should have size 1
      violations.head.asInstanceOf[Cycle].anomaly should include("G-single")
    }

    "LOST UPDATE — an append vanished from the final state" in {
      // T1 and T2 both appended to x, but only T2's survived: T2 overwrote the
      // list wholesale rather than extending it.
      val t1x        = tagFor(1, 0)
      val t2x        = tagFor(2, 0)
      val violations = check(state(x -> Vector(t2x)), List(t(1)(x -> t1x), t(2)(x -> t2x)))
      violations should have size 1
      violations.head shouldBe a[LostAppend]
    }

    "IMPOSSIBLE READ — a state that never existed" in {
      // T2 claims to have read [t2x] from x, but the true order is [t1x, t2x]:
      // it saw a suffix, not a prefix, which no append-only history can produce.
      val t1x = tagFor(1, 0)
      val t2x = tagFor(2, 0)
      val violations =
        check(state(x -> Vector(t1x, t2x)), List(t(1)(x -> t1x), t(2, x -> Vector(t2x))(x -> t2x)))
      violations should have size 1
      violations.head shouldBe a[ImpossibleRead]
    }
  }

  "scales past the permutation oracle" in {
    // The permutation oracle is O(n!) and caps out around four transactions;
    // this is the whole reason the checker exists. 5,000 transactions executed
    // strictly in series: each reads the full prefix of x written before it,
    // then appends. Serializable by construction, and the graph is a 5,000-node
    // chain — so this measures the cost of cycle detection, not of the history.
    val n                                 = 5000
    val order: Vector[Tag]                = (1 to n).map(i => tagFor(i, 0)).toVector
    val finalState: Map[Key, Vector[Tag]] = state(x -> order, y -> Vector.empty[Tag])

    val txns = (1 to n).map { i =>
      TxnRecord(
        id = i,
        // Read y (never written by anyone: no edges) and, implicitly via its
        // append, everything on x that preceded it.
        reads   = Map(y -> Vector.empty[Tag]),
        appends = Map(x -> tagFor(i, 0))
      )
    }.toList

    val start = System.nanoTime()
    check(finalState, txns) shouldBe empty
    val millis = (System.nanoTime() - start) / 1000000

    withClue(s"a 5,000-transaction history took ${millis}ms — detection should be near-linear in V+E: ") {
      millis should be < 5000L
    }
  }
}
