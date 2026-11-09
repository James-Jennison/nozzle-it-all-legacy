#!/usr/bin/env python3
"""Protected, source-bound NIA Desktop visual evidence on gthost-build01.

The GTHost host needs only Docker. X11, capture, fonts, Java and software-GL
tools live in one immutable image. Candidate runtimes and projects are mounted
read-only; fresh NIA/XDG state, tmp and evidence output are the only writable
locations. Direct-host and improvised-container captures are not valid evidence.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import socket
import subprocess
import sys
import tarfile
import time
from typing import Any

BASE_IMAGE_ID = "sha256:98d0fed8d169104ba6abb0b1ed5045cdff9a070d2df08b30ce6e68622682d53c"
IMAGE_ID = "sha256:a7b7e622ea745b4c6d7748fefee031697a591074306169dd9e0884042e6dd4a9"
SCENARIOS = Path(__file__).resolve().parent / "visual" / "scenarios.json"
IMAGE_INSTALLER = Path(__file__).resolve().parent / "visual" / "install-image-deps.sh"
REQUIRED_TOOLS = (
    "Xvfb", "xdpyinfo", "xwd", "openbox", "xdotool", "convert", "bwrap",
    "java", "fc-list", "fc-match", "locale", "glxinfo", "sha256sum",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_hash(label: str, path: Path, expected: str) -> str:
    actual = sha256(path)
    if actual != expected:
        raise SystemExit(
            f"{label} SHA-256 mismatch: expected {expected}, observed {actual} at {path}."
        )
    return actual


def desktop_application_jars(app_dir: Path) -> list[Path]:
    """Return only the project JAR, excluding Compose's desktop-jvm runtime."""
    return sorted(
        path for path in app_dir.glob("desktop-*.jar")
        if re.fullmatch(r"desktop-[0-9a-f]{16,64}\.jar", path.name)
    )


def normalize_tar_owners(source: Path, destination: Path) -> None:
    """Normalize a userns-host Docker archive for import by a remapped daemon."""
    with tarfile.open(source, "r|*") as reader, tarfile.open(destination, "w") as writer:
        for member in reader:
            member.uid = 0
            member.gid = 0
            member.uname = "root"
            member.gname = "root"
            payload = reader.extractfile(member) if member.isfile() else None
            writer.addfile(member, payload)


def require_gthost() -> None:
    if socket.gethostname().split(".", 1)[0] != "gthost-build01":
        raise SystemExit("This protected visual workflow runs only on gthost-build01.")


def load_scenarios(path: Path = SCENARIOS) -> dict[str, dict[str, Any]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if document.get("schema") != 1 or not isinstance(document.get("scenarios"), dict):
        raise ValueError("Unsupported visual scenario schema.")
    scenarios = document["scenarios"]
    for name, row in scenarios.items():
        if not name or row.get("theme") != "dark":
            raise ValueError(f"Invalid scenario {name!r}.")
        geometry = row.get("geometry")
        if not (isinstance(geometry, list) and len(geometry) == 2 and all(isinstance(v, int) and v > 0 for v in geometry)):
            raise ValueError(f"Invalid geometry for {name}.")
        if row.get("text_scale") not in (1.0, 1.5):
            raise ValueError(f"Unsupported text scale for {name}.")
        if not isinstance(row.get("actions"), list):
            raise ValueError(f"Invalid action list for {name}.")
    return scenarios


def docker_base(image: str, output: Path | None = None) -> list[str]:
    command = [
        "docker", "run", "--rm", "-i", "--network", "none", "--memory", "4g",
        "--cpus", "4", "--userns=host", "--user", f"{os.getuid()}:{os.getgid()}",
        "--tmpfs", "/tmp:rw,nosuid,nodev,mode=1777,size=512m",
    ]
    if output is not None:
        command += ["-v", f"{output.resolve()}:/evidence:rw"]
    command += [image]
    return command


def verify_image(image: str) -> None:
    actual = subprocess.check_output(
        ["docker", "image", "inspect", image, "--format", "{{.Id}}"], text=True
    ).strip()
    if actual != image:
        raise SystemExit(f"Visual image mismatch: expected {image}, observed {actual}.")


def build_image(args: argparse.Namespace) -> None:
    """Assemble the visual image without relying on remapped BuildKit RUN mounts."""
    require_gthost()
    verify_image(BASE_IMAGE_ID)
    installer = IMAGE_INSTALLER.resolve()
    if not installer.is_file():
        raise SystemExit(f"Visual image installer is missing: {installer}")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    name = f"nia-visual-build-{os.getuid()}-{os.getpid()}"
    rootfs = output / f"{name}-rootfs.tar"
    raw_rootfs = output / f"{name}-raw-rootfs.tar"
    command = [
        "docker", "run", "--detach", "--name", name, "--userns=host", "--network", "host",
        "--user", "0:0", "-v", f"{installer}:/opt/nia/install-image-deps.sh:ro",
        BASE_IMAGE_ID, "sleep", "infinity",
    ]
    started = int(time.time())
    try:
        subprocess.run(command, check=True, stdout=subprocess.DEVNULL)
        subprocess.run([
            "docker", "exec", "--user", "0:0", name,
            "/bin/bash", "/opt/nia/install-image-deps.sh",
        ], check=True)
        # docker commit/export omit this daemon's --userns=host upper layer.
        # docker cp's archive API includes it, so capture the complete live rootfs.
        with raw_rootfs.open("wb") as stream:
            subprocess.run(["docker", "cp", f"{name}:/.", "-"], stdout=stream, check=True)
        normalize_tar_owners(raw_rootfs, rootfs)
        subprocess.run([
            "docker", "import",
            "--change", f"LABEL com.nozzleitall.visual.base={BASE_IMAGE_ID}",
            "--change", f"LABEL com.nozzleitall.visual.installer-sha256={sha256(installer)}",
            "--change", "ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 LIBGL_ALWAYS_SOFTWARE=1 __GLX_VENDOR_LIBRARY_NAME=mesa SKIKO_RENDER_API=SOFTWARE",
            str(rootfs), args.tag,
        ], check=True)
        derived = subprocess.check_output(
            ["docker", "image", "inspect", args.tag, "--format", "{{.Id}}"], text=True
        ).strip()
    finally:
        subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL, check=False)
        rootfs.unlink(missing_ok=True)
        raw_rootfs.unlink(missing_ok=True)
    receipt = {
        "schema": 1,
        "status": "PASS",
        "base_image_id": BASE_IMAGE_ID,
        "derived_image_id": derived,
        "tag": args.tag,
        "installer": str(IMAGE_INSTALLER.relative_to(Path(__file__).resolve().parent.parent)),
        "installer_sha256": sha256(installer),
        "assembly_network": "host (package installation only)",
        "capture_network": "none (enforced by capture/preflight commands)",
        "started_epoch": started,
        "finished_epoch": int(time.time()),
    }
    (output / "image-build.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2))


def preflight(args: argparse.Namespace) -> None:
    require_gthost()
    verify_image(args.image)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    script = r'''set -eEuo pipefail
trap 'status=$?; printf "preflight failed at line %s: %s (status %s)\n" "$LINENO" "$BASH_COMMAND" "$status" >&2; exit "$status"' ERR
for tool in Xvfb xdpyinfo xwd openbox xdotool convert bwrap java fc-list fc-match locale glxinfo sha256sum; do command -v "$tool" >/dev/null; done
test "$(find /sys/class/net -mindepth 1 -maxdepth 1 -printf '%f\n' | sort | tr '\n' ' ')" = "lo "
test ! -s /proc/net/route || ! awk '$2 == "00000000" && $1 != "lo" { found=1 } END { exit found ? 0 : 1 }' /proc/net/route
mkdir -p /evidence/preflight-profile/{config,data,cache,runtime,tmp}
test -w /evidence/preflight-profile/config
test -w /evidence/preflight-profile/cache
test -w /evidence/preflight-profile/runtime
test -w /evidence/preflight-profile/tmp
chmod 700 /evidence/preflight-profile/runtime
export HOME=/evidence/preflight-profile DISPLAY=:199 LANG=C.UTF-8 LC_ALL=C.UTF-8 LIBGL_ALWAYS_SOFTWARE=1 __GLX_VENDOR_LIBRARY_NAME=mesa SKIKO_RENDER_API=SOFTWARE
Xvfb "$DISPLAY" -screen 0 640x480x24 -nolisten tcp -ac >/evidence/preflight-xvfb.log 2>&1 & xvfb=$!
trap 'kill $message $openbox $xvfb 2>/dev/null || true' EXIT
for i in $(seq 1 100); do xdpyinfo -display "$DISPLAY" >/dev/null 2>&1 && break; sleep .05; done
xdpyinfo -display "$DISPLAY" >/dev/null
openbox >/evidence/preflight-openbox.log 2>&1 & openbox=$!
xmessage -title NiaVisualPreflight -geometry 320x120 'NIA visual preflight' >/evidence/preflight-window.log 2>&1 & message=$!
for i in $(seq 1 100); do window=$(xdotool search --name NiaVisualPreflight 2>/dev/null | tail -1 || true); test -n "$window" && break; sleep .05; done
test -n "${window:-}"
xdotool windowsize --sync "$window" 360 140
xdotool windowmove --sync "$window" 20 20
xwd -display "$DISPLAY" -root -silent -out /evidence/preflight.xwd
convert /evidence/preflight.xwd /evidence/preflight.png
rm -f /evidence/preflight.xwd
identify /evidence/preflight.png | grep -q '640x480'
glxinfo -B >/evidence/preflight-glx.txt
grep -Eiq 'llvmpipe|softpipe|software' /evidence/preflight-glx.txt
fc-match sans >/evidence/preflight-font.txt
locale -a | grep -Eiq '^(C\.utf8|C\.UTF-8|en_US\.utf8|en_US\.UTF-8)$'
bwrap --version >/evidence/preflight-bwrap.txt
java -version >/evidence/preflight-java.txt 2>&1
sha256sum /evidence/preflight.png
'''
    result = subprocess.run(docker_base(args.image, output) + ["/bin/bash", "-s"], input=script, text=True)
    if result.returncode:
        raise SystemExit(result.returncode)
    receipt = {
        "schema": 1,
        "status": "PASS",
        "image_id": args.image,
        "network": "none",
        "required_tools": list(REQUIRED_TOOLS),
        "screenshot_sha256": sha256(output / "preflight.png"),
    }
    (output / "preflight.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")


def action_script(actions: list[dict[str, Any]]) -> str:
    lines: list[str] = []
    for action in actions:
        if set(action) == {"click"}:
            x, y = action["click"]
            lines.append(f"xdotool mousemove --sync {int(x)} {int(y)} click 1")
        elif set(action) == {"sleep"}:
            lines.append(f"sleep {float(action['sleep']):g}")
        elif set(action) == {"key"}:
            lines.append("xdotool key " + shlex.quote(str(action["key"])))
        elif set(action) == {"type"}:
            lines.append("xdotool type -- " + shlex.quote(str(action["type"])))
        else:
            raise ValueError(f"Unsupported action: {action!r}")
    return "\n".join(lines)


def capture(args: argparse.Namespace) -> None:
    require_gthost()
    verify_image(args.image)
    scenarios = load_scenarios(args.scenarios)
    scenario = scenarios.get(args.scenario)
    if scenario is None:
        raise SystemExit(f"Unknown scenario {args.scenario!r}.")
    runtime = args.runtime.resolve()
    launcher = runtime / "bin" / "nozzle-it-all"
    if not launcher.is_file():
        raise SystemExit(f"Missing assembled runtime launcher: {launcher}")
    project = args.project.resolve()
    inputs = {
        "source_bundle": args.source_bundle.resolve(),
        "jar": args.jar.resolve(),
        "engine": args.engine.resolve(),
        "project": project,
    }
    if args.deb:
        inputs["deb"] = args.deb.resolve()
    for label, path in inputs.items():
        if not path.is_file():
            raise SystemExit(f"Missing {label}: {path}")
    observed_hashes = {
        "source_bundle": require_hash(
            "Source bundle", inputs["source_bundle"], args.source_bundle_sha256
        ),
        "jar": require_hash("Desktop JAR", inputs["jar"], args.jar_sha256),
        "engine": require_hash("Engine", inputs["engine"], args.engine_sha256),
    }
    if "deb" in inputs:
        if not args.deb_sha256:
            raise SystemExit("--deb-sha256 is required whenever --deb is supplied.")
        observed_hashes["deb"] = require_hash(
            "Desktop Deb", inputs["deb"], args.deb_sha256
        )
    elif args.deb_sha256:
        raise SystemExit("--deb is required whenever --deb-sha256 is supplied.")

    runtime_jars = desktop_application_jars(runtime / "lib" / "app")
    if len(runtime_jars) != 1:
        raise SystemExit(
            f"Assembled runtime must contain exactly one Desktop application JAR; found {runtime_jars}."
        )
    runtime_engine = runtime / "lib" / "app" / "resources" / "nozzle-engine"
    if not runtime_engine.is_file():
        raise SystemExit(f"Assembled runtime engine is missing: {runtime_engine}")
    require_hash("Assembled runtime Desktop JAR", runtime_jars[0], observed_hashes["jar"])
    require_hash("Assembled runtime engine", runtime_engine, observed_hashes["engine"])
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    width, height = scenario["geometry"]
    scale = scenario["text_scale"]
    settings = json.dumps({
        "stockU1Enabled": False,
        "theme": scenario["theme"],
        "notifications": True,
        "textScale": scale,
        "collapsedSettingGroups": [],
        "firstRunComplete": True,
        "rfidFilamentProfiles": {},
    }, separators=(",", ":"))
    actions = action_script(scenario["actions"])
    script = f'''set -euo pipefail
profile=/evidence/profile
mkdir -p "$profile"/{{config,data,cache,runtime,tmp}}
chmod 700 "$profile/runtime"
cat >"$profile/config/settings.json" <<'NIA_SETTINGS'
{settings}
NIA_SETTINGS
export HOME="$profile" NOZZLE_HOME="$profile" XDG_CACHE_HOME="$profile/cache" XDG_CONFIG_HOME="$profile/config" XDG_DATA_HOME="$profile/data" XDG_RUNTIME_DIR="$profile/runtime"
export TMPDIR="$profile/tmp" LANG=C.UTF-8 LC_ALL=C.UTF-8 LIBGL_ALWAYS_SOFTWARE=1 __GLX_VENDOR_LIBRARY_NAME=mesa SKIKO_RENDER_API=SOFTWARE DISPLAY=:197
Xvfb "$DISPLAY" -screen 0 {width}x{height}x24 -nolisten tcp -ac -extension GLX >/evidence/xvfb.log 2>&1 & xvfb=$!
app=""; openbox=""
cleanup() {{ test -z "$app" || kill "$app" 2>/dev/null || true; test -z "$openbox" || kill "$openbox" 2>/dev/null || true; kill "$xvfb" 2>/dev/null || true; }}
trap cleanup EXIT INT TERM
for i in $(seq 1 100); do xdpyinfo -display "$DISPLAY" >/dev/null 2>&1 && break; sleep .05; done
xdpyinfo -display "$DISPLAY" >/dev/null
openbox >/evidence/openbox.log 2>&1 & openbox=$!
/candidate/runtime/bin/nozzle-it-all /candidate/project.3mf >/evidence/app.log 2>&1 & app=$!
window=""
for i in $(seq 1 300); do window=$(xdotool search --name 'Nozzle It All' 2>/dev/null | tail -1 || true); test -n "$window" && break; kill -0 "$app" 2>/dev/null || break; sleep .1; done
test -n "$window"
xdotool windowsize --sync "$window" {width} {height}
xdotool windowmove --sync "$window" 0 0
sleep 8
{actions}
xdotool getwindowgeometry --shell "$window" >/evidence/window-geometry.txt
xwd -display "$DISPLAY" -root -silent -out /evidence/{args.scenario}.xwd
convert /evidence/{args.scenario}.xwd /evidence/{args.scenario}.png
rm -f /evidence/{args.scenario}.xwd
identify /evidence/{args.scenario}.png | grep -q '{width}x{height}'
kill "$app" 2>/dev/null || true
set +e; wait "$app"; app_status=$?; set -e; app=""
printf '%s\n' "$app_status" >/evidence/app-exit-status.txt
'''
    command = docker_base(args.image, output)
    command[command.index(args.image):command.index(args.image)] = [
        "-v", f"{runtime}:/candidate/runtime:ro",
        "-v", f"{project}:/candidate/project.3mf:ro",
    ]
    result = subprocess.run(command + ["/bin/bash", "-s"], input=script, text=True)
    if result.returncode:
        raise SystemExit(result.returncode)
    screenshot = output / f"{args.scenario}.png"
    manifest = {
        "schema": 1,
        "scenario": args.scenario,
        "scenario_definition_sha256": sha256(args.scenarios.resolve()),
        "source_revision": args.source_revision,
        "source_bundle_sha256": observed_hashes["source_bundle"],
        "jar_sha256": observed_hashes["jar"],
        "engine_sha256": observed_hashes["engine"],
        "deb_sha256": observed_hashes.get("deb"),
        "runtime_jar": str(runtime_jars[0].relative_to(runtime)),
        "runtime_engine": str(runtime_engine.relative_to(runtime)),
        "project_sha256": sha256(project),
        "container_image_id": args.image,
        "runner_revision": subprocess.check_output(
            ["git", "-C", str(Path(__file__).resolve().parent.parent), "rev-parse", "HEAD"], text=True
        ).strip(),
        "environment": {
            "geometry": f"{width}x{height}", "text_scale": scale,
            "theme": scenario["theme"], "locale": "C.UTF-8",
            "renderer": "SKIKO_RENDER_API=SOFTWARE; LIBGL_ALWAYS_SOFTWARE=1; Xvfb GLX disabled",
            "network": "none", "runtime_mount": "read-only", "project_mount": "read-only",
        },
        "results": {
            "screenshot": screenshot.name,
            "screenshot_sha256": sha256(screenshot),
            "app_log_sha256": sha256(output / "app.log"),
            "xvfb_log_sha256": sha256(output / "xvfb.log"),
            "window_geometry_sha256": sha256(output / "window-geometry.txt"),
            "app_exit_status": int((output / "app-exit-status.txt").read_text().strip()),
            "termination": "runner SIGTERM after evidence capture",
        },
        "evidence_limits": [
            "Synthetic Xvfb/software-rendering evidence; not Owner visual acceptance.",
            "No physical printer, Send action, installation, packaging, or release is exercised.",
            "Coordinate scenarios prove the captured state only; semantic behavior remains test-backed separately.",
        ],
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def validate_manifest(args: argparse.Namespace) -> None:
    document = json.loads(args.manifest.read_text(encoding="utf-8"))
    root = args.manifest.resolve().parent
    if document.get("container_image_id") != args.image:
        raise SystemExit("Manifest image identity mismatch.")
    results = document.get("results", {})
    screenshot = root / results.get("screenshot", "")
    if not screenshot.is_file() or sha256(screenshot) != results.get("screenshot_sha256"):
        raise SystemExit("Manifest screenshot hash mismatch.")
    print(json.dumps({"status": "PASS", "manifest": str(args.manifest.resolve())}))


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("--image", default=IMAGE_ID)
    sub = result.add_subparsers(dest="mode", required=True)
    image = sub.add_parser("build-image")
    image.add_argument("--tag", required=True)
    image.add_argument("--output", type=Path, required=True)
    image.set_defaults(run=build_image)
    pre = sub.add_parser("preflight")
    pre.add_argument("--output", type=Path, required=True)
    pre.set_defaults(run=preflight)
    cap = sub.add_parser("capture")
    cap.add_argument("--runtime", type=Path, required=True)
    cap.add_argument("--source-revision", required=True)
    cap.add_argument("--source-bundle", type=Path, required=True)
    cap.add_argument("--source-bundle-sha256", required=True)
    cap.add_argument("--jar", type=Path, required=True)
    cap.add_argument("--jar-sha256", required=True)
    cap.add_argument("--engine", type=Path, required=True)
    cap.add_argument("--engine-sha256", required=True)
    cap.add_argument("--deb", type=Path)
    cap.add_argument("--deb-sha256")
    cap.add_argument("--project", type=Path, required=True)
    cap.add_argument("--scenario", required=True)
    cap.add_argument("--scenarios", type=Path, default=SCENARIOS)
    cap.add_argument("--output", type=Path, required=True)
    cap.set_defaults(run=capture)
    man = sub.add_parser("manifest")
    man.add_argument("--manifest", type=Path, required=True)
    man.set_defaults(run=validate_manifest)
    return result


def main() -> None:
    args = parser().parse_args()
    if hasattr(args, "source_revision") and not __import__("re").fullmatch(r"[0-9a-f]{40}", args.source_revision):
        raise SystemExit("source-revision must be a full lowercase Git commit.")
    for name, value in vars(args).items():
        if name.endswith("_sha256") and value is not None and not __import__("re").fullmatch(r"[0-9a-f]{64}", value):
            raise SystemExit(f"{name.replace('_', '-')} must be a lowercase SHA-256 digest.")
    args.run(args)


if __name__ == "__main__":
    main()
