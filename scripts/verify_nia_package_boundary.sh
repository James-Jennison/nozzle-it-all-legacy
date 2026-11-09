#!/usr/bin/env bash
set -euo pipefail

verify_package() {
  local package_root="$1" engine="$2" pinned_sha="$3"
  [ -d "$package_root" ] || { echo "package root is missing" >&2; return 1; }
  [ "$(sha256sum "$engine" | cut -d' ' -f1)" = "$pinned_sha" ] || {
    echo "embedded engine does not match the pinned headless engine" >&2; return 1;
  }
  if find "$package_root" -type f -printf '%p\n' | grep -Eiq '/(OrcaSlicer|Snapmaker[-_ ]?Orca)(/|$)'; then
    echo "package contains an Orca application-shell file" >&2; return 1
  fi
  if find "$package_root" -type f -name '*.desktop' -exec grep -EHi '^(Name|Exec)=.*(OrcaSlicer|Snapmaker[ _-]?Orca)' {} + | grep -q .; then
    echo "package advertises an Orca application shell" >&2; return 1
  fi
  local candidate
  while IFS= read -r -d '' candidate; do
    [ "$candidate" = "$engine" ] && continue
    if file -b "$candidate" | grep -q '^ELF' && {
      readelf -d "$candidate" 2>/dev/null | grep -Eiq 'libwx(_|gtk)|wxWidgets' ||
      strings -a "$candidate" | grep -Eq '(^|[^A-Za-z])(wxEntry|wxApp|OrcaSlicer)([^A-Za-z]|$)';
    }; then
      echo "package contains wxWidgets/Orca application-shell content: $candidate" >&2; return 1
    fi
  done < <(find "$package_root" -type f -print0)
  local launcher="$package_root/usr/share/applications/nozzle-it-all.desktop"
  [ "$(find "$package_root/usr/share/applications" -maxdepth 1 -type f -name '*.desktop' | wc -l)" -eq 1 ] || {
    echo "package must contain only the canonical NIA desktop launcher" >&2; return 1;
  }
  grep -qx 'Name=Nozzle It All' "$launcher"
  grep -qx 'Exec=/opt/nozzle-it-all/bin/nozzle-it-all %F' "$launcher"
  grep -qx 'StartupWMClass=nozzle-it-all' "$launcher"
  local app_jars=() app_jar candidate_jar
  while IFS= read -r -d '' candidate_jar; do
    if unzip -Z1 "$candidate_jar" 2>/dev/null | grep -Fx 'com/nozzleitall/desktop/MainKt.class' >/dev/null; then
      app_jars+=("$candidate_jar")
    fi
  done < <(find "$package_root/opt/nozzle-it-all/lib/app" -maxdepth 1 -type f -name '*.jar' -print0)
  [ "${#app_jars[@]}" -eq 1 ] || { echo "package must contain exactly one NIA desktop application JAR" >&2; return 1; }
  app_jar="${app_jars[0]}"
  unzip -Z1 "$app_jar" | grep -Fx 'com/nozzleitall/desktop/MainKt.class' >/dev/null
}

if [ "${1:-}" = --self-test ]; then
  fixture="$(mktemp -d)"; trap 'rm -rf "$fixture"' EXIT
  mkdir -p "$fixture/opt/nozzle-it-all/lib/app/resources" "$fixture/opt/nozzle-it-all/lib/app" "$fixture/usr/share/applications"
  printf 'headless-engine' > "$fixture/opt/nozzle-it-all/lib/app/resources/nozzle-engine"
  engine="$fixture/opt/nozzle-it-all/lib/app/resources/nozzle-engine"
  sha="$(sha256sum "$engine" | cut -d' ' -f1)"
  mkdir -p "$fixture/classes/com/nozzleitall/desktop"
  : > "$fixture/classes/com/nozzleitall/desktop/MainKt.class"
  (cd "$fixture/classes" && zip -q "$fixture/opt/nozzle-it-all/lib/app/desktop-fixture.jar" \
    com/nozzleitall/desktop/MainKt.class)
  printf 'not the NIA application' > "$fixture/decoy.txt"
  (cd "$fixture" && zip -q "$fixture/opt/nozzle-it-all/lib/app/desktop-jvm-fixture.jar" decoy.txt)
  cat > "$fixture/usr/share/applications/nozzle-it-all.desktop" <<'EOF'
[Desktop Entry]
Name=Nozzle It All
Exec=/opt/nozzle-it-all/bin/nozzle-it-all %F
StartupWMClass=nozzle-it-all
EOF
  cp /bin/true "$fixture/opt/nozzle-it-all/lib/app/renamed-shell"
  printf '\nwxEntry\n' >> "$fixture/opt/nozzle-it-all/lib/app/renamed-shell"
  if verify_package "$fixture" "$engine" "$sha" 2>/dev/null; then
    echo "package boundary negative control unexpectedly passed" >&2; exit 1
  fi
  rm "$fixture/opt/nozzle-it-all/lib/app/renamed-shell"
  verify_package "$fixture" "$engine" "$sha"
  mkdir -p "$fixture/classes-two/com/nozzleitall/desktop"
  : > "$fixture/classes-two/com/nozzleitall/desktop/MainKt.class"
  printf 'distinct second application' > "$fixture/classes-two/second.txt"
  (cd "$fixture/classes-two" && zip -q "$fixture/opt/nozzle-it-all/lib/app/another-app.jar" \
    com/nozzleitall/desktop/MainKt.class second.txt)
  if verify_package "$fixture" "$engine" "$sha" 2>/dev/null; then
    echo "package boundary duplicate application control unexpectedly passed" >&2; exit 1
  fi
  exit 0
fi

[ "$#" -eq 3 ] || { echo "usage: $0 PACKAGE_ROOT EMBEDDED_ENGINE PINNED_SHA256" >&2; exit 2; }
verify_package "$1" "$2" "$3"
