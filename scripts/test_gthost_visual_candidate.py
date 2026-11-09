import importlib.util
import json
from pathlib import Path
import tarfile
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("gthost_visual_candidate.py")
SPEC = importlib.util.spec_from_file_location("gthost_visual_candidate", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class VisualCandidateTest(unittest.TestCase):
    def test_scenarios_are_dark_and_cover_both_required_scales(self):
        scenarios = MODULE.load_scenarios()
        self.assertTrue(scenarios)
        self.assertEqual({1.0, 1.5}, {row["text_scale"] for row in scenarios.values()})
        self.assertEqual({"dark"}, {row["theme"] for row in scenarios.values()})

    def test_docker_contract_is_networkless_bounded_and_has_private_tmp(self):
        with tempfile.TemporaryDirectory() as root:
            command = MODULE.docker_base(MODULE.IMAGE_ID, Path(root))
        joined = " ".join(command)
        self.assertIn("-i", command)
        self.assertIn("--network none", joined)
        self.assertIn("--memory 4g", joined)
        self.assertIn("--cpus 4", joined)
        self.assertIn("--tmpfs /tmp:rw,nosuid,nodev,mode=1777,size=512m", joined)
        self.assertEqual(MODULE.IMAGE_ID, command[-1])

    def test_image_installer_is_the_single_package_source(self):
        installer = MODULE.IMAGE_INSTALLER.read_text(encoding="utf-8")
        dockerfile = MODULE.IMAGE_INSTALLER.with_name("Dockerfile").read_text(encoding="utf-8")
        self.assertIn("bubblewrap", installer)
        self.assertIn("openjdk-17-jre-headless", installer)
        self.assertIn("x11-apps", installer)
        self.assertIn("install-image-deps.sh", dockerfile)

    def test_runtime_jar_selection_excludes_compose_desktop_runtime(self):
        with tempfile.TemporaryDirectory() as root:
            app_dir = Path(root)
            project = app_dir / "desktop-31c305380522356d722332fd24e3f2.jar"
            compose_runtime = app_dir / "desktop-jvm-1.10.3-98023295d6f48188e5b98b192b9a30.jar"
            project.touch()
            compose_runtime.touch()
            self.assertEqual([project], MODULE.desktop_application_jars(app_dir))

    def test_action_interpreter_rejects_unknown_operations(self):
        with self.assertRaises(ValueError):
            MODULE.action_script([{"shell": "touch /host"}])

    def test_manifest_validation_rehashes_screenshot(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            shot = root / "shot.png"
            shot.write_bytes(b"visual")
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({
                "container_image_id": MODULE.IMAGE_ID,
                "results": {"screenshot": shot.name, "screenshot_sha256": MODULE.sha256(shot)},
            }))
            args = type("Args", (), {"manifest": manifest, "image": MODULE.IMAGE_ID})()
            MODULE.validate_manifest(args)

    def test_require_hash_rejects_mismatched_input(self):
        with tempfile.TemporaryDirectory() as root:
            artifact = Path(root) / "artifact"
            artifact.write_bytes(b"candidate")
            with self.assertRaises(SystemExit):
                MODULE.require_hash("Artifact", artifact, "0" * 64)

    def test_tar_owner_normalization_preserves_payload(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            payload = root / "payload"
            payload.write_bytes(b"image-data")
            source = root / "source.tar"
            with tarfile.open(source, "w") as archive:
                info = archive.gettarinfo(payload, arcname="usr/share/payload")
                info.uid = 100042
                info.gid = 100042
                with payload.open("rb") as stream:
                    archive.addfile(info, stream)
            destination = root / "normalized.tar"
            MODULE.normalize_tar_owners(source, destination)
            with tarfile.open(destination) as archive:
                info = archive.getmember("usr/share/payload")
                self.assertEqual((0, 0, "root", "root"), (info.uid, info.gid, info.uname, info.gname))
                self.assertEqual(b"image-data", archive.extractfile(info).read())


if __name__ == "__main__":
    unittest.main()
