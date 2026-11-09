import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("bundle_filament_catalog.py")
SPEC = importlib.util.spec_from_file_location("bundle_filament_catalog", SCRIPT)
catalog = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(catalog)


class BundleFilamentCatalogTest(unittest.TestCase):
    def test_case_and_whitespace_variants_merge_with_explicit_aliases(self):
        rows, audit = {}, catalog.Reconciliation()
        audit.scan("orca", "one.json")
        audit.scan("orca", "two.json")
        self.assertTrue(catalog.add(rows, audit, "Acme", "Acme PLA", "PLA", "orca", "one", "one.json"))
        self.assertTrue(catalog.add(rows, audit, " acme ", "  ACME   PLA ", "pla", "orca", "two", "two.json"))
        self.assertEqual(1, len(rows))
        row = next(iter(rows.values()))
        self.assertEqual(2, len(row["aliases"]))
        self.assertEqual({"one.json", "two.json"}, {ref["path"] for ref in row["sources"]})

    def test_missing_identity_is_explicitly_excluded(self):
        rows, audit = {}, catalog.Reconciliation()
        audit.scan("cura", "missing.fdm_material")
        self.assertFalse(catalog.add(rows, audit, "Acme", "", "PLA", "cura", "missing", "missing.fdm_material"))
        self.assertEqual([], list(rows))
        self.assertEqual([{"path": "missing.fdm_material", "reason": "missing product name"}], audit.excluded["cura"])

    def test_orca_cycles_dangling_parents_and_invalid_json_are_accounted(self):
        with tempfile.TemporaryDirectory() as root:
            profiles = Path(root) / "profiles"
            filament = profiles / "Vendor" / "filament"
            filament.mkdir(parents=True)
            fixtures = {
                "cycle-a.json": {"name": "Acme PLA A", "inherits": "Acme PLA B", "filament_vendor": ["Acme"], "filament_type": ["PLA"]},
                "cycle-b.json": {"name": "Acme PLA B", "inherits": "Acme PLA A", "filament_vendor": ["Acme"], "filament_type": ["PLA"]},
                "dangling.json": {"name": "Acme PETG", "inherits": "missing-parent", "filament_vendor": ["Acme"], "filament_type": ["PETG"]},
                "missing-material.json": {"name": "Mystery", "filament_vendor": ["Acme"]},
                "template.json": {"name": "fdm_filament_template", "filament_type": ["PLA"]},
            }
            for name, value in fixtures.items():
                (filament / name).write_text(json.dumps(value))
            (filament / "invalid.json").write_text("{")

            first_rows, first_audit = {}, catalog.Reconciliation()
            catalog.orca_family(first_rows, first_audit, profiles, "orca", False)
            second_rows, second_audit = {}, catalog.Reconciliation()
            catalog.orca_family(second_rows, second_audit, profiles, "orca", False)

            self.assertEqual(first_rows, second_rows)
            self.assertEqual(6, len(first_audit.scanned["orca"]))
            self.assertEqual(3, len(first_rows))
            self.assertEqual(
                {"invalid JSON", "missing material family", "non-selectable base template"},
                {item["reason"] for item in first_audit.excluded["orca"]},
            )
            reasons = {item["reason"].split(":", 1)[0] for item in first_audit.warnings["orca"]}
            self.assertEqual({"dangling inheritance", "inheritance cycle"}, reasons)

    def test_stable_id_has_fixed_regression_vector(self):
        self.assertEqual("catalog-bb784f93421fb199d8de3c8e", catalog.stable_id("Acme", "Acme PLA", "PLA"))
        self.assertEqual(catalog.stable_id("Acme", "Acme PLA", "PLA"), catalog.stable_id("ACME", "ACME PLA", "pla"))


if __name__ == "__main__":
    unittest.main()
