import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch
import urllib.error

import test_validate_update_site as fixtures


SPEC = importlib.util.spec_from_file_location(
    'publisher', Path(__file__).with_name('publish-forgejo-update-site.py'))
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class PublicationTest(unittest.TestCase):
    def test_only_stable_release_with_expected_asset_is_accepted(self):
        release = {'tag_name': 'v1.0.6', 'draft': False, 'prerelease': False,
                   'assets': [{'name': 'MCP-EDT.v1.0.6.zip',
                               'browser_download_url': 'https://github.com/ownicc/EDT-MCP/releases/download/v1.0.6/MCP-EDT.v1.0.6.zip'}]}
        self.assertEqual(publisher.release_asset(release, 'v1.0.6')[0], '1.0.6')
        for key in ('draft', 'prerelease'):
            with self.subTest(key=key), self.assertRaises(ValueError):
                publisher.release_asset(dict(release, **{key: True}), 'v1.0.6')
        for tag in ('v1.0.5', 'v1.0.6-rc1', '../main'):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                publisher.release_asset(release, tag)
        with self.assertRaises(ValueError):
            publisher.release_asset(dict(release, assets=[]), 'v1.0.6')

    def test_archive_cannot_write_outside_site_or_into_git(self):
        for name in ('../escaped', '/absolute', '.git/config', 'plugins/../../escaped'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with zipfile.ZipFile(root/'input.zip', 'w') as archive:
                    archive.writestr(name, 'bad')
                with self.assertRaises(ValueError):
                    publisher.extract_site(root/'input.zip', root/'site')
                self.assertFalse((root/'escaped').exists())

    def test_symlink_in_archive_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entry = zipfile.ZipInfo('plugins/link.jar')
            entry.external_attr = 0o120777 << 16
            with zipfile.ZipFile(root/'input.zip', 'w') as archive:
                archive.writestr(entry, '/etc/passwd')
            with self.assertRaises(ValueError):
                publisher.extract_site(root/'input.zip', root/'site')

    def test_publication_keeps_previous_payloads_and_replaces_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); site = root/'site'; repo = root/'repo'
            site.mkdir(); repo.mkdir()
            fixtures.UpdateSiteTest().make_site(repo, '1.0.5')
            (repo/'content.xml').write_text('stale metadata')
            (repo/'release.json').write_text(json.dumps({'version': '1.0.5'}))
            fixtures.UpdateSiteTest().make_site(site, '1.0.6')
            self.assertTrue(publisher.prepare_publication(site, repo, '1.0.6', 'release-url'))
            publisher.validator.validate(repo, '1.0.6')
            self.assertTrue((repo/'plugins/com.ditrix.edt.mcp.server_1.0.5.jar').exists())
            self.assertFalse((repo/'content.xml').exists())
            self.assertEqual(json.loads((repo/'release.json').read_text())['version'], '1.0.6')
            self.assertFalse(publisher.prepare_publication(site, repo, '1.0.6', 'release-url'))

    def test_invalid_site_or_downgrade_cannot_replace_current_site(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); site = root/'site'; repo = root/'repo'
            site.mkdir(); repo.mkdir()
            fixtures.UpdateSiteTest().make_site(repo, '1.0.6')
            marker = json.dumps({'version': '1.0.6'}); (repo/'release.json').write_text(marker)
            before = (repo/'content.jar').read_bytes()
            fixtures.UpdateSiteTest().make_site(site, '1.0.5')
            with self.assertRaises(ValueError):
                publisher.prepare_publication(site, repo, '1.0.5', 'url')
            with self.assertRaises(ValueError):
                publisher.prepare_publication(site, repo, '1.0.7', 'url')
            self.assertEqual((repo/'release.json').read_text(), marker)
            self.assertEqual((repo/'content.jar').read_bytes(), before)

    def test_release_waits_for_asset_upload_after_release_creation(self):
        pending = {'tag_name': 'v1.0.6', 'draft': False, 'prerelease': False, 'assets': []}
        ready = dict(pending, assets=[{'name': 'MCP-EDT.v1.0.6.zip',
            'browser_download_url': 'https://github.com/ownicc/EDT-MCP/releases/download/v1.0.6/MCP-EDT.v1.0.6.zip'}])
        responses = [urllib.error.HTTPError('url', 404, 'not released', {}, None),
                     io.BytesIO(json.dumps(pending).encode()), io.BytesIO(json.dumps(ready).encode())]
        with patch.object(publisher.urllib.request, 'urlopen', side_effect=responses), \
                patch.object(publisher.time, 'sleep'):
            release = publisher.get_release('v1.0.6', 1800)
        self.assertEqual(release, ready)

    def test_git_push_refuses_to_overwrite_a_concurrent_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); remote = root/'remote.git'
            def git(*args):
                return subprocess.run(['git', *map(str, args)], check=True,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            git('init', '--bare', remote)
            for name in ('first', 'second'):
                git('clone', remote, root/name)
                git('-C', root/name, 'checkout', '-b', 'master')
                git('-C', root/name, 'config', 'user.name', 'test')
                git('-C', root/name, 'config', 'user.email', 'test@example.invalid')
                (root/name/'release.json').write_text(name)
                git('-C', root/name, 'add', '.')
                git('-C', root/name, 'commit', '-m', name)
            publisher.push_publication(root/'first')
            with self.assertRaises(subprocess.CalledProcessError):
                publisher.push_publication(root/'second')
