import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

import test_validate_update_site as fixtures


SPEC = importlib.util.spec_from_file_location(
    'publisher', Path(__file__).with_name('publish-forgejo-update-site.py'))
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class PublicationTest(unittest.TestCase):
    def git(self, repo, *args):
        return subprocess.check_output(['git', '-C', str(repo), *args], text=True,
                                       stderr=subprocess.PIPE).strip()

    def make_source(self, root):
        self.git(root, 'init', '-b', 'master')
        self.git(root, 'config', 'user.name', 'test')
        self.git(root, 'config', 'user.email', 'test@example.invalid')
        (root/'file').write_text('released source')
        self.git(root, 'add', '.')
        self.git(root, 'commit', '-m', 'release')
        self.git(root, 'tag', 'v1.0.6')
        self.git(root, 'update-ref', 'refs/remotes/origin/master', 'HEAD')
        return self.git(root, 'rev-parse', 'HEAD')

    def test_source_must_be_exact_stable_tag_in_master(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sha = self.make_source(root)
            self.assertEqual(publisher.source_revision('v1.0.6', root), sha)
            for tag in ('master', 'v1.0.6-rc1', '../master'):
                with self.subTest(tag=tag), self.assertRaises(ValueError):
                    publisher.source_revision(tag, root)
            (root/'file').write_text('newer master')
            self.git(root, 'commit', '-am', 'unreleased')
            with self.assertRaises(ValueError):
                publisher.source_revision('v1.0.6', root)

    def test_tag_outside_master_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.make_source(root)
            self.git(root, 'checkout', '-b', 'outside-master')
            (root/'file').write_text('unmerged')
            self.git(root, 'commit', '-am', 'unmerged')
            self.git(root, 'tag', 'v1.0.7')
            with self.assertRaises(subprocess.CalledProcessError):
                publisher.source_revision('v1.0.7', root)

    def test_publication_keeps_previous_payloads_and_replaces_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); site = root/'site'; repo = root/'repo'
            site.mkdir(); repo.mkdir()
            fixtures.UpdateSiteTest().make_site(repo, '1.0.5')
            (repo/'content.xml').write_text('stale metadata')
            (repo/'release.json').write_text(json.dumps({'version': '1.0.5'}))
            fixtures.UpdateSiteTest().make_site(site, '1.0.6')
            self.assertTrue(publisher.prepare_publication(site, repo, '1.0.6', '0'*40, 'forgejo-tag-url'))
            publisher.validator.validate(repo, '1.0.6')
            self.assertTrue((repo/'plugins/com.ditrix.edt.mcp.server_1.0.5.jar').exists())
            self.assertFalse((repo/'content.xml').exists())
            self.assertEqual(json.loads((repo/'release.json').read_text()), {
                'version': '1.0.6', 'source_sha': '0'*40, 'source_url': 'forgejo-tag-url'})
            self.assertFalse(publisher.prepare_publication(site, repo, '1.0.6', '0'*40, 'forgejo-tag-url'))
            with self.assertRaises(ValueError):
                publisher.prepare_publication(site, repo, '1.0.6', '1'*40, 'forgejo-tag-url')

    def test_invalid_site_or_downgrade_cannot_replace_current_site(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); site = root/'site'; repo = root/'repo'
            site.mkdir(); repo.mkdir()
            fixtures.UpdateSiteTest().make_site(repo, '1.0.6')
            marker = json.dumps({'version': '1.0.6'}); (repo/'release.json').write_text(marker)
            before = (repo/'content.jar').read_bytes()
            fixtures.UpdateSiteTest().make_site(site, '1.0.5')
            with self.assertRaises(ValueError):
                publisher.prepare_publication(site, repo, '1.0.5', '0'*40, 'url')
            with self.assertRaises(ValueError):
                publisher.prepare_publication(site, repo, '1.0.7', '0'*40, 'url')
            self.assertEqual((repo/'release.json').read_text(), marker)
            self.assertEqual((repo/'content.jar').read_bytes(), before)

    def test_existing_legacy_release_is_not_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); site = root/'site'; repo = root/'repo'
            site.mkdir(); repo.mkdir()
            fixtures.UpdateSiteTest().make_site(site, '1.0.6')
            marker = json.dumps({'version': '1.0.6', 'release_url': 'legacy-release'})
            (repo/'release.json').write_text(marker)
            self.assertFalse(publisher.prepare_publication(site, repo, '1.0.6', '0'*40, 'url'))
            self.assertEqual((repo/'release.json').read_text(), marker)

    def test_local_site_cannot_replace_git_config_or_follow_symlinks(self):
        for unsafe in ('git-config', 'symlink'):
            with self.subTest(unsafe=unsafe), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); site = root/'site'; repo = root/'repo'
                site.mkdir(); repo.mkdir(); fixtures.UpdateSiteTest().make_site(site, '1.0.6')
                if unsafe == 'git-config':
                    (site/'.git').mkdir(); (site/'.git/config').write_text('unsafe config')
                else:
                    (root/'private').write_text('not a p2 payload')
                    (site/'plugins/leak.jar').symlink_to(root/'private')
                with self.assertRaises(ValueError):
                    publisher.prepare_publication(site, repo, '1.0.6', '0'*40, 'url')
                self.assertEqual(list(repo.iterdir()), [])

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
