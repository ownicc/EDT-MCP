"""Check a release p2 repository before replacing the public update site."""
import pathlib
import lzma
import sys
import xml.etree.ElementTree as ET
import zipfile

FEATURE = 'com.ditrix.edt.mcp.server.feature'
PAYLOADS = (
    ('osgi.bundle', 'com.ditrix.edt.mcp.server', 'plugins'),
    ('org.eclipse.update.feature', FEATURE, 'features'),
)


def read_metadata(root, name):
    if (root / f'{name}.jar').is_file():
        with zipfile.ZipFile(root / f'{name}.jar') as archive:
            return ET.fromstring(archive.read(f'{name}.xml'))
    if (root / f'{name}.xml.xz').is_file():
        return ET.fromstring(lzma.decompress((root / f'{name}.xml.xz').read_bytes()))
    return ET.parse(root / f'{name}.xml').getroot()


def validate(root, version):
    content = read_metadata(root, 'content')
    artifacts = read_metadata(root, 'artifacts')
    for identifier in ('com.ditrix.edt.mcp.server', f'{FEATURE}.feature.group'):
        if content.find(f"./units/unit[@id='{identifier}'][@version='{version}']") is None:
            raise ValueError(f'Missing p2 unit {identifier} {version}')
    for classifier, identifier, directory in PAYLOADS:
        if artifacts.find(
            f"./artifacts/artifact[@classifier='{classifier}'][@id='{identifier}'][@version='{version}']"
        ) is None:
            raise ValueError(f'Missing p2 artifact {identifier} {version}')
        payload = root / directory / f'{identifier}_{version}.jar'
        with zipfile.ZipFile(payload) as archive:
            if archive.testzip() is not None:
                raise ValueError(f'Corrupt payload: {payload}')


if __name__ == '__main__':
    validate(pathlib.Path(sys.argv[1]), sys.argv[2])
    print(f'Validated p2 update site {sys.argv[2]}')
