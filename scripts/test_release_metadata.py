"""Check release classification and reject invalid publishing inputs."""

import unittest

from release_metadata import validate


class ReleaseMetadataTest(unittest.TestCase):
    def metadata(self, version="0.1b", apk="app-release-unsigned.apk"):
        return {"elements": [{"versionName": version, "versionCode": 1, "outputFile": apk}]}

    def test_beta_versions(self):
        for version in ("0.1b", "0.2b2", "0.2.0-beta", "0.2.0-beta.1"):
            with self.subTest(version=version):
                self.assertEqual("true", validate(self.metadata(version), f"v{version}")["prerelease"])

    def test_stable_versions(self):
        for version in ("0.2", "1.0.0"):
            with self.subTest(version=version):
                result = validate(self.metadata(version), f"v{version}")
                self.assertEqual("false", result["prerelease"])
                self.assertEqual(f"rideology-companion-v{version}.apk", result["asset"])

    def test_mismatch_and_missing_prefix(self):
        for tag in ("v0.2b", "0.1b"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                validate(self.metadata(), tag)

    def test_unsupported_versions(self):
        for version in ("1.0-rc.1", "1.0\ninjected=true", "beta"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                validate(self.metadata(version), f"v{version}")

    def test_ambiguous_or_unsafe_apks(self):
        with self.assertRaises(ValueError):
            validate({"elements": []}, "v0.1b")
        for apk in ("../app.apk", "app.apk\ninjected=true"):
            with self.subTest(apk=apk), self.assertRaises(ValueError):
                validate(self.metadata(apk=apk), "v0.1b")

    def test_invalid_version_code(self):
        metadata = self.metadata()
        metadata["elements"][0]["versionCode"] = 0
        with self.assertRaises(ValueError):
            validate(metadata, "v0.1b")


if __name__ == "__main__":
    unittest.main()
