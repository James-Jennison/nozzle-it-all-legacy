#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/nia-source-identity.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT
mkdir -p "$TEST_ROOT/source/scripts"
cp "$ROOT/scripts/package_linux.sh" "$TEST_ROOT/source/scripts/"
printf 'exact archive bytes\n' > "$TEST_ROOT/source.tar.gz"
ARCHIVE_SHA256="$(sha256sum "$TEST_ROOT/source.tar.gz" | cut -d' ' -f1)"
REVISION="0123456789abcdef0123456789abcdef01234567"
cat > "$TEST_ROOT/source-attestation.json" <<EOF
{"source_revision":"$REVISION","source_archive_sha256":"$ARCHIVE_SHA256"}
EOF

run_rejected() {
  local expected="$1"
  shift
  local output
  if output="$(cd "$TEST_ROOT/source" && env -i PATH="$PATH" HOME="$TEST_ROOT/home" "$@" scripts/package_linux.sh --validate-source-only 2>&1)"; then
    echo "expected source identity validation to fail: $expected" >&2
    return 1
  fi
  grep -Fq "$expected" <<<"$output"
  test ! -e "$TEST_ROOT/source/dist/linux/nozzle-it-all_0.1.0_amd64.deb"
}

run_rejected "archive build requires ORG_GRADLE_PROJECT_nozzleSourceRevision"
run_rejected "source revision must be the full lowercase 40-character commit" \
  ORG_GRADLE_PROJECT_nozzleSourceRevision=invalid
run_rejected "source revision does not match source attestation" \
  ORG_GRADLE_PROJECT_nozzleSourceRevision=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  NOZZLE_SOURCE_ATTESTATION="$TEST_ROOT/source-attestation.json" \
  NOZZLE_SOURCE_ARCHIVE="$TEST_ROOT/source.tar.gz"

printf 'tampered archive bytes\n' > "$TEST_ROOT/source.tar.gz"
run_rejected "source archive does not match source attestation" \
  ORG_GRADLE_PROJECT_nozzleSourceRevision="$REVISION" \
  NOZZLE_SOURCE_ATTESTATION="$TEST_ROOT/source-attestation.json" \
  NOZZLE_SOURCE_ARCHIVE="$TEST_ROOT/source.tar.gz"

printf 'exact archive bytes\n' > "$TEST_ROOT/source.tar.gz"
(cd "$TEST_ROOT/source" && env -i PATH="$PATH" HOME="$TEST_ROOT/home" \
  ORG_GRADLE_PROJECT_nozzleSourceRevision="$REVISION" \
  NOZZLE_SOURCE_ATTESTATION="$TEST_ROOT/source-attestation.json" \
  NOZZLE_SOURCE_ARCHIVE="$TEST_ROOT/source.tar.gz" \
  scripts/package_linux.sh --validate-source-only)

echo "source identity validation tests passed"
