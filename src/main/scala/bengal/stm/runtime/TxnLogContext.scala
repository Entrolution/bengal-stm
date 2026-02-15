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

import bengal.stm.model._
import bengal.stm.model.runtime._

import cats.effect.implicits._
import cats.effect.Deferred
import cats.effect.kernel.{ Async, Resource }
import cats.effect.std.Semaphore
import cats.syntax.all._

import scala.annotation.nowarn

private[stm] trait TxnLogContext[F[_]] {
  this: AsyncImplicits[F] =>

  sealed private[stm] trait TxnLogEntry[V] {
    private[stm] def get: V
    private[stm] def set(value: V): TxnLogEntry[V]
    private[stm] def commit: F[Unit]
    private[stm] def isDirty: F[Boolean]
    private[stm] def lock: F[Option[Semaphore[F]]]
    private[stm] def idFootprint: F[IdFootprint]

  }

  // RO entry is not necessary for pure transactions.
  // However, to make the library interface more
  // predictable in the face of side-effects, RO
  // entries are created
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

    override private[stm] lazy val lock: F[Option[Semaphore[F]]] =
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

    override private[stm] lazy val lock: F[Option[Semaphore[F]]] =
      Async[F].delay(Some(txnVar.commitLock))

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

    override private[stm] lazy val lock: F[Option[Semaphore[F]]] =
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

    override private[stm] lazy val lock: F[Option[Semaphore[F]]] =
      Async[F].delay(Some(txnVarMap.commitLock))

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

    override private[stm] lazy val lock: F[Option[Semaphore[F]]] =
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

    override private[stm] lazy val lock: F[Option[Semaphore[F]]] =
      for {
        oTxnVar <- txnVarMap.getTxnVar(key)
      } yield oTxnVar.map(_.commitLock)

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

    private[stm] def isDirty: F[Boolean]

    private[stm] def commit: F[Unit]

    private[stm] def idFootprint: F[IdFootprint]

    private[stm] def withLock[A](fa: F[A]): F[A] =
      fa
  }

  private[stm] case class TxnLogValid(log: Map[TxnVarRuntimeId, TxnLogEntry[_]]) extends TxnLog {

    import TxnLogValid._

    @nowarn // Type erasure: V in (TxnLog, V) cannot be verified at runtime
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

    private def getVarMapValueEntry[K, V](
      key: K,
      txnVarMap: TxnVarMap[F, K, V]
    ): F[Option[(TxnVarRuntimeId, TxnLogEntry[Option[V]])]] =
      for {
        oTxnVar <- txnVarMap.getTxnVar(key)
        result <- oTxnVar match {
                    case Some(txnVar) =>
                      for {
                        rId <- Async[F].delay(txnVar.runtimeId)
                        entry <-
                          Async[F].delay(
                            log
                              .get(rId)
                              .map(_.asInstanceOf[TxnLogEntry[Option[V]]])
                          )
                        innerResult <- entry match {
                                         case Some(res) =>
                                           Async[F].pure(res)
                                         case None =>
                                           for {
                                             txnVal <- txnVar.get
                                             entry <-
                                               Async[F].delay(
                                                 TxnLogReadOnlyVarMapEntry(
                                                   key,
                                                   Option(txnVal),
                                                   txnVarMap
                                                 ).asInstanceOf[TxnLogEntry[
                                                   Option[V]
                                                 ]]
                                               )
                                           } yield entry
                                       }
                      } yield Some((rId, innerResult))
                    case None =>
                      for {
                        rid <- txnVarMap.getRuntimeId(key)
                        innerResult <-
                          Async[F].delay(log.get(rid).map(e => (rid, e.asInstanceOf[TxnLogEntry[Option[V]]])))
                      } yield innerResult
                  }
      } yield result

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
        rId <- Async[F].delay(txnVarMap.runtimeId)
        entry <- Async[F].delay(
                   log.get(rId).map(_.asInstanceOf[TxnLogEntry[Option[V]]])
                 )
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
        oTxnVar         <- txnVarMap.getTxnVar(materializedKey)
        rawResult <- oTxnVar match {
                       case Some(txnVar) =>
                         for {
                           rId <- Async[F].delay(txnVar.runtimeId)
                           entry <-
                             Async[F].delay(
                               log
                                 .get(rId)
                                 .map(_.asInstanceOf[TxnLogEntry[Option[V]]])
                             )
                           innerResult <- entry match {
                                            case Some(entry) =>
                                              Async[F].delay(
                                                (this, entry.get)
                                              ) // Noop
                                            case None =>
                                              for {
                                                txnVal <- txnVar.get
                                                newEntry <-
                                                  Async[F].delay(
                                                    rId -> TxnLogReadOnlyVarMapEntry(
                                                      materializedKey,
                                                      Option(txnVal),
                                                      txnVarMap
                                                    )
                                                  )
                                                newLogRaw <-
                                                  Async[F].delay(log + newEntry)
                                                newLog <-
                                                  Async[F].delay(
                                                    TxnLogValid(newLogRaw)
                                                  )
                                              } yield (newLog, Option(txnVal))
                                          }
                         } yield innerResult
                       case None =>
                         for {
                           rid      <- txnVarMap.getRuntimeId(materializedKey)
                           rawEntry <- Async[F].delay(log.get(rid).map(_.asInstanceOf[TxnLogEntry[Option[V]]]))
                           innerResult <- rawEntry match {
                                            case Some(entry) =>
                                              Async[F].delay((this, entry.get))
                                            case _ =>
                                              Async[F].delay[
                                                (TxnLogValid, Option[V])
                                              ]((this, None))
                                          }
                         } yield innerResult
                     }
      } yield (rawResult._1.asInstanceOf[TxnLog], rawResult._2)

      result.handleErrorWith { ex =>
        raiseError(ex).map((_, None))
      }
    }

    private def setVarMapValueEntry[K, V](
      key: K,
      newValue: V,
      txnVarMap: TxnVarMap[F, K, V]
    ): F[Option[(TxnVarRuntimeId, TxnLogEntry[Option[V]])]] =
      for {
        oTxnVarMap <- txnVarMap.getTxnVar(key)
        result <- oTxnVarMap match {
                    case Some(txnVar) =>
                      for {
                        rId <- Async[F].delay(txnVar.runtimeId)
                        entry <-
                          Async[F].delay(
                            log
                              .get(rId)
                              .map(_.asInstanceOf[TxnLogEntry[Option[V]]])
                          )
                        innerResult <- entry match {
                                         case Some(entry) =>
                                           Async[F].delay(
                                             Option(
                                               (rId, entry.set(Some(newValue)))
                                             )
                                           )
                                         case None =>
                                           for {
                                             txnVal <- txnVar.get
                                             i2Result <-
                                               Async[F].ifM(
                                                 Async[F].delay(
                                                   txnVal != newValue
                                                 )
                                               )(
                                                 Async[F].delay(
                                                   Option(
                                                     (
                                                       rId,
                                                       TxnLogUpdateVarMapEntry(
                                                         key,
                                                         Option(txnVal),
                                                         Option(newValue),
                                                         txnVarMap
                                                       ).asInstanceOf[
                                                         TxnLogEntry[Option[V]]
                                                       ]
                                                     )
                                                   )
                                                 ),
                                                 Async[F].pure[Option[
                                                   (
                                                     TxnVarRuntimeId,
                                                     TxnLogEntry[Option[V]]
                                                   )
                                                 ]](None)
                                               )
                                           } yield i2Result
                                       }
                      } yield innerResult
                    case None =>
                      // The txnVar may have been set to be created in this transaction
                      for {
                        rid      <- txnVarMap.getRuntimeId(key)
                        rawEntry <- Async[F].delay(log.get(rid).map(_.asInstanceOf[TxnLogEntry[Option[V]]]))
                        innerResult <- rawEntry match {
                                         case Some(entry) =>
                                           Async[F].delay(
                                             Option(
                                               (rid, entry.set(Some(newValue)))
                                             )
                                           )
                                         case _ =>
                                           Async[F].delay(
                                             Option(
                                               (
                                                 rid,
                                                 TxnLogUpdateVarMapEntry(
                                                   key,
                                                   None,
                                                   Option(newValue),
                                                   txnVarMap
                                                 ).asInstanceOf[TxnLogEntry[
                                                   Option[V]
                                                 ]]
                                               )
                                             )
                                           )
                                       }
                      } yield innerResult
                  }
      } yield result

    private def deleteVarMapValueEntry[K, V](
      key: K,
      txnVarMap: TxnVarMap[F, K, V]
    ): F[Option[(TxnVarRuntimeId, TxnLogEntry[Option[V]])]] =
      for {
        oTxnVar <- txnVarMap.getTxnVar(key)
        result <- oTxnVar match {
                    case Some(txnVar) =>
                      for {
                        rId <- Async[F].delay(txnVar.runtimeId)
                        entry <-
                          Async[F].delay(
                            log
                              .get(rId)
                              .map(_.asInstanceOf[TxnLogEntry[Option[V]]])
                          )
                        innerResult <- entry match {
                                         case Some(entry) =>
                                           Async[F].delay(
                                             Option((rId, entry.set(None)))
                                           )
                                         case None =>
                                           for {
                                             txnVal <- txnVar.get
                                             i2Result <-
                                               Async[F].delay(
                                                 Option(
                                                   (
                                                     rId,
                                                     TxnLogUpdateVarMapEntry(
                                                       key,
                                                       Option(txnVal),
                                                       None,
                                                       txnVarMap
                                                     ).asInstanceOf[TxnLogEntry[
                                                       Option[V]
                                                     ]]
                                                   )
                                                 )
                                               )
                                           } yield i2Result
                                       }
                      } yield innerResult
                    case None =>
                      for {
                        rid      <- txnVarMap.getRuntimeId(key)
                        rawEntry <- Async[F].delay(log.get(rid).map(_.asInstanceOf[TxnLogEntry[Option[V]]]))
                        innerResult <- rawEntry match {
                                         case Some(entry) =>
                                           Async[F].delay(
                                             Option((rid, entry.set(None)))
                                           )
                                         case _ =>
                                           Async[F].delay(
                                             None.asInstanceOf[Option[
                                               (
                                                 TxnVarRuntimeId,
                                                 TxnLogEntry[Option[V]]
                                               )
                                             ]]
                                           )
                                       }
                      } yield innerResult
                  }
      } yield result

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
            deleteVarMapValueEntry(ks, txnVarMap)
          }
        updates <- materializedNewMap.toList.parTraverse { kv =>
                     setVarMapValueEntry(kv._1, kv._2, txnVarMap)
                   }
      } yield (deletions ::: updates).flatten.toMap

      val result = for {
        materializedNewMap <- newMap
        rId                <- Async[F].delay(txnVarMap.runtimeId)
        entry <- Async[F].delay(
                   log
                     .get(rId)
                     .map(_.asInstanceOf[TxnLogEntry[Option[V]]])
                 )
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

    override private[stm] def setVarMapValue[K, V](
      key: F[K],
      newValue: F[V],
      txnVarMap: TxnVarMap[F, K, V]
    ): F[TxnLog] = {
      val resultSpec = for {
        materializedKey      <- key
        materializedNewValue <- newValue
        oEntry               <- txnVarMap.getTxnVar(materializedKey)
        rId                  <- Async[F].delay(txnVarMap.runtimeId)
        result <- oEntry match {
                    case Some(txnVar) =>
                      for {
                        entry <-
                          Async[F].delay(
                            log
                              .get(rId)
                              .map(_.asInstanceOf[TxnLogEntry[Option[V]]])
                          )
                        innerResult <- entry match {
                                         case Some(entry) =>
                                           for {
                                             newEntry <-
                                               Async[F].delay(
                                                 rId -> entry.set(
                                                   Some(materializedNewValue)
                                                 )
                                               )
                                             newLog <-
                                               Async[F].delay(log + newEntry)
                                           } yield TxnLogValid(newLog)
                                         case None =>
                                           for {
                                             txnVal <- txnVar.get
                                             i2Result <-
                                               Async[F].ifM(
                                                 Async[F].delay(
                                                   txnVal != materializedNewValue
                                                 )
                                               )(
                                                 for {
                                                   newEntry <-
                                                     Async[F].delay(
                                                       rId -> TxnLogUpdateVarMapEntry(
                                                         materializedKey,
                                                         Some(txnVal),
                                                         Some(
                                                           materializedNewValue
                                                         ),
                                                         txnVarMap
                                                       )
                                                     )
                                                   newLog <- Async[F].delay(
                                                               log + newEntry
                                                             )
                                                 } yield TxnLogValid(newLog),
                                                 Async[F].pure(this)
                                               )
                                           } yield i2Result
                                       }
                      } yield innerResult.asInstanceOf[TxnLog]
                    case None =>
                      // The txnVar may have been set to be created in this transaction
                      for {
                        rid      <- txnVarMap.getRuntimeId(materializedKey)
                        rawEntry <- Async[F].delay(log.get(rid).map(_.asInstanceOf[TxnLogEntry[Option[V]]]))
                        innerResult <- rawEntry match {
                                         case Some(entry) =>
                                           for {
                                             newEntry <-
                                               Async[F].delay(
                                                 rid -> entry.set(
                                                   Some(materializedNewValue)
                                                 )
                                               )
                                             newLog <-
                                               Async[F].delay(log + newEntry)
                                           } yield TxnLogValid(newLog)
                                         case _ =>
                                           for {
                                             newEntry <-
                                               Async[F].delay(
                                                 rid -> TxnLogUpdateVarMapEntry(
                                                   materializedKey,
                                                   None,
                                                   Some(materializedNewValue),
                                                   txnVarMap
                                                 )
                                               )
                                             newLog <-
                                               Async[F].delay(log + newEntry)
                                           } yield TxnLogValid(newLog)
                                       }
                      } yield innerResult.asInstanceOf[TxnLog]
                  }
      } yield result

      resultSpec.handleErrorWith(raiseError)
    }

    override private[stm] def modifyVarMapValue[K, V](
      key: F[K],
      f: V => F[V],
      txnVarMap: TxnVarMap[F, K, V]
    ): F[TxnLog] = {
      val resultSpec = for {
        materializedKey <- key
        rId             <- Async[F].delay(txnVarMap.runtimeId)
        mapEntry        <- txnVarMap.getTxnVar(materializedKey)
        result <- mapEntry match {
                    case Some(txnVar) =>
                      for {
                        entry <-
                          Async[F].delay(
                            log
                              .get(rId)
                              .map(_.asInstanceOf[TxnLogEntry[Option[V]]])
                          )
                        innerResult <- entry match {
                                         case Some(entry) =>
                                           for {
                                             oEntry <- Async[F].delay(entry.get)
                                             i2Result <- oEntry match {
                                                           case Some(v) =>
                                                             for {
                                                               evaluation <- f(
                                                                               v
                                                                             )
                                                               newEntry <-
                                                                 Async[F].delay(
                                                                   rId -> entry
                                                                     .set(
                                                                       Some(
                                                                         evaluation
                                                                       )
                                                                     )
                                                                 )
                                                               newLog <-
                                                                 Async[F].delay(
                                                                   log + newEntry
                                                                 )
                                                             } yield TxnLogValid(
                                                               newLog
                                                             )
                                                           case None =>
                                                             Async[F].delay {
                                                               TxnLogError(
                                                                 new RuntimeException(
                                                                   s"Key $materializedKey not found for modification"
                                                                 )
                                                               )
                                                             }
                                                         }
                                           } yield i2Result.asInstanceOf[TxnLog]
                                         case None =>
                                           for {
                                             v <- txnVar.get
                                             txnRId <-
                                               Async[F].delay(txnVar.runtimeId)
                                             evaluation <- f(v)
                                             newEntry <-
                                               Async[F].delay(
                                                 txnRId -> TxnLogUpdateVarMapEntry(
                                                   materializedKey,
                                                   Some(v),
                                                   Some(evaluation),
                                                   txnVarMap
                                                 )
                                               )
                                             newLog <-
                                               Async[F].delay(log + newEntry)
                                           } yield TxnLogValid(newLog)
                                             .asInstanceOf[TxnLog]
                                       }
                      } yield innerResult
                    case None =>
                      for {
                        rid <- txnVarMap.getRuntimeId(materializedKey)
                        rawEntry <- Async[F].delay(
                                      log
                                        .get(rid)
                                        .map(_.asInstanceOf[TxnLogEntry[Option[V]]])
                                    )
                        innerResult <- rawEntry match {
                                         case Some(entry) =>
                                           for {
                                             oEntry <- Async[F].delay(entry.get)
                                             i2Result <- oEntry match {
                                                           case Some(v) =>
                                                             for {
                                                               evaluation <- f(v)
                                                               newEntry <-
                                                                 Async[F].delay(rid -> entry.set(Some(evaluation)))
                                                               newLog <-
                                                                 Async[F].delay(
                                                                   log + newEntry
                                                                 )
                                                             } yield TxnLogValid(
                                                               newLog
                                                             )
                                                           case None =>
                                                             Async[F].delay {
                                                               TxnLogError(
                                                                 new RuntimeException(
                                                                   s"Key $materializedKey not found for modification"
                                                                 )
                                                               )
                                                             }
                                                         }
                                           } yield i2Result.asInstanceOf[TxnLog]
                                         case _ =>
                                           Async[F].delay {
                                             TxnLogError(
                                               new RuntimeException(
                                                 s"Key $materializedKey not found for modification"
                                               )
                                             ).asInstanceOf[TxnLog]
                                           }
                                       }
                      } yield innerResult
                  }
      } yield result

      resultSpec.handleErrorWith(raiseError)
    }

    override private[stm] def deleteVarMapValue[K, V](
      key: F[K],
      txnVarMap: TxnVarMap[F, K, V]
    ): F[TxnLog] = {
      val resultSpec = for {
        materializedKey <- key
        oTxnVar         <- txnVarMap.getTxnVar(materializedKey)
        result <- oTxnVar match {
                    case Some(txnVar) =>
                      for {
                        rId <- Async[F].delay(txnVar.runtimeId)
                        oEntry <-
                          Async[F].delay(
                            log
                              .get(rId)
                              .map(_.asInstanceOf[TxnLogEntry[Option[V]]])
                          )
                        innerResult <- oEntry match {
                                         case Some(entry) =>
                                           for {
                                             newEntry <-
                                               Async[F].delay(
                                                 rId -> entry.set(None)
                                               )
                                             newLog <-
                                               Async[F].delay(log + newEntry)
                                           } yield TxnLogValid(newLog)
                                         case None =>
                                           for {
                                             txnVal <- txnVar.get
                                             newEntry <-
                                               Async[F].delay(
                                                 rId -> TxnLogUpdateVarMapEntry(
                                                   materializedKey,
                                                   Some(txnVal),
                                                   None,
                                                   txnVarMap
                                                 )
                                               )
                                             newLog <-
                                               Async[F].delay(log + newEntry)
                                           } yield TxnLogValid(newLog)
                                       }
                      } yield innerResult.asInstanceOf[TxnLog]
                    case None =>
                      for {
                        rid <- txnVarMap.getRuntimeId(
                                 materializedKey
                               )
                        rawEntry <- Async[F].delay(
                                      log
                                        .get(rid)
                                        .map(_.asInstanceOf[TxnLogEntry[Option[V]]])
                                    )
                        innerResult <- rawEntry match {
                                         case Some(entry) =>
                                           for {
                                             newEntry <-
                                               Async[F].delay(
                                                 rid -> entry.set(None)
                                               )
                                             newLog <-
                                               Async[F].delay(log + newEntry)
                                           } yield TxnLogValid(newLog)
                                         case _ => // Throw error to be consistent with read behaviour
                                           Async[F].delay(TxnLogError {
                                             new RuntimeException(
                                               s"Tried to remove non-existent key $materializedKey in transactional map"
                                             )
                                           })
                                       }

                      } yield innerResult.asInstanceOf[TxnLog]
                  }
      } yield result

      resultSpec.handleErrorWith(raiseError)
    }

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

    override private[stm] lazy val isDirty: F[Boolean] = {
      def setDeferred(container: Deferred[F, Boolean]): F[Unit] = {
        for {
          dirtyIndicators <- log.values.toList.parTraverse { lv =>
                               Async[F].ifM(lv.isDirty)(
                                 container.complete(true) >> Async[F].pure(true),
                                 Async[F].pure(false)
                               )
                             }
          _ <- Async[F].ifM(Async[F].delay(dirtyIndicators.exists(b => b)))(
                 Async[F].unit,
                 container.complete(false).void
               )
        } yield ()
      }

      for {
        gotDirt <- Deferred[F, Boolean]
        logFib  <- setDeferred(gotDirt).start
        result  <- gotDirt.get
        _       <- logFib.cancel
        _       <- logFib.join
      } yield result
    }

    override private[stm] lazy val idFootprint: F[IdFootprint] =
      log.values.toList
        .parTraverse { entry =>
          entry.idFootprint
        }
        .map(_.reduce(_ mergeWith _))

    override private[stm] def withLock[A](fa: F[A]): F[A] =
      for {
        locks <- log.values.toList.parTraverse(_.lock)
        result <-
          locks.toSet.flatten
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

    override private[stm] lazy val isDirty: F[Boolean] =
      validLog.isDirty

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

    override private[stm] lazy val isDirty =
      Async[F].pure(false)

    override private[stm] lazy val commit: F[Unit] =
      Async[F].unit

    override private[stm] lazy val idFootprint: F[IdFootprint] =
      Async[F].pure(IdFootprint.empty)
  }

  private[stm] case class TxnRetryException(validLog: TxnLogValid) extends RuntimeException

}
