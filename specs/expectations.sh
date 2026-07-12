#!/usr/bin/env bash
# The expectation registry. Every .cfg under specs/ declares what TLC must
# report, and this is the only place that list exists.
#
# WHY THIS EXISTS. The expectation used to live in TWO places: prose in the
# cfg header ("EXPECTED RED: CommitSnapshotValid") and an argument in
# .github/workflows/specs.yml. Nothing tied them together, and they drifted --
# four cfg headers said EXPECTED RED for months while CI asserted NONE, and
# check_expected.sh could not see it because it only ever compares TLC's OUTPUT
# to the argument CI passed it. A cfg could also ship with no stated expectation
# at all (CommitH6 did), or exist and be run by nothing.
#
# So the cfg is now the source of truth and CI derives from it. Three failure
# modes become impossible by construction rather than merely discouraged:
#
#   * a cfg with no expectation      -> this script fails
#   * a cfg no CI step runs          -> CI globs the directory; there is no list to forget
#   * a header that disagrees with CI -> there is only one declaration to disagree with
#
# DIRECTIVES. Each cfg carries a block of these, in TLA+ comment syntax:
#
#   \* @spec    specs/scheduler/Scheduler.tla    (required)
#   \* @expect  NONE | DEADLOCK | <InvariantName> (required; passed verbatim to check_expected.sh)
#   \* @flags   ALLOW_DEADLOCK                   (optional)
#   \* @run     push | dispatch                  (optional; default push)
#
# USAGE
#   ./specs/expectations.sh --list      parse and validate; print the table; run no TLC
#   ./specs/expectations.sh             run every push-gated expectation
#   ./specs/expectations.sh --all       also run the dispatch-gated ones
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

MODE="${1:-run}"

directive() {  # directive <file> <name>
  sed -nE "s|^\\\\\* *@$2 +(.*[^ ]) *$|\1|p" "$1" | head -1
}

fail=0
rows=()

shopt -s nullglob
cfgs=(specs/*/*.cfg)
shopt -u nullglob

if [[ ${#cfgs[@]} -eq 0 ]]; then
  echo "ERROR: no .cfg files found under specs/" >&2
  exit 1
fi

for cfg in "${cfgs[@]}"; do
  spec=$(directive "$cfg" spec)
  expect=$(directive "$cfg" expect)
  flags=$(directive "$cfg" flags)
  run=$(directive "$cfg" run)
  run="${run:-push}"

  if [[ -z "$expect" ]]; then
    echo "ERROR: $cfg declares no '\\* @expect' directive." >&2
    echo "       Every config must state what TLC is expected to report." >&2
    fail=1
    continue
  fi
  if [[ -z "$spec" ]]; then
    echo "ERROR: $cfg declares no '\\* @spec' directive (which .tla to check it against)." >&2
    fail=1
    continue
  fi
  if [[ ! -f "$spec" ]]; then
    echo "ERROR: $cfg names a spec that does not exist: $spec" >&2
    fail=1
    continue
  fi
  if [[ "$run" != "push" && "$run" != "dispatch" ]]; then
    echo "ERROR: $cfg has '\\* @run $run'; expected 'push' or 'dispatch'." >&2
    fail=1
    continue
  fi

  # NO SECOND DECLARATION. @expect is the only place a verdict may be stated.
  # The prose that used to carry one drifted for months -- four headers read
  # "EXPECTED RED" while CI asserted NONE -- because nothing could compare a
  # sentence to an argument. Rather than try to reconcile two declarations, we
  # forbid the second. Explain the reasoning in prose all you like; just do not
  # restate the verdict there, because a restatement is a thing that can rot.
  if grep -qiE 'EXPECTED +(RED|CLEAN)' "$cfg"; then
    echo "ERROR: $cfg states a verdict in prose ('EXPECTED RED/CLEAN')." >&2
    echo "       The verdict belongs in '\\* @expect' and nowhere else -- a second" >&2
    echo "       declaration is the thing that drifted last time. Reword the prose to" >&2
    echo "       explain WHY the config verifies as it does, and let @expect say WHAT." >&2
    fail=1
    continue
  fi

  rows+=("$cfg|$spec|$expect|$flags|$run")
done

if [[ $fail -ne 0 ]]; then
  exit 1
fi

if [[ "$MODE" == "--list" ]]; then
  printf "%-42s %-38s %-22s %-16s %s\n" CONFIG SPEC EXPECT FLAGS RUN
  for row in "${rows[@]}"; do
    IFS='|' read -r cfg spec expect flags run <<<"$row"
    printf "%-42s %-38s %-22s %-16s %s\n" "$cfg" "$spec" "$expect" "${flags:--}" "$run"
  done
  echo
  echo "${#rows[@]} expectations, all declared."
  exit 0
fi

# --measure regenerates specs/measured.tsv, which CI then diffs. This is the
# only thing that catches a stale STATE COUNT, and nothing did before: the
# Scheduler config's header claimed "~24k distinct states" while the truth was
# 846k, and the lemma check was documented at "~1s" against a real 39s. Prose
# discipline will not catch that class of rot; a regenerated file will.
#
# ONLY @expect NONE configs are measured, and the reason is the same mistake in
# miniature. An exhaustive run explores the whole reachable state space, so its
# counts are a property of the spec and reproduce exactly. A run that HALTS on a
# violation explores a scheduling-dependent prefix, and its counts do not:
# consecutive -workers auto runs of SchedulerAbsentKey gave 9,201 and 8,843.
# Pinning a number like that would produce a guard that fails at random, which
# is worse than no guard.
if [[ "$MODE" == "--measure" ]]; then
  out=specs/measured.tsv
  {
    echo "# Measured state spaces. GENERATED -- do not hand-edit."
    echo "# Regenerate with: ./specs/expectations.sh --measure"
    echo "#"
    echo "# Only exhaustive (@expect NONE) configs appear. A run that halts on a"
    echo "# violation explores a scheduling-dependent prefix and its counts do not"
    echo "# reproduce, so pinning them would produce a guard that fails at random."
    printf "#\n# %-40s\t%s\t%s\t%s\n" config generated distinct depth
  } >"$out"

  for row in "${rows[@]}"; do
    IFS='|' read -r cfg spec expect flags run <<<"$row"
    [[ "$expect" == "NONE" ]] || continue
    [[ "$(directive "$cfg" measure)" == "no" ]] && continue
    echo "measuring $cfg ..." >&2
    res=$(java -XX:+UseParallelGC -cp specs/tla2tools.jar tlc2.TLC \
            -config "$cfg" "$spec" -workers auto 2>&1)
    gen=$(sed -nE 's/^([0-9]+) states generated, ([0-9]+) distinct.*/\1/p' <<<"$res" | tail -1)
    dis=$(sed -nE 's/^([0-9]+) states generated, ([0-9]+) distinct.*/\2/p' <<<"$res" | tail -1)
    dep=$(sed -nE 's/.*depth of the complete state graph search is ([0-9]+).*/\1/p' <<<"$res" | tail -1)
    if [[ -z "$gen" || -z "$dis" ]]; then
      echo "ERROR: could not parse a state count out of $cfg." >&2
      exit 1
    fi
    printf "%-42s\t%s\t%s\t%s\n" "$cfg" "$gen" "$dis" "${dep:--}" >>"$out"
  done
  echo "wrote $out" >&2
  exit 0
fi

# specs/README.md publishes these counts in its state-space table, and a guard
# that only checked measured.tsv would let the TABLE rot while the file stayed
# current -- which is how the 24k-vs-846k figure survived: someone chased it out
# of one file and left it in another. So the table is checked against the
# measurement, not against anyone's memory.
if [[ "$MODE" == "--check-readme" ]]; then
  if [[ ! -f specs/measured.tsv ]]; then
    echo "ERROR: specs/measured.tsv is missing. Run: ./specs/expectations.sh --measure" >&2
    exit 1
  fi
  bad=0
  while IFS=$'\t' read -r cfg gen dis dep; do
    [[ "$cfg" =~ ^# ]] && continue
    [[ -z "${cfg// }" ]] && continue
    cfg="${cfg// }"
    name=$(basename "$cfg" .cfg)
    row=$(grep -E "^\| \`$name\` \|" specs/README.md | head -1)
    if [[ -z "$row" ]]; then
      echo "ERROR: specs/README.md has no state-space row for \`$name\`." >&2
      bad=1
      continue
    fi
    # "6,082 / 2,100" -> 6082 2100
    got=$(sed -E 's/.*\| *([0-9,]+) *\/ *([0-9,]+) *\|.*/\1 \2/' <<<"$row" | tr -d ',')
    rgen=$(cut -d' ' -f1 <<<"$got")
    rdis=$(cut -d' ' -f2 <<<"$got")
    if [[ "$rgen" != "$gen" || "$rdis" != "$dis" ]]; then
      echo "ERROR: specs/README.md is stale for \`$name\`:" >&2
      echo "         table says  $rgen / $rdis" >&2
      echo "         TLC reports $gen / $dis" >&2
      bad=1
    fi
  done <specs/measured.tsv
  if [[ $bad -ne 0 ]]; then
    echo >&2
    echo "Re-measure and update the table:  ./specs/expectations.sh --measure" >&2
    exit 1
  fi
  echo "specs/README.md state-space table agrees with specs/measured.tsv."
  exit 0
fi

status=0
for row in "${rows[@]}"; do
  IFS='|' read -r cfg spec expect flags run <<<"$row"
  if [[ "$run" == "dispatch" && "$MODE" != "--all" ]]; then
    echo "SKIP: $cfg (dispatch-gated; use --all)"
    continue
  fi
  # shellcheck disable=SC2086
  ./specs/check_expected.sh "$cfg" "$spec" "$expect" $flags || status=1
done

exit $status
