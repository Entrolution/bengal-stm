#!/usr/bin/env bash
# Run TLC on a spec and assert the EXPECTED outcome:
#
#   ./specs/check_expected.sh <config.cfg> <module.tla> NONE
#       — require a clean run ("No error has been found").
#
#   ./specs/check_expected.sh <config.cfg> <module.tla> <InvariantName>
#       — require TLC to report exactly "Invariant <InvariantName> is
#         violated". This pins an EXPECTED-RED verdict: the spec documents a
#         confirmed defect, and CI fails if the counterexample stops
#         reproducing (e.g. the protocol was fixed) so that the spec, the
#         verdict table in specs/README.md, and the plan's hypothesis rows
#         get updated together.
#
# Run from the repository root. TLC's jar is expected at specs/tla2tools.jar.

# NOTE: deliberately NO `set -e` — TLC exits nonzero on expected violations
# (the entire point of a pinned-red run); the exit code is captured and
# interpreted below instead. Adding -e would kill every pinned-red check.
set -uo pipefail

CFG="${1:?usage: check_expected.sh <cfg> <tla> <NONE|InvariantName> [ALLOW_DEADLOCK]}"
TLA="${2:?usage: check_expected.sh <cfg> <tla> <NONE|InvariantName> [ALLOW_DEADLOCK]}"
EXPECTED="${3:?usage: check_expected.sh <cfg> <tla> <NONE|InvariantName> [ALLOW_DEADLOCK]}"
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

if grep -q "Invariant $EXPECTED is violated" "$OUT"; then
  echo "OK: $TLA ($CFG) — reproduced the pinned counterexample ($EXPECTED)."
  exit 0
fi

echo "UNEXPECTED: $TLA ($CFG) did not reproduce the pinned $EXPECTED violation (exit $TLC_EXIT)." >&2
echo "If the protocol was fixed, update the verdict table in specs/README.md," >&2
echo "the hypothesis rows in docs/plans/formal-specs.md, and this CI expectation." >&2
tail -40 "$OUT" >&2
exit 1
