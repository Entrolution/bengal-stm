#!/usr/bin/env bash
# Run TLC on a spec and assert the EXPECTED outcome:
#
#   ./specs/check_expected.sh <config.cfg> <module.tla> NONE
#       — require a clean run ("No error has been found").
#
#   ./specs/check_expected.sh <config.cfg> <module.tla> <InvariantName>
#       — require TLC to report exactly "Invariant <InvariantName> is
#         violated". This pins an EXPECTED-RED verdict: the config documents a
#         defect the protocol still has, and CI fails if the counterexample
#         stops reproducing (e.g. someone fixed it) so that the spec and the
#         verdict table in specs/README.md get updated together.
#
#   ./specs/check_expected.sh <config.cfg> <module.tla> DEADLOCK
#       — require TLC to report "Deadlock reached". Same contract as an
#         expected-red invariant, for defects whose symptom is a dead end
#         rather than a violated predicate.
#
# Run from the repository root. TLC's jar is expected at specs/tla2tools.jar.

# NOTE: deliberately NO `set -e` — TLC exits nonzero on expected violations
# (the entire point of a pinned-red run); the exit code is captured and
# interpreted below instead. Adding -e would kill every pinned-red check.
set -uo pipefail

CFG="${1:?usage: check_expected.sh <cfg> <tla> <NONE|InvariantName|DEADLOCK> [ALLOW_DEADLOCK]}"
TLA="${2:?usage: check_expected.sh <cfg> <tla> <NONE|InvariantName|DEADLOCK> [ALLOW_DEADLOCK]}"
EXPECTED="${3:?usage: check_expected.sh <cfg> <tla> <NONE|InvariantName|DEADLOCK> [ALLOW_DEADLOCK]}"
# Optional 4th arg ALLOW_DEADLOCK passes TLC's -deadlock flag (suppresses
# deadlock detection). Default is detection ON: specs with legitimate
# terminal states model them as an explicit Terminating stutter, so any
# reported deadlock is a real protocol deadlock. Failure-injection configs
# (SchedulerAborts) keep the flag — aborted zombies legitimately strand
# their dependents.
DEADLOCK_FLAG=""
if [[ "${4:-}" == "ALLOW_DEADLOCK" ]]; then
  DEADLOCK_FLAG="-deadlock"
fi

OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT

java -XX:+UseParallelGC -cp specs/tla2tools.jar tlc2.TLC \
    -config "$CFG" "$TLA" -workers auto $DEADLOCK_FLAG >"$OUT" 2>&1
TLC_EXIT=$?

if [[ "$EXPECTED" == "NONE" ]]; then
  if [[ $TLC_EXIT -eq 0 ]] && grep -q "No error has been found" "$OUT"; then
    echo "OK: $TLA ($CFG) — clean run, as expected."
    exit 0
  fi
  echo "UNEXPECTED: $TLA ($CFG) should verify cleanly but did not (exit $TLC_EXIT):" >&2
  tail -40 "$OUT" >&2
  exit 1
fi

# EXPECTED=DEADLOCK pins a reachable dead-end state. Every spec here models its
# legitimate terminals as an explicit Terminating stutter, so a reported
# deadlock is a real one.
#
# The live pin is SchedulerAbsentKey.cfg, and it is a NEGATIVE CONTROL rather
# than an open defect: it withholds the log entry that anyReadChangedSinceRead
# folds over — which is what a waitFor on an ABSENT MAP KEY used to do — and the
# parker sleeps forever. If it ever stops deadlocking, then either the model
# stopped modelling the unlogged read or the park protocol changed shape, and
# H1's second guard is no longer under test. H1 itself is fixed; the pin for the
# fix is SchedulerRetry.cfg, which must stay CLEAN.
if [[ "$EXPECTED" == "DEADLOCK" ]]; then
  if grep -q "Deadlock reached" "$OUT"; then
    echo "OK: $TLA ($CFG) — reproduced the pinned deadlock."
    exit 0
  fi
  echo "UNEXPECTED: $TLA ($CFG) did not reproduce the pinned deadlock (exit $TLC_EXIT)." >&2
  echo "If the protocol changed, update specs/README.md's verdict table and this CI" >&2
  echo "expectation together — those two are the only places a verdict is recorded." >&2
  tail -40 "$OUT" >&2
  exit 1
fi

if grep -q "Invariant $EXPECTED is violated" "$OUT"; then
  echo "OK: $TLA ($CFG) — reproduced the pinned counterexample ($EXPECTED)."
  exit 0
fi

echo "UNEXPECTED: $TLA ($CFG) did not reproduce the pinned $EXPECTED violation (exit $TLC_EXIT)." >&2
echo "If the protocol changed, update specs/README.md's verdict table and this CI" >&2
echo "expectation together — those two are the only places a verdict is recorded." >&2
tail -40 "$OUT" >&2
exit 1
