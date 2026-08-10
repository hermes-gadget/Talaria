#!/usr/bin/env bash
# Fixture tests for version-code.sh — proves uniqueness and monotonic ordering
# of every accepted tag shape, plus rejection of malformed/overflow input.
# Usage: bash .github/scripts/version-code-fixtures.sh   (exit 0 = all pass)
set -euo pipefail

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/version-code.sh"
PASS=0; FAIL=0

check_code() { # <tag> <expected-code>
  local tag="$1" expected="$2" actual
  actual="$(bash "${SCRIPT}" "${tag}" | sed -n 's/^code=//p')"
  if [ "${actual}" = "${expected}" ]; then
    PASS=$((PASS + 1))
  else
    FAIL=$((FAIL + 1))
    echo "FAIL: ${tag} -> code=${actual:-<error>}, expected ${expected}"
  fi
}

check_reject() { # <tag>
  local tag="$1"
  if bash "${SCRIPT}" "${tag}" >/dev/null 2>&1; then
    FAIL=$((FAIL + 1))
    echo "FAIL: ${tag} should have been rejected"
  else
    PASS=$((PASS + 1))
  fi
}

# --- Uniqueness / ordering fixtures ---
# Stable, distinct, increasing across major/minor carries.
check_code "v0.2.0" 2009
check_code "v0.3.0" 3009
check_code "v1.0.0" 100009
check_code "v10.0.0" 1000009
check_code "v99.99.9" 9999099

# Maximum patch (9) then minor carry — distinct codes.
check_code "v0.8.9" 8099
check_code "v0.9.0" 9009
# Patch 10 must be rejected (not silently collide with a minor bump).
check_reject "v0.1.10"
check_reject "v0.1.100"

# The original collision: v0.1.100 and v0.2.0 both resolved to 2009.
check_reject "v0.1.100"
check_code "v0.2.0" 2009

# Minor overflow and major overflow.
check_reject "v0.100.0"
check_reject "v100.0.0"
check_reject "v100.0.0-rc1"

# Prerelease-to-stable ordering within the same version.
check_code "v0.8.3-rc1" 8031
check_code "v0.8.3-rc8" 8038
check_code "v0.8.3" 8039
check_code "v0.8.4" 8049

# Malformed tags.
check_reject "v0.8"
check_reject "v0.8.3-rc9"      # rc N limited to 1..8
check_reject "v0.8.3-rc0"
check_reject "v0.8.3-beta"
check_reject "v0.8.3.4"
check_reject "v0.8.3+meta"
check_reject "0.8.3"           # missing v
check_reject "v0.08.3"         # leading zero
check_reject "v0.8.03"
check_reject "v00.8.3"
check_reject "v0.8.3-rc1-dirty"
check_reject "v0.0.1"          # below legacy floor (202)

echo "version-code fixtures: ${PASS} passed, ${FAIL} failed"
[ "${FAIL}" -eq 0 ]
