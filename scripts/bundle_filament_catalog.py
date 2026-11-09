#!/usr/bin/env python3
"""Build NIA's source-qualified filament product catalog.

The four source slicers describe the same real product many times: once per
printer/nozzle in Orca-family JSON, once per Prusa variant, or once per Cura
colour.  NIA presents one product row and retains every contributing preset in
``sources``.  Runtime slicing is deliberately not generated here; Desktop
derives the selected identity from the chosen machine's compatible base
material preset.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

try:
    import yaml
except ImportError as exc:  # generation is maintainer-only; the generated JSON is committed
    raise SystemExit("PyYAML is required to regenerate the filament catalog") from exc


SPACE = re.compile(r"\s+")
SUFFIX = re.compile(r"\s+@.+$")


class Reconciliation:
    """Every source file considered by a catalog adapter is accounted for."""

    def __init__(self):
        self.scanned = defaultdict(set)
        self.excluded = defaultdict(list)
        self.warnings = defaultdict(list)

    def scan(self, source: str, path: str):
        self.scanned[source].add(path)

    def exclude(self, source: str, path: str, reason: str):
        self.excluded[source].append({"path": path, "reason": reason})

    def warn(self, source: str, path: str, reason: str):
        self.warnings[source].append({"path": path, "reason": reason})


def clean(value: object) -> str:
    if isinstance(value, list):
        value = value[0] if value else ""
    return SPACE.sub(" ", str(value or "").strip())


def product_name(value: object) -> str:
    return SUFFIX.sub("", clean(value)).strip()


def material(value: object) -> str:
    raw = clean(value).upper().replace(" ", "-")
    aliases = {"FLEX": "TPU", "NYLON": "PA", "CPE": "PETG"}
    return aliases.get(raw, raw)


def base_material(value: str) -> str:
    raw = value.upper()
    for prefix, base in (("PETG", "PETG"), ("PCTG", "PCTG"), ("PLA", "PLA"), ("ABS", "ABS"),
                         ("ASA", "ASA"), ("TPU", "TPU"), ("TPE", "TPU"), ("PA", "PA"),
                         ("NYLON", "PA"), ("PC", "PC"), ("PP", "PP"), ("PVA", "PVA"),
                         ("BVOH", "BVOH"), ("HIPS", "HIPS"), ("PET", "PETG")):
        if raw.startswith(prefix):
            return base
    return raw


def color(value: object) -> str | None:
    raw = clean(value).upper()
    return raw if re.fullmatch(r"#[0-9A-F]{6}(?:[0-9A-F]{2})?", raw) else None


def stable_id(vendor: str, name: str, kind: str) -> str:
    payload = "\n".join((vendor.casefold(), name.casefold(), kind.casefold()))
    return "catalog-" + hashlib.sha256(payload.encode()).hexdigest()[:24]


def source_commit(root: Path) -> str:
    import subprocess
    return subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()


def add(rows, audit, vendor, name, kind, source, preset, relpath, colour=None):
    raw_vendor, raw_name, raw_kind = clean(vendor), product_name(name), material(kind)
    vendor, name, kind = raw_vendor or "Generic", raw_name, raw_kind
    if not name or not kind:
        audit.exclude(source, relpath, "missing product name" if not name else "missing material family")
        return False
    # A product is a vendor/name/material combination. Colours are runtime spool
    # properties, not separate slicing presets.
    key = (vendor.casefold(), name.casefold(), kind.casefold())
    row = rows.setdefault(key, {"vendor": vendor, "name": name, "material": kind,
                                "baseMaterial": base_material(kind), "colors": set(), "sources": [], "aliases": set()})
    row["aliases"].add((vendor, name, kind))
    c = color(colour)
    if c:
        row["colors"].add(c)
    ref = {"catalog": source, "preset": clean(preset), "path": relpath}
    if ref not in row["sources"]:
        row["sources"].append(ref)
    return True


def orca_family(rows, audit, profiles: Path, source: str, only_library: bool):
    vendors = [profiles / "OrcaFilamentLibrary"] if only_library else [p for p in profiles.iterdir() if p.is_dir()]
    for vendor_dir in sorted(vendors):
        filament = vendor_dir / "filament"
        if not filament.is_dir():
            continue
        parsed = []
        by_name = {}
        for path in sorted(filament.rglob("*.json")):
            relpath = str(path.relative_to(profiles))
            audit.scan(source, relpath)
            try:
                raw = json.loads(path.read_text())
            except (OSError, UnicodeError, json.JSONDecodeError):
                audit.exclude(source, relpath, "invalid JSON")
                continue
            if not isinstance(raw, dict):
                audit.exclude(source, relpath, "filament preset is not a JSON object")
                continue
            parsed.append((path, raw))
            by_name.setdefault(raw.get("name") or path.stem, raw)

        def flattened(name, seen=()):
            if not name or name in seen:
                return {}
            raw = by_name.get(name)
            if raw is None:
                return {}
            out = flattened(raw.get("inherits"), seen + (name,))
            out.update(raw)
            return out

        for path, raw in parsed:
            name = raw.get("name") or path.stem
            # Base templates are implementation details. System/instantiation
            # profiles are the user-visible list; inherited identity fields are
            # recoverable from the product name when absent.
            if "@base" in name or name.startswith("fdm_filament_"):
                audit.exclude(source, str(path.relative_to(profiles)), "non-selectable base template")
                continue
            inst = str(raw.get("instantiation", "true")).lower()
            if inst == "false" and "@System" not in name:
                audit.exclude(source, str(path.relative_to(profiles)), "non-instantiated template")
                continue
            resolved = flattened(name)
            inherited = raw.get("inherits")
            if inherited and inherited not in by_name:
                audit.warn(source, str(path.relative_to(profiles)), f"dangling inheritance: {inherited}")
            elif inherited:
                chain, parent = {name}, inherited
                while parent in by_name:
                    if parent in chain:
                        audit.warn(source, str(path.relative_to(profiles)), f"inheritance cycle: {parent}")
                        break
                    chain.add(parent)
                    parent = by_name[parent].get("inherits")
                    if not parent:
                        break
            kind = clean(resolved.get("filament_type"))
            vendor = clean(resolved.get("filament_vendor"))
            bare = product_name(name)
            if not vendor:
                # OrcaFilamentLibrary's first folder is authoritative brand
                # metadata even when an incomplete preset omits the field.
                relative = path.relative_to(filament)
                vendor = relative.parts[0] if len(relative.parts) > 1 else (bare.split(" ", 1)[0] if " " in bare else "Generic")
            if not kind:
                # Most inherited variants keep the material in their name.
                tokens = re.findall(r"(?:PLA|PETG|PCTG|ABS|ASA|TPU|TPE|PA\d*|NYLON|PC|PP|PVA|BVOH|HIPS|PEEK|PEI|PPS|PPA|PET)(?:[- ][A-Z0-9+]+)*", bare.upper())
                kind = tokens[-1] if tokens else ""
            add(rows, audit, vendor, bare, kind, source, name, str(path.relative_to(profiles)), resolved.get("default_filament_colour"))


def prusa(rows, audit, root: Path):
    base = root / "resources" / "presets"
    for path in sorted(base.rglob("preset-filament-*.yaml")):
        relpath = str(path.relative_to(root))
        audit.scan("prusa", relpath)
        try:
            # Current Prusa bundles may append auxiliary YAML documents.  The
            # first document is the product preset; later documents are shared
            # variant fragments rather than additional picker entries.
            raw = next(yaml.safe_load_all(path.read_text()))
        except (OSError, UnicodeError, yaml.YAMLError, StopIteration):
            audit.exclude("prusa", relpath, "invalid YAML")
            continue
        if not isinstance(raw, dict) or raw.get("kind") != "filament":
            audit.exclude("prusa", relpath, "not a filament product preset")
            continue
        values = raw.get("values") if isinstance(raw.get("values"), dict) else {}
        filename_material = path.name.removeprefix("preset-filament-").split("-", 1)[0]
        inferred_material = {"FLEX": "TPU", "PET": "PETG"}.get(filename_material.upper(), filename_material)
        add(rows, audit, values.get("filament_vendor"), raw.get("name") or raw.get("id"), values.get("filament_type") or inferred_material,
            "prusa", raw.get("id") or raw.get("name"), str(path.relative_to(root)), values.get("filament_colour"))


def cura(rows, audit, root: Path):
    ns = {"m": "http://www.ultimaker.com/material"}
    for path in sorted(root.rglob("*.fdm_material")):
        relpath = str(path.relative_to(root))
        audit.scan("cura", relpath)
        try:
            tree = ET.parse(path)
        except (OSError, UnicodeError, ET.ParseError):
            audit.exclude("cura", relpath, "invalid material XML")
            continue
        node = tree.getroot()
        vendor = node.findtext("m:metadata/m:name/m:brand", namespaces=ns)
        kind = node.findtext("m:metadata/m:name/m:material", namespaces=ns)
        label = node.findtext("m:metadata/m:name/m:label", namespaces=ns)
        colour_name = node.findtext("m:metadata/m:name/m:color", namespaces=ns)
        colour_code = node.findtext("m:metadata/m:color_code", namespaces=ns)
        guid = node.findtext("m:metadata/m:GUID", namespaces=ns)
        name = clean(label) or " ".join(filter(None, (clean(vendor), clean(kind))))
        add(rows, audit, vendor, name, kind, "cura", guid or path.stem, str(path.relative_to(root)), colour_code)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--orca", type=Path, required=True)
    ap.add_argument("--bambu", type=Path, required=True)
    ap.add_argument("--prusa", type=Path, required=True)
    ap.add_argument("--cura-materials", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    rows = {}
    audit = Reconciliation()
    # Orca's list is the union of every bundled vendor filament folder, not
    # only OrcaFilamentLibrary. Normalization consolidates overlap explicitly.
    orca_family(rows, audit, args.orca / "resources" / "profiles", "orca", False)
    orca_family(rows, audit, args.bambu / "resources" / "profiles", "bambu", False)
    prusa(rows, audit, args.prusa)
    cura(rows, audit, args.cura_materials)
    entries = []
    counts = defaultdict(int)
    for row in rows.values():
        row["id"] = stable_id(row["vendor"], row["name"], row["material"])
        row["colors"] = sorted(row["colors"])
        row["aliases"] = [dict(zip(("vendor", "name", "material"), alias)) for alias in sorted(row["aliases"], key=lambda x: tuple(v.casefold() for v in x))]
        row["sources"] = sorted(row["sources"], key=lambda x: (x["catalog"], x["preset"], x["path"]))
        for ref in row["sources"]:
            counts[ref["catalog"]] += 1
        entries.append(row)
    entries.sort(key=lambda x: (x["vendor"].casefold(), x["name"].casefold(), x["material"], x["id"]))
    ids = defaultdict(list)
    for row in entries:
        ids[row["id"]].append((row["vendor"], row["name"], row["material"]))
    collisions = {key: value for key, value in ids.items() if len(value) > 1}
    if collisions:
        raise SystemExit(f"stable id collision: {collisions}")
    accepted_paths = defaultdict(set)
    product_counts = defaultdict(set)
    for row in entries:
        for ref in row["sources"]:
            accepted_paths[ref["catalog"]].add(ref["path"])
            product_counts[ref["catalog"]].add(row["id"])
    reconciliation = {}
    for source in ("orca", "bambu", "prusa", "cura"):
        excluded = sorted(audit.excluded[source], key=lambda x: (x["path"], x["reason"]))
        warnings = sorted(audit.warnings[source], key=lambda x: (x["path"], x["reason"]))
        accounted = accepted_paths[source] | {item["path"] for item in excluded}
        missing = sorted(audit.scanned[source] - accounted)
        if missing:
            raise SystemExit(f"unaccounted {source} files: {missing[:10]}")
        reconciliation[source] = {
            "scannedFiles": len(audit.scanned[source]),
            "acceptedPresetFiles": len(accepted_paths[source]),
            "representedProducts": len(product_counts[source]),
            "excluded": excluded,
            "warnings": warnings,
        }
    payload = {
        "version": 1,
        "policy": "Product identities are normalized; source presets and colors remain provenance. Runtime tuning derives from the selected machine's compatible base material.",
        "catalogs": {
            "orca": {"repo": "https://github.com/SoftFever/OrcaSlicer.git", "commit": source_commit(args.orca), "license": "AGPL-3.0", "root": "resources/profiles"},
            "bambu": {"repo": "https://github.com/bambulab/BambuStudio.git", "commit": source_commit(args.bambu), "license": "AGPL-3.0", "root": "resources/profiles"},
            "prusa": {"repo": "https://github.com/prusa3d/PrusaSlicer.git", "commit": source_commit(args.prusa), "license": "AGPL-3.0", "root": "resources/presets"},
            "cura": {"repo": "https://github.com/Ultimaker/fdm_materials.git", "commit": source_commit(args.cura_materials), "license": "CC0-1.0", "root": "."},
        },
        "reconciliation": reconciliation,
        "entries": entries,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, indent=1, ensure_ascii=False) + "\n")
    print(f"{len(entries)} normalized products; {sum(len(x['sources']) for x in entries)} source presets; "
          f"{len({x['vendor'].casefold() for x in entries})} vendors")


if __name__ == "__main__":
    main()
