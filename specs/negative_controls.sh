#!/usr/bin/env bash
# Run the negative-control mutations: apply each patch in specs/nc/ to a
# scratch copy of the specs tree, run TLC, and REQUIRE the documented red
# verdict.
#
# A negative control proves a config CAN fail: break one fix, watch the
# counterexample come back. A control that stays green is a hole in the
# suite — the config it guards is verifying nothing about that fix — so a
# green mutated run FAILS this script, exactly inverted from the ordinary
# expectations run.
#
# The registry is specs/nc/registry.tsv: one row per control —
#   <id> <patch> <module.tla> <config.cfg> <NONE-forbidden expected red>
# where the expectation is an invariant name, DEADLOCK, or
# ASSUMPTION:<Name> (a named ASSUME that fails at parse time — no state
# graph; FootprintLemmas is checked this way).
#
# PATCH MAINTENANCE: patches are cut against the CURRENT specs tree. Any
# spec edit that touches a mutated region must regenerate the affected
# patches (re-break a fresh copy, `diff -u`). A patch that stops applying
# fails LOUDLY here — that is the alarm that this maintenance is due, not
# an inconvenience to suppress.
#
# NOT A PATCH, by design:
#   NC-8 — the dirty-check deletion itself, pinned not as a mutation but
#          as the CoverageSubsumesDirty invariant every push checks.
# (NC-2's original ORACLE — the DirtyRestart action-coverage count — died
# with the dirty check; its break, reverting the H3 fix, is in the registry
# with a red-verdict oracle instead.)
#
# Run from the repository root: ./specs/negative_controls.sh

set -uo pipefail

REG="specs/nc/registry.tsv"
if [[ ! -f "$REG" ]]; then
  echo "ERROR: $REG is missing." >&2
  exit 1
fi
if [[ ! -f specs/tla2tools.jar ]]; then
  echo "ERROR: specs/tla2tools.jar is missing (CI downloads it; locally, see specs.yml)." >&2
  exit 1
fi

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

# The repo root, captured once: the per-control work happens inside cd'd
# subshells, and paths back to the registry/patches must not depend on
# where those subshells have wandered.
ROOT="$PWD"

total=0
failed=0

# `|| [[ -n ... ]]` keeps a final registry row that lacks a trailing
# newline: read returns nonzero there but still fills the fields, and
# silently dropping the last control would report a smaller-but-green run.
while IFS=$'\t' read -r id patch module cfg expect || [[ -n "${id:-}" ]]; do
  [[ "$id" =~ ^#.*$ || -z "${id// }" ]] && continue
  total=$((total + 1))

  # Pristine scratch copy per control, symlinks PRESERVED: cp -RP on both
  # BSD/macOS and GNU (bare BSD `cp -r` FOLLOWS links and would materialize
  # them). commit/ and scheduler/ symlink common/Footprint.tla, and a patch
  # against the common file must propagate through the links to the configs
  # that EXTEND it — a materialized copy would leave the commit tree running
  # the pristine file and NC-3 would silently pass green. Relative links
  # resolve inside the copied tree. A fresh copy per control keeps patched
  # state from leaking between controls.
  rm -rf "$SCRATCH/specs"
  cp -RP specs "$SCRATCH/specs"
  rm -rf "$SCRATCH/specs/nc" "$SCRATCH/specs/states" 2>/dev/null || true

  if ! (cd "$SCRATCH" && patch -p1 --silent <"$ROOT/specs/nc/$patch") 2>/dev/null; then
    # Re-run without --silent for the log, from a fresh copy.
    rm -rf "$SCRATCH/specs" && cp -RP specs "$SCRATCH/specs"
    echo "FAIL: $id — specs/nc/$patch no longer applies. A spec edit touched" >&2
    echo "      its mutation site: regenerate the patch against the current tree." >&2
    (cd "$SCRATCH" && patch -p1 --dry-run <"$ROOT/specs/nc/$patch") >&2 || true
    failed=$((failed + 1))
    continue
  fi

  # </dev/null: the subshell must not inherit the registry as stdin.
  if out=$( (cd "$SCRATCH" && ./specs/check_expected.sh "$cfg" "$module" "$expect" </dev/null) 2>&1 ); then
    echo "OK: $id — mutation reproduced the expected red ($expect) on $cfg."
  else
    echo "FAIL: $id — the mutated spec did NOT go red as documented ($expect on $cfg)." >&2
    echo "      Either the config no longer witnesses this fix (the control is" >&2
    echo "      dead and the green run proves nothing about it), or the mutation" >&2
    echo "      rotted. Reproduce with: apply specs/nc/$patch to a scratch copy" >&2
    echo "      and run ./specs/check_expected.sh $cfg $module $expect" >&2
    printf '%s\n' "$out" | tail -40 >&2
    failed=$((failed + 1))
  fi
done <"$REG"

echo
# Zero parsed controls exiting green would be this harness's own vacuity
# hole — the exact failure class it exists to catch.
if [[ $total -eq 0 ]]; then
  echo "ERROR: no controls parsed from $REG." >&2
  exit 1
fi
if [[ $failed -ne 0 ]]; then
  echo "$failed of $total negative controls FAILED." >&2
  exit 1
fi
echo "All $total negative controls reproduced their documented reds."
