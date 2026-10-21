import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "prune_dev_releases", Path(__file__).resolve().parents[1] / "scripts/prune_dev_releases.py"
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RetentionTests(unittest.TestCase):
    def test_preserves_non_dev_releases_drafts_and_latest_three_numbers(self):
        def release(tag, prerelease=True, draft=False):
            return dict(tag_name=tag, prerelease=prerelease, draft=draft)
        releases = [release(f"dev-{n}") for n in [2, 11, 1, 9, 10]] + [
            release("v1.6.1", prerelease=False), release("v1.0.0"),
            release("dev-100", draft=True), release("dev-99", prerelease=False),
            release("dev-preview"), release("dev-0"),
        ]
        self.assertEqual(module.releases_to_delete(releases), ["dev-2", "dev-1"])

    def test_three_or_fewer_are_kept(self):
        self.assertEqual(module.releases_to_delete([]), [])
        self.assertEqual(module.releases_to_delete([
            dict(tag_name=f"dev-{n}", prerelease=True, draft=False) for n in [3, 4, 5]
        ]), [])
