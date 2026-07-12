#!/usr/bin/env bash
# Verify that specs/README.md's invariant tables and the `// SPEC: <Name>`
# anchors in src/ agree with each other. TWO PASSES, because the correspondence
# can rot from either end:
#
#   FORWARD — every invariant listed in a README cross-reference table has a
#     matching `// SPEC: <InvariantName>` anchor in the source file the table
#     names. Catches an invariant that was documented but never anchored, or
#     whose code moved to a different file.
#
#   REVERSE — every `// SPEC: <Name>` anchor in src/ appears as a PARSED row in
#     those tables. Catches an orphaned or renamed anchor, and also catches a
#     README row that silently fell out of the awk parse through a malformed
#     backtick or path: without this pass, a row that stopped parsing would take
#     its invariant out of the forward check and NOTHING would notice.
#
# Both directions fail open without the other, which is why both exist. Exits
# non-zero if either finds a mismatch. Run from the repository root.
#
# Ported from ../echidna/specs/verify_anchors.sh (identical mechanism; Scala
# sources use the same `//` comment syntax and live under src/, so both the
# anchor grep and the path regex carry over unchanged).

set -euo pipefail

README="specs/README.md"

if [[ ! -f "$README" ]]; then
  echo "error: $README not found — run from the repository root." >&2
  exit 2
fi

# Extract rows of the form:
#   | `InvariantName` | `src/path/to/file.scala` | description |
# from the cross-reference tables. The first column is the invariant, the
# second is the source file carrying the anchor.
rows=$(awk '
  /^### .* \(anchors in / { in_table = 1; next }
  /^## / { in_table = 0 }
  in_table && /^\| *`[A-Za-z_][A-Za-z0-9_]*` *\| *`src\// {
    line = $0
    # Invariant is the first backticked token.
    match(line, /`[A-Za-z_][A-Za-z0-9_]*`/)
    inv = substr(line, RSTART + 1, RLENGTH - 2)
    # Source path: first backticked token starting with src/.
    rest = substr(line, RSTART + RLENGTH)
    match(rest, /`src\/[^`]+`/)
    path = substr(rest, RSTART + 1, RLENGTH - 2)
    print inv "\t" path
  }
' "$README")

if [[ -z "$rows" ]]; then
  echo "error: no invariant rows parsed from $README" >&2
  echo "hint: table rows must match | \`Invariant\` | \`src/...\` | ... |" >&2
  exit 2
fi

missing=0
checked=0

while IFS=$'\t' read -r invariant path; do
  [[ -z "$invariant" ]] && continue
  checked=$((checked + 1))
  if [[ ! -f "$path" ]]; then
    echo "MISSING: $invariant — declared source file $path does not exist" >&2
    missing=$((missing + 1))
    continue
  fi
  # Match `// SPEC: <Invariant>` followed by end-of-line, space, or non-alphanumeric —
  # prevents `TallyNonNegative` from matching an anchor named `Tally`.
  if ! grep -Eq "// SPEC: ${invariant}([^A-Za-z0-9_]|$)" "$path"; then
    echo "MISSING: $invariant — no \`// SPEC: ${invariant}\` anchor in $path" >&2
    missing=$((missing + 1))
  fi
done <<< "$rows"

if [[ $missing -gt 0 ]]; then
  echo ""
  echo "anchor verification failed: $missing missing of $checked declared"
  echo "fix: add \`// SPEC: <InvariantName>\` next to the relevant code,"
  echo "     or update specs/README.md if the invariant moved to a different file."
  exit 1
fi

# Reverse pass: every `// SPEC: <Name>` anchor in the source tree must appear
# as a PARSED row above. This closes both fail-open directions: an orphaned
# or renamed anchor is flagged here, and a README row that silently fell out
# of the awk parse (malformed backticks/path) leaves its code anchor orphaned
# and is flagged here too.
orphans=0
declared_names=$(printf '%s\n' "$rows" | cut -f1)
while IFS= read -r name; do
  [[ -z "$name" ]] && continue
  if ! printf '%s\n' "$declared_names" | grep -qx "$name"; then
    echo "ORPHAN: \`// SPEC: $name\` exists in src/ but is not a parsed row in $README" >&2
    orphans=$((orphans + 1))
  fi
done < <(grep -rhoE '// SPEC: [A-Za-z_][A-Za-z0-9_]*' src/ | sed 's|// SPEC: ||' | sort -u)

if [[ $orphans -gt 0 ]]; then
  echo ""
  echo "anchor verification failed: $orphans orphaned anchor(s) in src/"
  echo "fix: add a cross-reference row to $README, or remove/rename the anchor."
  exit 1
fi

echo "anchor verification OK: $checked invariants mapped and found; no orphans."
