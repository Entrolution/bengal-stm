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

import scala.annotation.nowarn

import cats.effect.implicits._
import cats.effect.kernel.{ Async, Resource }
import cats.effect.std.Semaphore
import cats.syntax.all._

import bengal.stm.model._
import bengal.stm.model.runtime._

private[stm] trait TxnLogContext[F[_]] {
  this: AsyncImplicits[F] =>

  sealed private[stm] trait TxnLogEntry[V] {
    private[stm] def get: V
    private[stm] def set(value: V): TxnLogEntry[V]
    private[stm] def commit: F[Unit]
    private[stm] def isDirty: F[Boolean]

    // Unlike isDirty — which read-only entries hardcode to false because
    // commit-time validation is the scheduler's job — this REALLY compares
    // the entry's initial value against live state, read-only entries
    // included. The park path (submitTxnForRetry) uses it to detect
    // conflicting commits that landed after the log ran but whose wake
    // sweep has already been spent (the H1 lost-wakeup fix).
    private[stm] def hasChangedSinceRead: F[Boolean]

    // The commitLock this entry must hold to publish, PAIRED WITH THE RUNTIME
    // ID OF THE ENTITY THAT OWNS IT. Read-only entries hold no lock at all.
    //
    // The owner id has to travel WITH the lock because it cannot be recovered
    // from the log key, and it is what withLock sorts on — the H2 fix, argued in
    // full there.
    private[stm] def lock: F[Option[(TxnVarRuntimeId, Semaphore[F])]]

    private[stm] def idFootprint: F[IdFootprint]

  }

  // RO entry is not necessary for pure transactions.
  // However, to make the library interface more
  // predictable in the face of side-effects, RO
  // entries are created
  //
  // SPEC: NoLocksWithoutWrites — every read-only entry returns lock = None and
  // isDirty = pure(false). Two consequences, and both are load-bearing:
  //   1. Commit locks cover the WRITE SET ONLY, and reads are never validated at
  //      commit time at all. Serializability therefore rests ENTIRELY on the
  //      scheduler's footprint conflict-avoidance — which is why footprint
  //      accuracy is a safety precondition (H3) and why a gap in the
  //      compatibility relation is a direct hole (H5). Removing the Contract C
  //      guard from Spec A breaks CommitSnapshotValid even with accurate
  //      footprints and every lock intact — negative control NC-4.
  //
  //      That was always true, and it is now the WHOLE truth: the commit-time
  //      dirty check is gone. It could never fire (CoverageSubsumesDirty), so
  //      Contract C is not merely the main defence, it is the only one — and
  //      spec/ContractCSpec exists to check the running code still provides it.
  //   2. A pure reader's log acquires NO locks and can never report dirty, so a
  //      re-validation of it before a park would be vacuous. That is why the H1
  //      fix needed hasChangedSinceRead (below) — a real comparison against live
  //      state that INCLUDES read-only entries — rather than a write-set check.
  private[stm] case class TxnLogReadOnlyVarEntry[V](
    initial: V,
    txnVar: TxnVar[F, V]
  ) extends TxnLogEntry[V] {

    override private[stm] def get: V =
      initial

    override private[stm] def set(value: V): TxnLogEntry[V] =
      if (value != initial) {
        TxnLogUpdateVarEntry[V](
          initial = initial,
          current = value,
          txnVar  = txnVar
        )
      } else {
        this
      }

    override private[stm] lazy val commit: F[Unit] =
      Async[F].unit

    override private[stm] lazy val isDirty: F[Boolean] =
      Async[F].pure(false)

    override private[stm] lazy val hasChangedSinceRead: F[Boolean] =
      txnVar.get.map(_ != initial)

    override private[stm] lazy val lock: F[Option[(TxnVarRuntimeId, Semaphore[F])]] =
      Async[F].pure(None)

    override private[stm] lazy val idFootprint: F[IdFootprint] =
      Async[F].delay(txnVar.runtimeId).map { rid =>
        IdFootprint(readIds = Set(rid), updatedIds = Set())
      }
  }

  private[stm] case class TxnLogUpdateVarEntry[V](
    initial: V,
    current: V,
    txnVar: TxnVar[F, V]
  ) extends TxnLogEntry[V] {

    override private[stm] def get: V =
      current

    override private[stm] def set(value: V): TxnLogEntry[V] =
      if (initial != value) {
        TxnLogUpdateVarEntry[V](
          initial = initial,
          current = value,
          txnVar  = txnVar
        )
      } else {
        TxnLogReadOnlyVarEntry[V](
          initial = initial,
          txnVar  = txnVar
        )
      }

    override private[stm] lazy val commit: F[Unit] =
      txnVar.set(current)

    override private[stm] lazy val isDirty: F[Boolean] =
      txnVar.get.map(_ != initial)

    override private[stm] lazy val hasChangedSinceRead: F[Boolean] =
      isDirty

    override private[stm] lazy val lock: F[Option[(TxnVarRuntimeId, Semaphore[F])]] =
      Async[F].delay(Some((txnVar.runtimeId, txnVar.commitLock)))

    override private[stm] lazy val idFootprint: F[IdFootprint] =
      Async[F].delay(txnVar.runtimeId).map { rid =>
        IdFootprint(readIds = Set(), updatedIds = Set(rid))
      }
  }

  // See above comment for RO entry
  private[stm] case class TxnLogReadOnlyVarMapStructureEntry[K, V](
    initial: Map[K, V],
    txnVarMap: TxnVarMap[F, K, V]
  ) extends TxnLogEntry[Map[K, V]] {

    override private[stm] def get: Map[K, V] =
      initial

    override private[stm] def set(value: Map[K, V]): TxnLogEntry[Map[K, V]] =
      if (initial != value) {
        TxnLogUpdateVarMapStructureEntry(
          initial   = initial,
          current   = value,
          txnVarMap = txnVarMap
        )
      } else {
        this
      }

    override private[stm] lazy val commit: F[Unit] =
      Async[F].unit

    override private[stm] lazy val isDirty: F[Boolean] =
      Async[F].pure(false)

    override private[stm] lazy val hasChangedSinceRead: F[Boolean] =
      txnVarMap.get.map(_ != initial)

    override private[stm] lazy val lock: F[Option[(TxnVarRuntimeId, Semaphore[F])]] =
      Async[F].pure(None)

    override private[stm] lazy val idFootprint: F[IdFootprint] =
      Async[F].delay(txnVarMap.runtimeId).map { rid =>
        IdFootprint(readIds = Set(rid), updatedIds = Set())
      }
  }

  private[stm] case class TxnLogUpdateVarMapStructureEntry[K, V](
    initial: Map[K, V],
    current: Map[K, V],
    txnVarMap: TxnVarMap[F, K, V]
  ) extends TxnLogEntry[Map[K, V]] {

    override private[stm] lazy val get: Map[K, V] =
      current

    override private[stm] def set(value: Map[K, V]): TxnLogEntry[Map[K, V]] =
      if (initial != value) {
        TxnLogUpdateVarMapStructureEntry(
          initial   = initial,
          current   = value,
          txnVarMap = txnVarMap
        )
      } else {
        TxnLogReadOnlyVarMapStructureEntry(
          initial   = initial,
          txnVarMap = txnVarMap
        )
      }

    override private[stm] lazy val commit: F[Unit] =
      Async[F].unit

    override private[stm] lazy val isDirty: F[Boolean] =
      txnVarMap.get.map(_ != initial)

    override private[stm] lazy val hasChangedSinceRead: F[Boolean] =
      isDirty

    override private[stm] lazy val lock: F[Option[(TxnVarRuntimeId, Semaphore[F])]] =
      Async[F].delay(Some((txnVarMap.runtimeId, txnVarMap.commitLock)))

    override private[stm] lazy val idFootprint: F[IdFootprint] =
      Async[F].delay(txnVarMap.runtimeId).map { rid =>
        IdFootprint(readIds = Set(), updatedIds = Set(rid))
      }
  }

  // See above comment for RO entry
  private[stm] case class TxnLogReadOnlyVarMapEntry[K, V](
    key: K,
    initial: Option[V],
    txnVarMap: TxnVarMap[F, K, V]
  ) extends TxnLogEntry[Option[V]] {

    override private[stm] lazy val get: Option[V] =
      initial

    override private[stm] def set(value: Option[V]): TxnLogEntry[Option[V]] =
      if (initial != value) {
        TxnLogUpdateVarMapEntry(
          key       = key,
          initial   = initial,
          current   = value,
          txnVarMap = txnVarMap
        )
      } else {
        this
      }

    override private[stm] lazy val commit: F[Unit] =
      Async[F].unit

    override private[stm] lazy val isDirty: F[Boolean] =
      Async[F].pure(false)

    override private[stm] lazy val hasChangedSinceRead: F[Boolean] =
      txnVarMap.get(key).map(_ != initial)

    override private[stm] lazy val lock: F[Option[(TxnVarRuntimeId, Semaphore[F])]] =
      Async[F].pure(None)

    override private[stm] lazy val idFootprint: F[IdFootprint] =
      txnVarMap.getRuntimeId(key).map { rid =>
        IdFootprint(
          readIds    = Set(rid),
          updatedIds = Set()
        )
      }
  }

  private[stm] case class TxnLogUpdateVarMapEntry[K, V](
    key: K,
    initial: Option[V],
    current: Option[V],
    txnVarMap: TxnVarMap[F, K, V]
  ) extends TxnLogEntry[Option[V]] {

    override private[stm] lazy val get: Option[V] =
      current

    override private[stm] def set(value: Option[V]): TxnLogEntry[Option[V]] =
      if (initial != value) {
        TxnLogUpdateVarMapEntry(
          key       = key,
          initial   = initial,
          current   = value,
          txnVarMap = txnVarMap
        )
      } else {
        TxnLogReadOnlyVarMapEntry(
          key       = key,
          initial   = initial,
          txnVarMap = txnVarMap
        )
      }

    override private[stm] lazy val commit: F[Unit] =
      (initial, current) match {
        case (_, Some(cValue)) =>
          txnVarMap.addOrUpdate(key, cValue)
        case (Some(_), None) =>
          txnVarMap.delete(key)
        case _ =>
          Async[F].unit
      }

    override private[stm] lazy val isDirty: F[Boolean] =
      txnVarMap.get(key).map { oValue =>
        initial
          .map(iValue => !oValue.contains(iValue))
          .getOrElse(oValue.isDefined)
      }

    override private[stm] lazy val hasChangedSinceRead: F[Boolean] =
      isDirty

    // The map-lock fallback: a key that does not yet exist has no TxnVar, so this
    // entry locks the MAP's structural commitLock while being logged under the
    // existential id allocated for (map, key). Owner and log key are different
    // entities — the id-space split withLock's ordering turns on.
    override private[stm] lazy val lock: F[Option[(TxnVarRuntimeId, Semaphore[F])]] =
      for {
        oTxnVar <- txnVarMap.getTxnVar(key)
      } yield Some(
        oTxnVar
          .map(txnVar => (txnVar.runtimeId, txnVar.commitLock))
          .getOrElse((txnVarMap.runtimeId, txnVarMap.commitLock))
      )

    override private[stm] lazy val idFootprint: F[IdFootprint] =
      txnVarMap.getRuntimeId(key).map { rid =>
        IdFootprint(
          readIds    = Set(),
          updatedIds = Set(rid)
        )
      }
  }

  sealed private[stm] trait TxnLog { self =>

    private[stm] def getVar[V](txnVar: TxnVar[F, V]): F[(TxnLog, V)]

    // @nowarn on base TxnLog methods: These are no-op defaults that intentionally ignore
    // their parameters (returning Unit cast to V, empty maps, or self unchanged). They exist
    // so that terminal log states (TxnLogRetry, TxnLogError) inherit safe defaults without
    // reimplementing every method. TxnLogValid overrides all of these with real logic.
    // The compiler warns about unused parameters, which is expected and correct here.
    @nowarn
    private[stm] def delay[V](value: F[V]): F[(TxnLog, V)] =
      Async[F].delay(self, ().asInstanceOf[V])

    @nowarn
    private[stm] def pure[V](value: V): F[(TxnLog, V)] =
      Async[F].pure(self, ().asInstanceOf[V])

    @nowarn
    private[stm] def setVar[V](
      newValue: F[V],
      txnVar: TxnVar[F, V]
    ): F[TxnLog] =
      Async[F].pure(self)

    @nowarn
    private[stm] def getVarMapValue[K, V](
      key: F[K],
      txnVarMap: TxnVarMap[F, K, V]
    ): F[(TxnLog, Option[V])] =
      Async[F].pure((self, None))

    @nowarn
    private[stm] def getVarMap[K, V](
      txnVarMap: TxnVarMap[F, K, V]
    ): F[(TxnLog, Map[K, V])] =
      Async[F].pure(self, Map())

    @nowarn
    private[stm] def setVarMap[K, V](
      newMap: F[Map[K, V]],
      txnVarMap: TxnVarMap[F, K, V]
    ): F[TxnLog] =
      Async[F].pure(self)

    @nowarn
    private[stm] def setVarMapValue[K, V](
      key: F[K],
      newValue: F[V],
      txnVarMap: TxnVarMap[F, K, V]
    ): F[TxnLog] =
      Async[F].pure(self)

    @nowarn
    private[stm] def modifyVarMapValue[K, V](
      key: F[K],
      f: V => F[V],
      txnVarMap: TxnVarMap[F, K, V]
    ): F[TxnLog] =
      Async[F].pure(self)

    @nowarn
    private[stm] def deleteVarMapValue[K, V](
      key: F[K],
      txnVarMap: TxnVarMap[F, K, V]
    ): F[TxnLog] =
      Async[F].pure(self)

    @nowarn
    private[stm] def raiseError(ex: Throwable): F[TxnLog] =
      Async[F].pure(self)

    private[stm] def scheduleRetry: F[TxnLog]

    private[stm] def commit: F[Unit]

    private[stm] def idFootprint: F[IdFootprint]

    private[stm] def withLock[A](fa: F[A]): F[A] =
      fa
  }

  private[stm] case class TxnLogValid(log: Map[TxnVarRuntimeId, TxnLogEntry[_]]) extends TxnLog {

    import TxnLogValid._

    private def getLogEntry[V](rId: TxnVarRuntimeId): F[Option[TxnLogEntry[V]]] =
      Async[F].delay(log.get(rId).map(_.asInstanceOf[TxnLogEntry[V]]))

    // THE one answer to: which id does this map key's log entry live under, is there a
    // live TxnVar behind it, and is it already in the log?
    //
    // TWO UNRELATED ID SPACES, and every keyed operation has to choose between them
    // identically. A key that EXISTS has a TxnVar, and its log entry is keyed by that
    // VAR's own runtimeId. A key that does NOT exist yet has no TxnVar, and its entry is
    // keyed by the EXISTENTIAL id allocated for (map, key) — the same id the static
    // analysis walker puts in the footprint, which is why the scheduler can protect a read
    // of a key that is not there. (Which LOCK it takes is a third question again: a new-key
    // entry locks the MAP's structural commitLock. That was H2.)
    //
    // This resolution used to be written out FIVE times, once per keyed operation. Four of
    // the copies recorded a log entry for an absent key and one did not — and that one was
    // a lost wakeup, because the park-time staleness check folds over the LOG, so a read
    // that recorded nothing was invisible to it. The omission survived the entire H1
    // workstream, because it was one line different from four other places nobody was
    // diffing side by side. It is one place now, and an omission here is an omission
    // everywhere, which is the point: it fails loudly instead of in one caller.
    private def resolveMapKey[K, V](
      key: K,
      txnVarMap: TxnVarMap[F, K, V]
    ): F[(TxnVarRuntimeId, Option[TxnVar[F, V]], Option[TxnLogEntry[Option[V]]])] =
      for {
        oTxnVar <- txnVarMap.getTxnVar(key)
        rid <- oTxnVar match {
                 case Some(txnVar) => Async[F].delay(txnVar.runtimeId)
                 case None => txnVarMap.getRuntimeId(key)
               }
        entry <- getLogEntry[Option[V]](rid)
      } yield (rid, oTxnVar, entry)

    // @nowarn: the error branch has no V to hand back — the log is now a
    // TxnLogError and the fold is running only to reach a handleError — so it
    // returns ().asInstanceOf[V], which Scala 2.13 flags as a dubious Unit cast.
    @nowarn
    override private[stm] def delay[V](
      value: F[V]
    ): F[(TxnLog, V)] =
      value
        .map((this.asInstanceOf[TxnLog], _))
        .handleErrorWith { ex =>
          Async[F].delay((TxnLogError(ex), ().asInstanceOf[V]))
        }

    override private[stm] def pure[V](value: V): F[(TxnLog, V)] =
      Async[F].pure((this, value))

    override private[stm] def getVar[V](
      txnVar: TxnVar[F, V]
    ): F[(TxnLog, V)] = {
      def fallbackF(rId: TxnVarRuntimeId): F[(TxnLog, V)] =
        for {
          v <- txnVar.get
          newLog <-
            Async[F].delay(
              log + (rId -> TxnLogReadOnlyVarEntry(v, txnVar))
            )
          result <- Async[F].delay((TxnLogValid(newLog), v))
        } yield result

      for {
        rId      <- Async[F].delay(txnVar.runtimeId)
        logEntry <- Async[F].delay(log.get(rId))
        result <- logEntry match {
                    case Some(entry) =>
                      Async[F].delay((this, entry.get.asInstanceOf[V]))
                    case None =>
                      fallbackF(rId)
                  }
      } yield result
    }

    override private[stm] def setVar[V](
      newValue: F[V],
      txnVar: TxnVar[F, V]
    ): F[TxnLog] =
      (for {
        materializedValue <- newValue
        rId               <- Async[F].delay(txnVar.runtimeId)
        entry             <- Async[F].delay(log.get(rId))
        rawResult <- entry match {
                       case Some(entry) =>
                         Async[F]
                           .delay(
                             log + (txnVar.runtimeId -> entry
                               .asInstanceOf[TxnLogEntry[V]]
                               .set(materializedValue))
                           )
                           .map(TxnLogValid(_))
                       case _ =>
                         for {
                           v   <- txnVar.get
                           rId <- Async[F].delay(txnVar.runtimeId)
                           newValue <- Async[F].delay(
                                         TxnLogUpdateVarEntry(
                                           v,
                                           materializedValue,
                                           txnVar
                                         )
                                       )
                           newLog <- Async[F].delay(log + (rId -> newValue))
                         } yield TxnLogValid(newLog)
                     }
        result <- Async[F].delay(rawResult.asInstanceOf[TxnLog])
      } yield result).handleErrorWith(raiseError)

    // A LOOKUP, not a read: it answers "is there an entry for this key?" and does not
    // create one. getVarMapValue is the read, and it must record.
    private def getVarMapValueEntry[K, V](
      key: K,
      txnVarMap: TxnVarMap[F, K, V]
    ): F[Option[(TxnVarRuntimeId, TxnLogEntry[Option[V]])]] =
      resolveMapKey[K, V](key, txnVarMap).flatMap {
        case (rid, _, Some(entry)) =>
          Async[F].pure(Some((rid, entry)))

        case (rid, Some(txnVar), None) =>
          // The key exists, and this transaction has not touched it yet: materialize a
          // read-only entry from the live value.
          txnVar.get.map { txnVal =>
            Some(
              (
                rid,
                TxnLogReadOnlyVarMapEntry(key, Some(txnVal), txnVarMap)
                  .asInstanceOf[TxnLogEntry[Option[V]]]
              )
            )
          }

        case (_, None, None) =>
          Async[F].pure(None)
      }

    override private[stm] def getVarMap[K, V](
      txnVarMap: TxnVarMap[F, K, V]
    ): F[(TxnLog, Map[K, V])] = {
      val individualEntries: F[Map[TxnVarRuntimeId, TxnLogEntry[_]]] =
        for {
          rId <- Async[F].delay(txnVarMap.runtimeId)
          preTxnEntries <-
            Async[F].ifM(Async[F].delay(!log.contains(rId)))(
              for {
                oldMap <- txnVarMap.get
                preTxn <-
                  oldMap.keySet.toList.parTraverse { ks =>
                    getVarMapValueEntry(ks, txnVarMap)
                  }
              } yield preTxn,
              Async[F].pure(
                List[Option[(TxnVarRuntimeId, TxnLogEntry[Option[V]])]]()
              )
            )
          currentEntries <- extractMap(txnVarMap, log)
          reads <- currentEntries.keySet.toList.parTraverse { ks =>
                     getVarMapValueEntry(ks, txnVarMap)
                   }
        } yield (preTxnEntries ::: reads).flatten.toMap

      for {
        rId   <- Async[F].delay(txnVarMap.runtimeId)
        entry <- getLogEntry[Option[V]](rId)
        result <- entry match {
                    case Some(_) =>
                      for {
                        entries   <- individualEntries
                        newLogRaw <- Async[F].delay(log ++ entries)
                        newMap    <- extractMap(txnVarMap, newLogRaw)
                        newLog    <- Async[F].delay(TxnLogValid(newLogRaw))
                      } yield (newLog, newMap)
                    case None =>
                      for {
                        v       <- txnVarMap.get
                        entries <- individualEntries
                        newEntry <- Async[F].delay(
                                      rId -> TxnLogReadOnlyVarMapStructureEntry(
                                        v,
                                        txnVarMap
                                      )
                                    )
                        newLogRaw <- Async[F].delay((log ++ entries) + newEntry)
                        newMap    <- extractMap(txnVarMap, newLogRaw)
                        newLog    <- Async[F].delay(TxnLogValid(newLogRaw))
                      } yield (newLog, newMap)
                  }
      } yield result
    }

    override private[stm] def getVarMapValue[K, V](
      key: F[K],
      txnVarMap: TxnVarMap[F, K, V]
    ): F[(TxnLog, Option[V])] = {
      val result = for {
        materializedKey <- key
        resolved        <- resolveMapKey[K, V](materializedKey, txnVarMap)
        (rid, oTxnVar, oEntry) = resolved
        rawResult <- oEntry match {
                       // Already in this transaction's log: read it back, record nothing new.
                       case Some(entry) =>
                         Async[F].delay[(TxnLog, Option[V])]((this, entry.get))

                       // SPEC: NoLostWakeup — THE READ IS RECORDED WHETHER OR NOT THE KEY
                       // EXISTS. The two cases differ only in the initial value: a live key
                       // reads as its value, an absent key reads as None. Both are reads and
                       // both belong in the log.
                       //
                       // They used to be two separately-written branches, and only one of
                       // them recorded. The absent one did not, which made
                       // anyReadChangedSinceRead blind to it — and that fold IS the second of
                       // H1's two park guards, the one that catches a conflictor which
                       // already committed and left. A waitFor on an absent key therefore
                       // parked forever. specs/scheduler/SchedulerAbsentKey.cfg pins it, and
                       // TxnLogEntrySpec asserts the entry is here.
                       case None =>
                         for {
                           initial <- oTxnVar match {
                                        case Some(txnVar) => txnVar.get.map(v => Some(v): Option[V])
                                        case None => Async[F].pure(Option.empty[V])
                                      }
                           newLog <- Async[F].delay(
                                       TxnLogValid(
                                         log + (rid -> TxnLogReadOnlyVarMapEntry(
                                           materializedKey,
                                           initial,
                                           txnVarMap
                                         ))
                                       )
                                     )
                         } yield (newLog: TxnLog, initial)
                     }
      } yield rawResult

      result.handleErrorWith { ex =>
        raiseError(ex).map((_, None))
      }
    }

    // Computes the log entry a write WOULD need, or None if it needs none. Used by
    // setVarMap to diff a whole-map replacement into per-key entries.
    //
    // ONE RULE, and the two id spaces do not change it: if the new value differs from what
    // was there, record an update; if it does not, nothing changed and no entry is needed.
    // A live key's "what was there" is its value; an ABSENT key's is None. So inserting
    // into an absent key records an entry (None -> Some), and "deleting" an absent key
    // records nothing (None -> None) because nothing changed.
    //
    // That last case reads like a silent no-op next to removeTxnVarMapValue, which FAILS
    // the transaction on an absent key. They are not in conflict: this is setVarMap's
    // internal diff, where a key absent from both the old and new map is simply not a
    // change, and `remove` is a user asking to delete something that is not there. The two
    // used to be written far apart and it was easy to read them as contradicting.
    private def writeVarMapValueEntry[K, V](
      key: K,
      newOpt: Option[V],
      txnVarMap: TxnVarMap[F, K, V]
    ): F[Option[(TxnVarRuntimeId, TxnLogEntry[Option[V]])]] =
      resolveMapKey[K, V](key, txnVarMap).flatMap {
        case (rid, _, Some(entry)) =>
          Async[F].delay(Some((rid, entry.set(newOpt))))

        case (rid, oTxnVar, None) =>
          for {
            initial <- oTxnVar match {
                         case Some(txnVar) => txnVar.get.map(v => Some(v): Option[V])
                         case None => Async[F].pure(Option.empty[V])
                       }
          } yield
            if (initial == newOpt) {
              None
            } else {
              Some(
                (
                  rid,
                  TxnLogUpdateVarMapEntry(key, initial, newOpt, txnVarMap)
                    .asInstanceOf[TxnLogEntry[Option[V]]]
                )
              )
            }
      }

    override private[stm] def setVarMap[K, V](
      newMap: F[Map[K, V]],
      txnVarMap: TxnVarMap[F, K, V]
    ): F[TxnLog] = {
      def individualEntries(
        materializedNewMap: Map[K, V]
      ): F[Map[TxnVarRuntimeId, TxnLogEntry[Option[V]]]] = for {
        currentMap <- extractMap(txnVarMap, log)
        deletions <-
          (currentMap.keySet -- materializedNewMap.keySet).toList.parTraverse { ks =>
            writeVarMapValueEntry(ks, None, txnVarMap)
          }
        updates <- materializedNewMap.toList.parTraverse { kv =>
                     writeVarMapValueEntry(kv._1, Some(kv._2), txnVarMap)
                   }
      } yield (deletions ::: updates).flatten.toMap

      val result = for {
        materializedNewMap <- newMap
        rId                <- Async[F].delay(txnVarMap.runtimeId)
        entry              <- getLogEntry[Option[V]](rId)
        innerResult <- entry match {
                         case Some(entry) =>
                           for {
                             entries <- individualEntries(materializedNewMap)
                             newLog  <- Async[F].delay(log ++ entries)
                             newEntry <-
                               Async[F].delay(
                                 rId -> entry
                                   .asInstanceOf[TxnLogEntry[Map[K, V]]]
                                   .set(materializedNewMap)
                               )
                             i2Result <-
                               Async[F].delay(TxnLogValid(newLog + newEntry))
                           } yield i2Result
                         case None =>
                           for {
                             txnVal  <- txnVarMap.get
                             entries <- individualEntries(materializedNewMap)
                             newLog  <- Async[F].delay(log ++ entries)
                             entry <- Async[F].delay(
                                        TxnLogUpdateVarMapStructureEntry(
                                          txnVal,
                                          materializedNewMap,
                                          txnVarMap
                                        )
                                      )
                             newEntry <-
                               Async[F].delay(
                                 rId -> entry
                                   .asInstanceOf[TxnLogEntry[Map[K, V]]]
                               )
                             i2Result <-
                               Async[F].delay(TxnLogValid(newLog + newEntry))
                           } yield i2Result
                       }
      } yield innerResult.asInstanceOf[TxnLog]

      result.handleErrorWith(raiseError)
    }

    // The user-facing keyed write: map.set(k, v) and map.remove(k).
    //
    // Same rule as writeVarMapValueEntry -- if the new value differs from the initial,
    // record an update -- with ONE deliberate difference at the bottom: removing a key that
    // is not there FAILS the transaction, because the user asked to delete something that
    // does not exist and STM.scala says so. writeVarMapValueEntry returns None for the same
    // input instead, and that is not a contradiction: it serves setVarMap's internal diff,
    // where a key absent from both the old and the new map is simply not a change. The two
    // used to be written far apart, and it was easy to read them as opposite policies.
    //
    // NOTE ON NULL. `initial` is built with Some(...) here and Option(...) in the read
    // paths, and for a null value those disagree. It is not observable today, because a null
    // map value is invisible on the way back out: Option(null) is None, so get(key) reports
    // the key as ABSENT and extractMap drops it from a whole-map read. Storing null in a
    // TxnVarMap silently loses the key. That is a real defect and it is tracked separately;
    // preserving behaviour exactly is the whole point of a collapse, so it is not fixed here.
    private def writeVarMapValue[K, V](
      key: F[K],
      newOpt: Option[F[V]],
      txnVarMap: TxnVarMap[F, K, V]
    ): F[TxnLog] = {
      val resultSpec = for {
        materializedKey    <- key
        materializedNewOpt <- newOpt.traverse(identity)
        resolved           <- resolveMapKey[K, V](materializedKey, txnVarMap)
        (rid, oTxnVar, oEntry) = resolved
        result <- oEntry match {
                    case Some(entry) =>
                      Async[F].delay(
                        TxnLogValid(log + (rid -> entry.set(materializedNewOpt))): TxnLog
                      )

                    case None =>
                      oTxnVar match {
                        case Some(txnVar) =>
                          for {
                            txnVal <- txnVar.get
                            out <- Async[F].ifM(
                                     Async[F].delay(Some(txnVal) != materializedNewOpt)
                                   )(
                                     Async[F].delay(
                                       TxnLogValid(
                                         log + (rid -> TxnLogUpdateVarMapEntry(
                                           materializedKey,
                                           Some(txnVal),
                                           materializedNewOpt,
                                           txnVarMap
                                         ))
                                       ): TxnLog
                                     ),
                                     Async[F].pure(this: TxnLog)
                                   )
                          } yield out

                        case None =>
                          materializedNewOpt match {
                            // Insert: the key does not exist, and we are creating it.
                            case Some(_) =>
                              Async[F].delay(
                                TxnLogValid(
                                  log + (rid -> TxnLogUpdateVarMapEntry(
                                    materializedKey,
                                    None,
                                    materializedNewOpt,
                                    txnVarMap
                                  ))
                                ): TxnLog
                              )

                            // Remove of a key that is not there. Fails the transaction.
                            case None =>
                              Async[F].delay(TxnLogError {
                                new RuntimeException(
                                  s"Tried to remove non-existent key $materializedKey in transactional map"
                                )
                              }: TxnLog)
                          }
                      }
                  }
      } yield result

      resultSpec.handleErrorWith(raiseError)
    }

    override private[stm] def setVarMapValue[K, V](
      key: F[K],
      newValue: F[V],
      txnVarMap: TxnVarMap[F, K, V]
    ): F[TxnLog] =
      writeVarMapValue(key, Some(newValue), txnVarMap)

    // Read-modify-write on one key. Fails if the key is not there to modify -- including
    // when it was deleted earlier in THIS transaction, which the log records as an entry
    // whose value is None. That is why the "no key" test is on the entry's VALUE and not on
    // the entry's existence.
    override private[stm] def modifyVarMapValue[K, V](
      key: F[K],
      f: V => F[V],
      txnVarMap: TxnVarMap[F, K, V]
    ): F[TxnLog] = {
      val resultSpec = for {
        materializedKey <- key
        resolved        <- resolveMapKey[K, V](materializedKey, txnVarMap)
        (rid, oTxnVar, oEntry) = resolved
        result <- oEntry match {
                    // Already in the log. Modify whatever it currently holds -- unless it
                    // holds nothing, i.e. this transaction already deleted the key.
                    case Some(entry) =>
                      entry.get match {
                        case Some(v) =>
                          f(v).map { evaluation =>
                            TxnLogValid(log + (rid -> entry.set(Some(evaluation)))): TxnLog
                          }
                        case None =>
                          Async[F].delay(
                            TxnLogError(
                              new RuntimeException(
                                s"Key $materializedKey not found for modification"
                              )
                            ): TxnLog
                          )
                      }

                    // Not in the log yet. If the key exists, read it live and modify it;
                    // if it does not, there is nothing to modify.
                    case None =>
                      oTxnVar match {
                        case Some(txnVar) =>
                          for {
                            v          <- txnVar.get
                            evaluation <- f(v)
                          } yield TxnLogValid(
                            log + (rid -> TxnLogUpdateVarMapEntry(
                              materializedKey,
                              Some(v),
                              Some(evaluation),
                              txnVarMap
                            ))
                          ): TxnLog

                        case None =>
                          Async[F].delay(
                            TxnLogError(
                              new RuntimeException(
                                s"Key $materializedKey not found for modification"
                              )
                            ): TxnLog
                          )
                      }
                  }
      } yield result

      resultSpec.handleErrorWith(raiseError)
    }

    override private[stm] def deleteVarMapValue[K, V](
      key: F[K],
      txnVarMap: TxnVarMap[F, K, V]
    ): F[TxnLog] =
      writeVarMapValue[K, V](key, None, txnVarMap)

    override private[stm] def raiseError(ex: Throwable): F[TxnLog] =
      Async[F].delay(TxnLogError(ex))

    // We throw here to short-circuit the Free compiler recursion.
    // There is no point in processing anything else beyond the retry,
    // which could lead to impossible casts being attempted, which would
    // just throw anyway.
    // Note that we do not throw on `raiseError` as the Txn may contain
    // a handleError entry; i.e. we can not simply short-circuit the
    // recursion.
    override private[stm] def scheduleRetry: F[TxnLog] =
      throw TxnRetryException(this)

    // The park path's read-inclusive staleness check (H1 fix): TRUE iff any
    // entry — read-only entries included — no longer matches the live value
    // it was built from. Contrast isDirty, which validates the write set
    // only. Same walk, no short-circuit machinery: see isDirty above for why
    // that machinery would not pay for itself here either.
    private[stm] lazy val anyReadChangedSinceRead: F[Boolean] =
      log.values.toList
        .parTraverse(_.hasChangedSinceRead)
        .map(_.exists(identity))

    // foldLeft from empty, NOT reduce: an EMPTY log is perfectly ordinary — a
    // transaction of pure/delay steps touches nothing, and reading an absent map
    // key records no entry at all — and `reduce` throws on an empty list. The
    // bug was latent while this was only forced on the dirty path (where the log
    // is non-empty by construction); the H6 coverage check forces it on every
    // commit, which is what brought it out.
    // `traverse`, NOT `parTraverse`. Every entry's idFootprint is trivial — a
    // cached runtimeId for a var, a registry lookup (one-time allocation on
    // first touch) for a map entry — so there is nothing to overlap and a fiber
    // per entry is pure overhead. That did not matter while this was forced only
    // on the dirty path; the H6 coverage check forces it on EVERY commit, and a
    // whole-map read expands into a log entry per key, so the fiber count
    // tracked the map size.
    //
    // The switch was measured on dedicated hardware and it is worth real
    // percentage points on the whole-map-read workload; benchmarks/README.md
    // carries the figures, and its header explains why the hardware matters —
    // the first attempt at this measurement ran on a thermally throttling laptop
    // and reported the exact opposite. (Those figures predate the removal of the
    // per-entry UUID/MD5 hash from this fold, which only lowers the per-entry
    // cost further and strengthens the same conclusion.)
    override private[stm] lazy val idFootprint: F[IdFootprint] =
      log.values.toList
        .traverse { entry =>
          entry.idFootprint
        }
        .map(_.foldLeft(IdFootprint.empty)(_ mergeWith _))

    // SPEC: NoWaitsForCycle — the H2 fix. Acquire the write set's commitLocks
    // in ascending order of THE ID OF THE ENTITY THAT OWNS EACH LOCK. Because
    // owner -> lock is injective, one ascending order is a single global total
    // order over locks, and a set of transactions that all respect it cannot
    // form a circular wait (the classic resource-ordering argument).
    //
    // This used to sort the LOG ENTRIES by their log key, which is NOT the same
    // thing and did not order the locks at all: a map entry for a key that does
    // not yet exist is logged under the existential id allocated for (map, key)
    // but locks the MAP's structural commitLock. Sorting by the log key
    // therefore ordered acquisitions by the KEYS' ids (hashes, at the time)
    // while the locks acquired belonged to the MAPS — two unrelated id spaces.
    // Two transactions inserting fresh keys into two maps have COMPATIBLE
    // footprints, so the scheduler runs them concurrently by design; both then
    // hold {M1.lock, M2.lock}, and key-id order could invert their acquisition
    // order into a deadlock. specs/commit/CommitH2.cfg pins it.
    //
    // .distinct dedupes on the (owner id, Semaphore) pair, so the two entries
    // of a double insert into ONE map — which alias to that map's single lock —
    // collapse to one acquisition rather than self-deadlocking a 1-permit
    // Semaphore. Owner ids are unique by construction (every id comes from the
    // one global allocator), so equal ids imply the same entity and the sort
    // below can never tie between distinct locks; comparing the Semaphore by
    // reference in the dedup is belt and braces, not a collision hedge.
    override private[stm] def withLock[A](fa: F[A]): F[A] =
      for {
        locks <- log.values.toList.traverse(_.lock)
        result <-
          locks.flatten.distinct
            .sortBy(_._1.value)
            .map(_._2)
            .foldLeft(Resource.eval(Async[F].unit))((i, j) => i >> j.permit)
            .use(_ => fa)
      } yield result

    override private[stm] lazy val commit: F[Unit] =
      log.values.toList.parTraverse(_.commit).void
  }

  private[stm] object TxnLogValid {
    private[stm] val empty: TxnLogValid = TxnLogValid(Map())

    private def extractMap[K, V](
      txnVarMap: TxnVarMap[F, K, V],
      log: Map[TxnVarRuntimeId, TxnLogEntry[_]]
    ): F[Map[K, V]] = {
      val logEntriesF =
        Async[F].delay(
          log.values
            .flatMap {
              case TxnLogReadOnlyVarMapEntry(key, Some(initial), entryMap) if txnVarMap.id == entryMap.id =>
                Some(key -> initial)
              case TxnLogUpdateVarMapEntry(key, _, Some(current), entryMap) if txnVarMap.id == entryMap.id =>
                Some(key -> current)
              case _ =>
                None
            }
            .toMap
            .asInstanceOf[Map[K, V]]
        )

      Async[F].ifM(Async[F].delay(log.contains(txnVarMap.runtimeId)))(
        logEntriesF,
        for {
          logEntries   <- logEntriesF
          txnVarMapGet <- txnVarMap.get
        } yield txnVarMapGet ++ logEntries
      )
    }
  }

  private[stm] case class TxnLogRetry(validLog: TxnLogValid) extends TxnLog {

    override private[stm] def getVar[V](
      txnVar: TxnVar[F, V]
    ): F[(TxnLog, V)] =
      validLog.log.get(txnVar.runtimeId) match {
        case Some(entry) =>
          Async[F].delay((this, entry.get.asInstanceOf[V]))
        case None =>
          for {
            v <- txnVar.get
          } yield (this, v)
      }

    override private[stm] lazy val scheduleRetry =
      Async[F].pure(this)

    override private[stm] lazy val commit: F[Unit] =
      Async[F].unit

    override private[stm] lazy val idFootprint: F[IdFootprint] =
      Async[F].pure(IdFootprint.empty)
  }

  private[stm] case class TxnLogError(ex: Throwable) extends TxnLog {

    override private[stm] def getVar[V](
      txnVar: TxnVar[F, V]
    ): F[(TxnLog, V)] =
      txnVar.get.map(v => (this, v))

    override private[stm] lazy val scheduleRetry =
      Async[F].pure(this)

    override private[stm] lazy val commit: F[Unit] =
      Async[F].unit

    override private[stm] lazy val idFootprint: F[IdFootprint] =
      Async[F].pure(IdFootprint.empty)
  }

  private[stm] case class TxnRetryException(validLog: TxnLogValid) extends RuntimeException

}
