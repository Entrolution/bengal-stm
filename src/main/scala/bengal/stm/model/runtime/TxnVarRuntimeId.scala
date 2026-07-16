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
package bengal.stm.model.runtime

// The id a footprint and a log are keyed on: a value issued by the runtime's
// global allocator (STM.txnVarIdGen), plus an optional parent for ids that live
// inside a container (a map entry's parent is its TxnVarMap — see
// TxnVarMap.getRuntimeId).
//
// EVERY id — entity or map-key existential — draws from that ONE allocator, so
// raw values are unique across the whole runtime. That is a requirement, not a
// nicety: IdFootprint's conflict and coverage tests compare RAW values with the
// parent stripped (combinedRawIds and friends), so two ids issued from
// independent counters could alias in those sets even though their (value,
// parent) pairs differ.
//
// THE HIERARCHY IS TWO LEVELS DEEP, AND EVERY CONSUMER ASSUMES SO. The type says
// otherwise — `parent` is recursive and nothing stops constructing a chain of
// any depth — but nothing reads one. IdFootprint's compatibility relation and
// its coverage check both do ONE-HOP parent tests (parentIn/coveredBy below), as
// does getValidated, so an id nested two containers down would have a parent the
// relation looks at and a grandparent it never does: a conflict on the outer
// container would be missed and the pair judged compatible. Today the depth is 2
// by construction, because the only parent anyone assigns is a map's own
// runtimeId and maps do not nest. A nested-map feature would type-check against
// this class and silently lose conflicts; it needs multi-hop coverage in
// IdFootprint first.
final private[stm] case class TxnVarRuntimeId(
  value: Long,
  parent: Option[TxnVarRuntimeId] = None
) {

  // The one-hop parent test every conflict/coverage relation turns on. If maps
  // ever nest, this is the seam that must become multi-hop.
  private[stm] def parentIn(rawIds: Set[Long]): Boolean =
    parent.exists(p => rawIds.contains(p.value))

  private[stm] def coveredBy(rawIds: Set[Long]): Boolean =
    rawIds.contains(value) || parentIn(rawIds)
}
