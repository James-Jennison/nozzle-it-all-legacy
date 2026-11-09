#!/usr/bin/env bash
# Builds the Linux packages into dist/linux with provenance and SHA-256 sums:
#   nozzle-it-all                   Desktop, the PAXX U1 / Klipper / OctoPrint / PrusaLink / Bambu adapters and the
#                                   shared slicing engine (engine/native)
#   nozzle-stock-u1-adapter         the optional Stock U1 adapter, installed separately with its own minimal Java
#                                   runtime; Desktop finds it at /opt/nozzle-stock-u1-adapter
# Run through heavy-build:  heavy-build -- scripts/package_linux.sh [--engine FILE]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/dist/linux"
ENGINE="${NOZZLE_ENGINE:-/mnt/faststorage/build-work/nozzle-native-fork/dist/nozzle-engine}"
SOURCE_REVISION="${ORG_GRADLE_PROJECT_nozzleSourceRevision:-}"
SOURCE_STATE=" (exact source archive)"
SOURCE_ARCHIVE_SHA256=""
if [ -z "$SOURCE_REVISION" ]; then
  if ! git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "archive build requires ORG_GRADLE_PROJECT_nozzleSourceRevision" >&2
    exit 1
  fi
  SOURCE_REVISION="$(git -C "$ROOT" rev-parse HEAD)"
  SOURCE_STATE=""
  if ! git -C "$ROOT" diff --quiet || ! git -C "$ROOT" diff --cached --quiet; then
    SOURCE_STATE=" (with uncommitted changes)"
  fi
fi
if ! [[ "$SOURCE_REVISION" =~ ^[0-9a-f]{40}$ ]]; then
  echo "source revision must be the full lowercase 40-character commit" >&2
  exit 1
fi
if git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  ACTUAL_SOURCE_REVISION="$(git -C "$ROOT" rev-parse HEAD)"
  if [ "$SOURCE_REVISION" != "$ACTUAL_SOURCE_REVISION" ]; then
    echo "source revision does not match the checked-out Git commit" >&2
    exit 1
  fi
  SOURCE_STATE=""
  if ! git -C "$ROOT" diff --quiet || ! git -C "$ROOT" diff --cached --quiet; then
    SOURCE_STATE=" (with uncommitted changes)"
  fi
else
  SOURCE_ATTESTATION="${NOZZLE_SOURCE_ATTESTATION:-}"
  SOURCE_ARCHIVE="${NOZZLE_SOURCE_ARCHIVE:-}"
  [ -f "$SOURCE_ATTESTATION" ] || {
    echo "archive build requires NOZZLE_SOURCE_ATTESTATION" >&2; exit 1;
  }
  [ -f "$SOURCE_ARCHIVE" ] || {
    echo "archive build requires NOZZLE_SOURCE_ARCHIVE" >&2; exit 1;
  }
  readarray -t SOURCE_IDENTITY < <(python3 -c '
import json, re, sys
value = json.load(open(sys.argv[1], encoding="utf-8"))
revision = value.get("source_revision", "")
archive = value.get("source_archive_sha256", "")
if not re.fullmatch(r"[0-9a-f]{40}", revision) or not re.fullmatch(r"[0-9a-f]{64}", archive):
    raise SystemExit("invalid source attestation")
print(revision)
print(archive)
' "$SOURCE_ATTESTATION")
  [ "${#SOURCE_IDENTITY[@]}" -eq 2 ] || {
    echo "source attestation is incomplete" >&2; exit 1;
  }
  [ "$SOURCE_REVISION" = "${SOURCE_IDENTITY[0]}" ] || {
    echo "source revision does not match source attestation" >&2; exit 1;
  }
  SOURCE_ARCHIVE_SHA256="$(sha256sum "$SOURCE_ARCHIVE" | cut -d' ' -f1)"
  [ "$SOURCE_ARCHIVE_SHA256" = "${SOURCE_IDENTITY[1]}" ] || {
    echo "source archive does not match source attestation" >&2; exit 1;
  }
fi
export ORG_GRADLE_PROJECT_nozzleSourceRevision="$SOURCE_REVISION"
GRADLE=(/bin/bash "$ROOT/gradlew" --no-daemon --max-workers="${HEAVY_BUILD_JOBS:-6}")
VALIDATE_SOURCE_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --engine) ENGINE="$2"; shift 2 ;;
    --validate-source-only) VALIDATE_SOURCE_ONLY=1; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
[ "$VALIDATE_SOURCE_ONLY" -eq 0 ] || exit 0
[ -x "$ENGINE" ] || { echo "slicing engine not found at $ENGINE (build it with engine/native/scripts/build_engine_fork.sh)" >&2; exit 1; }
PINNED_ENGINE_SHA="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["base"]["desktop_binary_sha256"])' "$ROOT/engine/fork/ENGINE_PIN.json")"
[ "$(sha256sum "$ENGINE" | cut -d' ' -f1)" = "$PINNED_ENGINE_SHA" ] || {
  echo "slicing engine does not match engine/fork/ENGINE_PIN.json" >&2; exit 1;
}
mkdir -p "$OUT"
# Compose's package version is nozzleDesktopVersion, not Gradle project.version.
# Pass it explicitly so both package metadata and provenance use one identity.
VERSION="${ORG_GRADLE_PROJECT_nozzleDesktopVersion:-0.1.0}"
GRADLE+=("-PnozzleDesktopVersion=$VERSION")

echo "== Desktop ($VERSION)"
(cd "$ROOT" && "${GRADLE[@]}" -q :desktop:packageDeb :stock-u1-adapter:installDist -PnozzleEngine="$ENGINE")
STAGE="$(mktemp -d)"; trap 'rm -rf "$STAGE"' EXIT

# Desktop integration jpackage doesn't do well: its postinst registers a launcher with xdg-desktop-menu, which silently
# does nothing on current Ubuntu, and names it "nozzle-it-all" with no StartupWMClass, so docks show a generic icon.
# Ship a proper launcher and themed icons in the standard places instead; the app names its window class to match.
PKG="$STAGE/desktop"
SOURCE_DESKTOP_DEB="$ROOT/desktop/build/compose/binaries/main/deb/nozzle-it-all_${VERSION}_amd64.deb"
[ -s "$SOURCE_DESKTOP_DEB" ] || { echo "missing intended Compose package: $SOURCE_DESKTOP_DEB" >&2; exit 1; }
[ "$(dpkg-deb -f "$SOURCE_DESKTOP_DEB" Version)" = "$VERSION" ] || { echo "Compose package version mismatch" >&2; exit 1; }
dpkg-deb -R "$SOURCE_DESKTOP_DEB" "$PKG"
EMBEDDED_ENGINE="$PKG/opt/nozzle-it-all/lib/app/resources/nozzle-engine"
[ -s "$EMBEDDED_ENGINE" ] || { echo "Compose package is missing the embedded slicing engine" >&2; exit 1; }
[ "$(sha256sum "$EMBEDDED_ENGINE" | cut -d' ' -f1)" = "$PINNED_ENGINE_SHA" ] || {
  echo "Compose package embedded engine does not match engine/fork/ENGINE_PIN.json" >&2; exit 1;
}
install -Dm644 "$ROOT/desktop/packaging/linux/nozzle-it-all.desktop" "$PKG/usr/share/applications/nozzle-it-all.desktop"
install -Dm644 "$ROOT/THIRD_PARTY_NOTICES.md" "$PKG/usr/share/doc/nozzle-it-all/THIRD_PARTY_NOTICES.md"
# The optional viewport bridge statically links the pinned builder's GCC runtime.
# Ship its full supplied copyright/licensing texts, not just a link to them.
install -Dm644 /usr/share/doc/gcc-15-base/copyright "$PKG/usr/share/doc/nozzle-it-all/GCC-runtime-copyright"
for size in 16 32 48 64 128 256 512; do
  install -Dm644 "$ROOT/desktop/packaging/icons/nozzle-it-all-$size.png" "$PKG/usr/share/icons/hicolor/${size}x${size}/apps/nozzle-it-all.png"
done
install -Dm644 "$ROOT/desktop/packaging/icons/nozzle-it-all.svg" "$PKG/usr/share/icons/hicolor/scalable/apps/nozzle-it-all.svg"
rm -f "$PKG/opt/nozzle-it-all/lib/nozzle-it-all-nozzle-it-all.desktop"
"$ROOT/scripts/verify_nia_package_boundary.sh" --self-test
"$ROOT/scripts/verify_nia_package_boundary.sh" "$PKG" "$EMBEDDED_ENGINE" "$PINNED_ENGINE_SHA"
sed -i '/xdg-desktop-menu/d' "$PKG/DEBIAN/postinst" "$PKG/DEBIAN/prerm"
# ffmpeg plays printer cameras' H.264 streams at full frame rate (MJPEG from camera-streamer tops out near 4 fps).
sed -i 's/^Depends: /Depends: ffmpeg, util-linux, procps, libegl1, libgles2, /' "$PKG/DEBIAN/control"
for dependency in ffmpeg util-linux procps libegl1 libgles2; do
  grep -Eq "^Depends:.*[ ,]${dependency}([ ,(]|$)" "$PKG/DEBIAN/control"
done
cat > "$STAGE/refresh" <<'EOF'
if [ "$1" = configure ] || [ "$1" = remove ]; then
  command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
  command -v update-desktop-database >/dev/null && update-desktop-database -q /usr/share/applications || true
fi
EOF
for script in postinst postrm; do
  [ -f "$PKG/DEBIAN/$script" ] || printf '#!/bin/sh\nset -e\n' > "$PKG/DEBIAN/$script"
  sed -i '/^exit 0$/d' "$PKG/DEBIAN/$script"; cat "$STAGE/refresh" >> "$PKG/DEBIAN/$script"; echo "exit 0" >> "$PKG/DEBIAN/$script"
  chmod 755 "$PKG/DEBIAN/$script"
done
# Fail before producing an installable artifact if upstream maintainer templates
# no longer support this integration. Never install the unprocessed jpackage Deb.
for script in postinst prerm postrm; do
  if [ -f "$PKG/DEBIAN/$script" ]; then
    /bin/sh -n "$PKG/DEBIAN/$script"
    if grep -q 'xdg-desktop-menu' "$PKG/DEBIAN/$script"; then
      echo "unexpected launcher registration remains in $script" >&2; exit 1
    fi
  fi
done
for script in postinst postrm; do
  grep -q 'gtk-update-icon-cache' "$PKG/DEBIAN/$script"
  grep -q 'update-desktop-database' "$PKG/DEBIAN/$script"
done
grep -qx 'StartupWMClass=nozzle-it-all' "$PKG/usr/share/applications/nozzle-it-all.desktop"
for size in 16 32 48 64 128 256 512; do
  test -s "$PKG/usr/share/icons/hicolor/${size}x${size}/apps/nozzle-it-all.png"
done
DESKTOP_DEB="$OUT/$(basename "$SOURCE_DESKTOP_DEB")"
fakeroot dpkg-deb --build "$PKG" "$DESKTOP_DEB" >/dev/null
[ -s "$DESKTOP_DEB" ]

echo "== Stock U1 adapter"
PKG="$STAGE/stock"
mkdir -p "$PKG/DEBIAN" "$PKG/opt/nozzle-stock-u1-adapter"
cp -r "$ROOT/stock-u1-adapter/build/install/nozzle-stock-u1-adapter/lib" "$PKG/opt/nozzle-stock-u1-adapter/"
mkdir -p "$PKG/opt/nozzle-stock-u1-adapter/bin"
# Desktop's runtime (jpackage) has no java launcher, so the helper carries its own minimal runtime: the modules jdeps
# finds, plus elliptic-curve TLS for Snapmaker's HTTPS services.
MODULES="$(jdeps --multi-release 17 --ignore-missing-deps --print-module-deps --class-path "$ROOT/stock-u1-adapter/build/install/nozzle-stock-u1-adapter/lib/*" \
  "$ROOT"/stock-u1-adapter/build/install/nozzle-stock-u1-adapter/lib/stock-u1-adapter*.jar),jdk.crypto.ec"
jlink --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages --compress=2 --output "$PKG/opt/nozzle-stock-u1-adapter/runtime"
cat > "$PKG/opt/nozzle-stock-u1-adapter/bin/nozzle-stock-u1-adapter" <<'EOF'
#!/bin/sh
exec /opt/nozzle-stock-u1-adapter/runtime/bin/java -cp "/opt/nozzle-stock-u1-adapter/lib/*" com.nozzleitall.stocku1.StockU1HelperKt "$@"
EOF
chmod 755 "$PKG/opt/nozzle-stock-u1-adapter/bin/nozzle-stock-u1-adapter"
cat > "$PKG/DEBIAN/control" <<EOF
Package: nozzle-stock-u1-adapter
Version: $VERSION-1
Architecture: amd64
Maintainer: support@nozzleitall.com
Enhances: nozzle-it-all
Section: graphics
Priority: optional
Description: Optional Snapmaker U1 stock-firmware support for Nozzle It All
 Lets Nozzle It All for Desktop work with a Snapmaker U1 running stock firmware. It runs as a separate
 process, only when switched on in Settings, and is the only part of Nozzle It All that may contact
 Snapmaker's online services. Not needed for PAXX firmware or any other printer.
EOF
fakeroot dpkg-deb --build "$PKG" "$OUT/nozzle-stock-u1-adapter_${VERSION}-1_amd64.deb" >/dev/null


echo "== Provenance"
ADAPTERS="$(dpkg-deb -c "$DESKTOP_DEB" | grep -o 'adapter-[a-z]*' | sort -u | tr '\n' ' ')"
[ -n "$ADAPTERS" ] || { echo "no adapters found in intended desktop package" >&2; exit 1; }
(cd "$OUT" && sha256sum -- "$(basename "$DESKTOP_DEB")" "nozzle-stock-u1-adapter_${VERSION}-1_amd64.deb" > SHA256SUMS)
{
  echo "Nozzle It All Linux packages $VERSION"
  echo "built: $(date -u +%Y-%m-%dT%H:%M:%SZ) on $(uname -srm)"
  echo "source: $SOURCE_REVISION$SOURCE_STATE"
  [ -z "$SOURCE_ARCHIVE_SHA256" ] || echo "source archive sha256: $SOURCE_ARCHIVE_SHA256"
  echo "source Compose package: $(sha256sum "$SOURCE_DESKTOP_DEB")"
  echo "SHA256SUMS scope: only the two current-build outputs listed below; retained older artifacts are not covered or selected."
  echo "java: $(java -version 2>&1 | head -1); gradle: $("${GRADLE[@]}" -q --version 2>/dev/null | awk '/^Gradle/{print $2}')"
  echo "viewport compiler: $(g++ --version | head -1)"
  echo "viewport library: $(sha256sum "$ROOT/desktop/build/native/viewport/libnia_viewport.so")"
  echo "stock adapter runtime modules: $MODULES"
  echo "slicing engine: $ENGINE sha256 $(sha256sum "$ENGINE" | cut -d' ' -f1)"
  [ -f "$ROOT/engine/native/PROVENANCE.txt" ] && sed 's/^/  /' "$ROOT/engine/native/PROVENANCE.txt"
  echo "adapters in nozzle-it-all: $ADAPTERS"
  echo; cat "$OUT/SHA256SUMS"
} > "$OUT/PROVENANCE.txt"
cat "$OUT/PROVENANCE.txt"
