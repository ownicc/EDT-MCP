import importlib.util
import pathlib
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location(
    'validate_update_site', pathlib.Path(__file__).with_name('validate-update-site.py'))
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class UpdateSiteTest(unittest.TestCase):
    def make_site(self, root, version='1.0.1'):
        units = []
        artifacts = []
        for classifier, identifier, directory in validator.PAYLOADS:
            unit = identifier if directory == 'plugins' else f'{identifier}.feature.jar'
            units.append(f'<unit id="{unit}" version="{version}"/>')
            artifacts.append(f'<artifact classifier="{classifier}" id="{identifier}" version="{version}"/>')
            payload = root / directory / f'{identifier}_{version}.jar'
            payload.parent.mkdir(exist_ok=True)
            with zipfile.ZipFile(payload, 'w') as archive:
                archive.writestr('payload', 'test data')
        units.append(f'<unit id="{validator.FEATURE}.feature.group" version="{version}"/>')
        for name, content in [('content', '<units>' + ''.join(units) + '</units>'),
                              ('artifacts', '<artifacts>' + ''.join(artifacts) + '</artifacts>')]:
            with zipfile.ZipFile(root / f'{name}.jar', 'w') as archive:
                archive.writestr(f'{name}.xml', '<repository>' + content + '</repository>')

    def test_complete_matching_site(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.make_site(root)
            validator.validate(root, '1.0.1')

    def test_empty_metadata_cannot_replace_site(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            for name in ['content', 'artifacts']:
                (root / f'{name}.xml').write_text('<repository/>')
            with self.assertRaises(ValueError):
                validator.validate(root, '1.0.1')

    def test_missing_payload_cannot_replace_site(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.make_site(root)
            next((root / 'plugins').glob('*.jar')).unlink()
            with self.assertRaises((ValueError, FileNotFoundError)):
                validator.validate(root, '1.0.1')

    def test_wrong_release_version_cannot_replace_site(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.make_site(root, '1.0.2')
            with self.assertRaises(ValueError):
                validator.validate(root, '1.0.1')

    def test_corrupt_payload_cannot_replace_site(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.make_site(root)
            next((root / 'features').glob('*.jar')).write_bytes(b'not a jar')
            with self.assertRaises(zipfile.BadZipFile):
                validator.validate(root, '1.0.1')


if __name__ == '__main__':
    unittest.main()
