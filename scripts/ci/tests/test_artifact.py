import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from artifact import config


class ArtifactVersionTests(unittest.TestCase):
    def test_stable_and_beta_versions_come_from_build_configuration(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            for version in ('0.0.2', '0.1.1-beta'):
                with self.subTest(version=version):
                    (root / 'build.gradle').write_text(
                        "version = '" + version + "'\n"
                        "minecraft 'net.minecraftforge:forge:1.12.2-14.23.5.2859'\n")
                    self.assertEqual(version, config(root))
