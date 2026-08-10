#!/usr/bin/env bash
# Strict semver-ish release-tag -> Android versionName/versionCode resolver.
#
# Supported: vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-rcN (N in 1..8).
# Anything else (dirty suffix, missing patch, letters) FAILS with exit 1.
#
# Decimal allocation (each component owns a fixed-width slot, so distinct
# supported tags ALWAYS produce distinct, monotonically increasing codes):
#   MAJ * 100000   (MAJ in 0..99 -> 2 digits, max 9,900,000)
#   MIN * 1000     (MIN in 0..99 -> 2 digits, max 99,000)
#   PAT * 10       (PAT in 0..9  -> 1 digit,  max 90)
#   SUFFIX 1..8 = rcN (prerelease), 9 = stable
#   max code = 9,999,099 << Android signed-32-bit ceiling (2,147,483,647)
#
# Ordering: v0.8.3-rc1 (8031) < v0.8.3 (8039) < v0.8.4 (8049).
# Out-of-range components are REJECTED before arithmetic, never wrapped.
#
# Usage: version-code.sh <tag>
# Prints: version=<versionName>  and  code=<versionCode> (one per line).
set -euo pipefail

TAG="${1:?usage: version-code.sh <tag>}"

if [[ ! "${TAG}" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)(-rc([1-8]))?$ ]]; then
  echo "::error::Unsupported version tag '${TAG}'. Expected vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-rcN (N=1..8)." >&2
  exit 1
fi

MAJ="${BASH_REMATCH[1]}"; MIN="${BASH_REMATCH[2]}"; PAT="${BASH_REMATCH[3]}"
RC="${BASH_REMATCH[5]:-}"

# Reject leading zeros ("08"-style) so arithmetic is unambiguous.
if [[ "${MAJ}" =~ ^0[0-9] || "${MIN}" =~ ^0[0-9] || "${PAT}" =~ ^0[0-9] ]]; then
  echo "::error::Version components must not have leading zeros in '${TAG}'." >&2
  exit 1
fi

MAJ=$((10#$MAJ)); MIN=$((10#$MIN)); PAT=$((10#$PAT))

# Component bounds must match the decimal allocation above.
if (( MAJ > 99 )); then
  echo "::error::Major version ${MAJ} in '${TAG}' exceeds the supported bound 99. Use a 2-digit major." >&2
  exit 1
fi
if (( MIN > 99 )); then
  echo "::error::Minor version ${MIN} in '${TAG}' exceeds the supported bound 99. Use a 2-digit minor." >&2
  exit 1
fi
if (( PAT > 9 )); then
  echo "::error::Patch version ${PAT} in '${TAG}' exceeds the supported bound 9. Bump the minor version instead." >&2
  exit 1
fi

SUFFIX=9
if [ -n "${RC}" ]; then SUFFIX="${RC}"; fi
CODE=$((MAJ * 100000 + MIN * 1000 + PAT * 10 + SUFFIX))

# Floor above legacy Obtainium debug builds (versionCode 2 and 201); reject
# any tag that cannot clear it (e.g. v0.0.x).
if [ "${CODE}" -lt 202 ]; then
  echo "::error::versionCode ${CODE} for tag '${TAG}' is below the legacy floor (202). Use a later version." >&2
  exit 1
fi

VERSION="${MAJ}.${MIN}.${PAT}"
if [ -n "${RC}" ]; then VERSION="${VERSION}-rc${RC}"; fi
echo "version=${VERSION}"
echo "code=${CODE}"
