"""Publish an already-built stable release as an anonymous Forgejo p2 repository."""
import argparse
import html
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import zipfile


SOURCE = 'ownicc/EDT-MCP'
DESTINATION = 'https://forgejo.ownicc.ru/ownicc/EDT-MCP-update-site.git'
SITE_URL = 'https://forgejo.ownicc.ru/ownicc/EDT-MCP-update-site/raw/branch/master/'
METADATA = {'p2.index', *(name + suffix for name in ('content', 'artifacts')
                          for suffix in ('.jar', '.xml', '.xml.xz'))}
SPEC = importlib.util.spec_from_file_location(
    'validator', Path(__file__).with_name('validate-update-site.py'))
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)


class ReleasePending(ValueError):
    """A release exists but its p2 upload has not completed yet."""


def version_tuple(tag):
    if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+', tag):
        raise ValueError('Expected stable release tag vX.Y.Z')
    return tuple(map(int, tag[1:].split('.')))


def release_asset(release, tag):
    version_tuple(tag)
    if release['tag_name'] != tag or release['draft'] or release['prerelease']:
        raise ValueError('Release must be published, stable and match the requested tag')
    name = f'MCP-EDT.{tag}.zip'
    expected_url = f'https://github.com/{SOURCE}/releases/download/{tag}/{name}'
    for asset in release['assets']:
        if asset['name'] == name and asset['browser_download_url'] == expected_url:
            return tag[1:], expected_url
    raise ReleasePending('Stable release p2 archive is missing')


def extract_site(archive_path, destination):
    with zipfile.ZipFile(archive_path) as archive:
        for entry in archive.infolist():
            path = PurePosixPath(entry.filename)
            if (path.is_absolute() or '..' in path.parts or '\\' in entry.filename
                    or not path.parts or path.parts[0] not in METADATA | {'plugins', 'features'}
                    or any(part.startswith('.') for part in path.parts)
                    or stat.S_ISLNK(entry.external_attr >> 16)):
                raise ValueError('Unexpected path in p2 archive')
        archive.extractall(destination)


def prepare_publication(site, repository, version, release_url):
    validator.validate(site, version)
    marker = repository / 'release.json'
    if marker.exists():
        previous = json.loads(marker.read_text())['version']
        if version_tuple('v' + version) < version_tuple('v' + previous):
            raise ValueError('Refusing to downgrade the stable update site')
        if version == previous:
            return False
    # Keep older payloads: clients may already have read the previous metadata.
    for name in METADATA:
        (repository / name).unlink(missing_ok=True)
    shutil.copytree(site, repository, dirs_exist_ok=True)
    marker.write_text(json.dumps({'version': version, 'release_url': release_url}, indent=2) + '\n')
    (repository / 'index.html').write_text(
        '<!doctype html><meta charset="utf-8"><title>EDT MCP update site</title>'
        f'<h1>EDT MCP {html.escape(version)}</h1><p>Add this URL in EDT:</p>'
        f'<pre>{SITE_URL}</pre><a href="{html.escape(release_url, quote=True)}">Release</a>\n')
    return True


def push_publication(repository):
    # A concurrent publication must fail, never replace newer remote history.
    subprocess.run(['git', '-C', str(repository), 'push', 'origin', 'HEAD:master'], check=True)


def get_release(tag, wait_seconds):
    if tag:
        version_tuple(tag)
    endpoint = f'tags/{tag}' if tag else 'latest'
    deadline = time.monotonic() + wait_seconds
    while True:
        try:
            request = urllib.request.Request(
                f'https://api.github.com/repos/{SOURCE}/releases/{endpoint}',
                headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'EDT-MCP-p2-publisher'})
            with urllib.request.urlopen(request, timeout=30) as response:
                release = json.load(response)
            release_asset(release, tag or release['tag_name'])
            return release
        except (urllib.error.HTTPError, ReleasePending) as error:
            if ((isinstance(error, urllib.error.HTTPError) and error.code != 404)
                    or time.monotonic() >= deadline):
                raise
            print('Waiting for the stable release to finish publishing...', flush=True)
            time.sleep(min(60, max(0, deadline - time.monotonic())))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', default='')
    parser.add_argument('--wait-seconds', type=int, default=1800)
    args = parser.parse_args()
    release = get_release(args.tag, args.wait_seconds)
    tag = args.tag or release['tag_name']
    version, asset_url = release_asset(release, tag)
    # GitHub Releases are canonical, but the corresponding source must exist in the mirror.
    subprocess.run(['git', 'merge-base', '--is-ancestor', f'{tag}^{{commit}}', 'origin/master'], check=True)
    if not os.environ.get('UPDATE_SITE_TOKEN'):
        raise ValueError('UPDATE_SITE_TOKEN is required')
    with tempfile.TemporaryDirectory(prefix='edt-p2-publish-') as directory:
        work = Path(directory)
        request = urllib.request.Request(asset_url, headers={'User-Agent': 'EDT-MCP-p2-publisher'})
        with urllib.request.urlopen(request, timeout=120) as response, (work/'release.zip').open('wb') as output:
            shutil.copyfileobj(response, output)
        extract_site(work/'release.zip', work/'site')
        validator.validate(work/'site', version)
        askpass = work/'askpass.sh'
        askpass.write_text('#!/bin/sh\ncase "$1" in *Username*) echo edt-mcp-publisher;; *) printf "%s\\n" "$UPDATE_SITE_TOKEN";; esac\n')
        askpass.chmod(0o700)
        os.environ.update(GIT_ASKPASS=str(askpass), GIT_TERMINAL_PROMPT='0')
        # Suppress ambient credential helpers; only this job's scoped token is used.
        os.environ.update(GIT_CONFIG_COUNT='1', GIT_CONFIG_KEY_0='credential.helper', GIT_CONFIG_VALUE_0='')
        repository = work/'repository'
        subprocess.run(['git', 'clone', '--quiet', DESTINATION, str(repository)], check=True)
        if not prepare_publication(work/'site', repository, version, release['html_url']):
            print(f'Update site already publishes {version}; no changes')
            return
        for key, value in [('user.name', 'EDT MCP p2 publisher'),
                           ('user.email', 'edt-mcp-publisher@users.noreply.forgejo.ownicc.ru')]:
            subprocess.run(['git', '-C', str(repository), 'config', key, value], check=True)
        subprocess.run(['git', '-C', str(repository), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(repository), 'commit', '--quiet', '-m', f'Publish EDT MCP {tag}'], check=True)
        push_publication(repository)
        print(f'Published {tag}: {SITE_URL}')


if __name__ == '__main__':
    main()
