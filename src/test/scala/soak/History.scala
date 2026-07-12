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

/** Serializability checking by CYCLE DETECTION, after Adya's formalism and Jepsen's Elle.
  *
  * WHY NOT JUST TRY EVERY SERIAL ORDER. `SerializabilityOracleSpec` checks an observed outcome against every
  * permutation of the transactions. That is exact, but it is O(n!) — it caps out around four transactions, which is why
  * that suite generates two to four. Every defect this project has found (H1–H6) needed either deep interleaving or a
  * specific operation mix, and none of them would have surfaced in a four-transaction window. To search where the bugs
  * actually live, the checker has to scale.
  *
  * THE TRICK THAT MAKES IT CHEAP. Model every transactional value as an append-only LIST, and make the only mutation an
  * append. Then the final list on a key IS the total order of writes to that key — recovered for free, with no
  * bookkeeping, no timestamps and no reference model. From there, a read that observed some prefix tells you exactly
  * where it sits relative to every writer of that key.
  *
  * THE GRAPH. Build the Direct Serialization Graph over transactions:
  *
  *   - ww(k): consecutive appends on k. The earlier writer precedes the later one.
  *   - wr(k): a transaction read a prefix ending in someone's append, so that writer precedes it.
  *   - rw(k): a transaction read a prefix and MISSED the next append, so it precedes that appender. This
  *     ANTI-DEPENDENCY is the one that matters — it is the edge that unprotected reads create, and every anomaly this
  *     project has fixed (H3, H5, H6) is an rw cycle.
  *
  * A cycle in that graph is, by Adya's theorem, precisely a violation of conflict-serializability. Detection is
  * O(V + E), so it scales to millions of transactions where permutation checking dies at eight.
  *
  * The cycle's shape also NAMES the anomaly, which is worth as much as detecting it: no rw edges is a write cycle;
  * exactly one is read skew; two or more is write skew / phantom.
  *
  * NOTE WHAT THE WORKLOAD MUST CONTAIN. Read-ONLY reads. A read-modify-write puts the read into the transaction's
  * footprint, so the scheduler serializes it and no anomaly is possible. Every bug this project found was an
  * UNPROTECTED READ — which is also precisely why the existing generator, whose every operation reads what it writes,
  * could never have found one.
  */
object History {

  type TxnId = Int
  type Tag   = Long

  /** A transaction appends `id * TagStride + seq`, so a tag names its writer. Transaction ids start at 1; id 0 is the
    * virtual transaction that established the initial state.
    */
  val TagStride: Long = 1000000L

  val InitTxn: TxnId = 0

  def tagFor(txn: TxnId, seq: Int): Tag = txn.toLong * TagStride + seq.toLong
  def writerOf(tag: Tag): TxnId         = (tag / TagStride).toInt

  /** A transactional location: either a plain var or one entry of one map. */
  sealed trait Key
  final case class VarKey(idx: Int)                extends Key
  final case class EntryKey(map: Int, key: String) extends Key

  /** What one transaction observed and what it wrote.
    *
    * `reads` are READ-ONLY observations — a key the transaction read but did not append to. A key it appended to is
    * ordered by the ww edges instead, and adding its implicit read would only duplicate them.
    *
    * A whole-map read records an observation for EVERY key in the pool, including an EMPTY vector for keys that were
    * absent. That empty observation is what makes a PHANTOM visible: the reader saw no such key, someone inserted one,
    * and the resulting rw edge says the reader must precede the inserter.
    */
  final case class TxnRecord(
    id: TxnId,
    reads: Map[Key, Vector[Tag]],
    appends: Map[Key, Tag]
  )

  sealed trait EdgeKind
  case object WW extends EdgeKind
  case object WR extends EdgeKind
  case object RW extends EdgeKind

  final case class Edge(from: TxnId, to: TxnId, kind: EdgeKind, key: Key) {
    override def toString: String = s"T$from -${kind.toString.toLowerCase}(${keyName(key)})-> T$to"
  }

  private def keyName(k: Key): String = k match {
    case VarKey(i)      => s"v$i"
    case EntryKey(m, s) => s"m$m[$s]"
  }

  /** Everything the checker can find wrong with a history. */
  sealed trait Violation { def explain: String }

  /** An append is not in the final state at all: someone's write was overwritten wholesale. A lost update. */
  final case class LostAppend(txn: TxnId, key: Key, tag: Tag) extends Violation {
    def explain: String =
      s"LOST UPDATE: T$txn appended $tag to ${keyName(key)}, but it is absent from the final state — " +
        "a concurrent writer overwrote it, so the two were never serialized against each other."
  }

  /** A read observed something that is not a prefix of the key's true write order. Appends only ever extend a list, so
    * every legitimate observation is a prefix; anything else means the reader saw a state that never existed.
    */
  final case class ImpossibleRead(txn: TxnId, key: Key, observed: Vector[Tag], actual: Vector[Tag]) extends Violation {
    def explain: String =
      s"IMPOSSIBLE READ: T$txn read ${observed.mkString("[", ",", "]")} from ${keyName(key)}, which is not a prefix " +
        s"of its true write order ${actual.mkString("[", ",", "]")} — that state never existed."
  }

  /** A cycle in the DSG: by Adya's theorem, exactly a violation of conflict-serializability. */
  final case class Cycle(edges: List[Edge]) extends Violation {

    /** The cycle's rw-edge count names the anomaly class. */
    def anomaly: String = edges.count(_.kind == RW) match {
      case 0 => "G0/G1c — write cycle or cyclic information flow"
      case 1 => "G-single — read skew (a transaction saw one key before a writer and another after it)"
      case _ => "G2 — write skew / phantom (an anti-dependency cycle: each read what the other then overwrote)"
    }

    def explain: String =
      s"NOT SERIALIZABLE [${anomaly}]\n  cycle: ${edges.map(_.toString).mkString(" ")}"
  }

  /** Check a completed history.
    *
    * @param finalState
    *   the true, total write order of every key, read after all transactions have committed
    * @param txns
    *   what each transaction observed and appended
    * @return
    *   every violation found (empty means the history is conflict-serializable)
    */
  def check(finalState: Map[Key, Vector[Tag]], txns: List[TxnRecord]): List[Violation] = {
    val lost       = lostAppends(finalState, txns)
    val impossible = impossibleReads(finalState, txns)

    // A cycle search over a history that already contains lost updates or
    // impossible reads would report noise derived from the corruption rather
    // than from the interleaving, so report the primary faults alone.
    if (lost.nonEmpty || impossible.nonEmpty) lost ++ impossible
    else findCycle(buildGraph(finalState, txns)).toList
  }

  private def lostAppends(finalState: Map[Key, Vector[Tag]], txns: List[TxnRecord]): List[Violation] =
    for {
      t             <- txns
      (key, tag)    <- t.appends.toList
      if !finalState.getOrElse(key, Vector.empty).contains(tag)
    } yield LostAppend(t.id, key, tag)

  private def impossibleReads(finalState: Map[Key, Vector[Tag]], txns: List[TxnRecord]): List[Violation] =
    for {
      t               <- txns
      (key, observed) <- t.reads.toList
      actual = finalState.getOrElse(key, Vector.empty)
      if !actual.startsWith(observed)
    } yield ImpossibleRead(t.id, key, observed, actual)

  /** ww, wr and rw edges, exactly as described in the class comment. */
  def buildGraph(finalState: Map[Key, Vector[Tag]], txns: List[TxnRecord]): List[Edge] = {
    val wwEdges: List[Edge] =
      finalState.toList.flatMap { case (key, order) =>
        order.sliding(2).collect { case Vector(a, b) if writerOf(a) != writerOf(b) =>
          Edge(writerOf(a), writerOf(b), WW, key)
        }.toList
      }

    val readEdges: List[Edge] =
      for {
        t               <- txns
        (key, observed) <- t.reads.toList
        order = finalState.getOrElse(key, Vector.empty)
        if order.startsWith(observed) // impossible reads are reported separately
        edge <- {
          // wr: the last writer this transaction SAW must precede it.
          val wr = observed.lastOption.map(writerOf).filter(_ != t.id).map(Edge(_, t.id, WR, key))
          // rw: the next writer, which this transaction MISSED, must follow it.
          // THIS is the anti-dependency, and it is the edge unprotected reads create.
          val rw = order.lift(observed.length).map(writerOf).filter(_ != t.id).map(Edge(t.id, _, RW, key))
          wr.toList ++ rw.toList
        }
      } yield edge

    wwEdges ++ readEdges
  }

  /** Iterative DFS with an explicit stack — a soak history is far too deep for recursion. Returns the first cycle
    * found, with its edges in order.
    */
  private def findCycle(edges: List[Edge]): Option[Cycle] = {
    val adj: Map[TxnId, List[Edge]] = edges.groupBy(_.from).withDefaultValue(Nil)
    val nodes: Set[TxnId]           = edges.flatMap(e => List(e.from, e.to)).toSet

    val White = 0
    val Grey  = 1
    val Black = 2

    val colour = scala.collection.mutable.Map.empty[TxnId, Int].withDefaultValue(White)
    // The edge by which each node was entered, so a cycle can be reconstructed.
    val via = scala.collection.mutable.Map.empty[TxnId, Edge]

    def walk(root: TxnId): Option[Cycle] = {
      // (node, remaining outgoing edges to explore)
      val stack = scala.collection.mutable.Stack[(TxnId, List[Edge])]()
      colour(root) = Grey
      stack.push((root, adj(root)))

      while (stack.nonEmpty) {
        val (node, pending) = stack.pop()
        pending match {
          case Nil =>
            colour(node) = Black
          case e :: rest =>
            stack.push((node, rest))
            colour(e.to) match {
              case White =>
                colour(e.to) = Grey
                via(e.to) = e
                stack.push((e.to, adj(e.to)))
              case Grey =>
                // Back edge: e.to is on the current path, so walking `via`
                // backwards from `node` to `e.to` reconstructs the cycle.
                var path = List(e)
                var cur  = node
                while (cur != e.to) {
                  val in = via(cur)
                  path = in :: path
                  cur = in.from
                }
                return Some(Cycle(path))
              case _ => () // Black: already fully explored, cannot be on the current path
            }
        }
      }
      None
    }

    nodes.toList.sorted.collectFirst(Function.unlift { n =>
      if (colour(n) == White) walk(n) else None
    })
  }
}
