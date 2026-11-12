"""Publish the validated p2 output built from a stable tag in the Forgejo mirror."""
import argparse
import html
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


SOURCE = 'https://forgejo.ownicc.ru/ownicc/EDT-MCP'
DESTINATION = 'https://forgejo.ownicc.ru/ownicc/EDT-MCP-update-site.git'
SITE_URL = 'https://forgejo.ownicc.ru/ownicc/EDT-MCP-update-site/raw/branch/master/'
METADATA = {'p2.index', *(name + suffix for name in ('content', 'artifacts')
                          for suffix in ('.jar', '.xml', '.xml.xz'))}
SPEC = importlib.util.spec_from_file_location(
    'validator', Path(__file__).with_name('validate-update-site.py'))
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)


def version_tuple(tag):
    if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+', tag):
        raise ValueError('Expected stable release tag vX.Y.Z')
    return tuple(map(int, tag[1:].split('.')))


def source_revision(tag, repository):
    version_tuple(tag)
    def revision(ref):
        return subprocess.check_output(
            ['git', '-C', str(repository), 'rev-parse', '--verify', ref], text=True).strip()
    tagged = revision(f'refs/tags/{tag}^{{commit}}')
    if revision('HEAD') != tagged:
        raise ValueError('The built checkout must be exactly the requested release tag')
    subprocess.run(['git', '-C', str(repository), 'merge-base', '--is-ancestor',
                    tagged, 'origin/master'], check=True)
    return tagged


def prepare_publication(site, repository, version, source_sha, source_url):
    validator.validate(site, version)
    for path in site.rglob('*'):
        parts = path.relative_to(site).parts
        if (path.is_symlink() or parts[0] not in METADATA | {'plugins', 'features'}
                or any(part.startswith('.') for part in parts)):
            raise ValueError('Unexpected path in local p2 output')
    marker = repository / 'release.json'
    if marker.exists():
        published = json.loads(marker.read_text())
        previous = published['version']
        if version_tuple('v' + version) < version_tuple('v' + previous):
            raise ValueError('Refusing to downgrade the stable update site')
        if version == previous:
            if published.get('source_sha', source_sha) != source_sha:
                raise ValueError('The published version belongs to a different source commit')
            return False
    # Keep older payloads: clients may already have read the previous metadata.
    for name in METADATA:
        (repository / name).unlink(missing_ok=True)
    shutil.copytree(site, repository, dirs_exist_ok=True)
    marker.write_text(json.dumps({'version': version, 'source_sha': source_sha,
                                 'source_url': source_url}, indent=2) + '\n')
    (repository / 'index.html').write_text(
        '<!doctype html><meta charset="utf-8"><title>EDT MCP update site</title>'
        f'<h1>EDT MCP {html.escape(version)}</h1><p>Add this URL in EDT:</p>'
        f'<pre>{SITE_URL}</pre><a href="{html.escape(source_url, quote=True)}">Source tag</a>\n')
    return True


def push_publication(repository):
    # A concurrent publication must fail, never replace newer remote history.
    subprocess.run(['git', '-C', str(repository), 'push', 'origin', 'HEAD:master'], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--site', required=True, type=Path)
    args = parser.parse_args()
    tag = args.tag
    source_sha = source_revision(tag, Path.cwd())
    version = tag[1:]
    site = args.site.resolve()
    validator.validate(site, version)
    if not os.environ.get('UPDATE_SITE_TOKEN'):
        raise ValueError('UPDATE_SITE_TOKEN is required')
    with tempfile.TemporaryDirectory(prefix='edt-p2-publish-') as directory:
        work = Path(directory)
        askpass = work/'askpass.sh'
        askpass.write_text('#!/bin/sh\ncase "$1" in *Username*) echo edt-mcp-publisher;; *) printf "%s\\n" "$UPDATE_SITE_TOKEN";; esac\n')
        askpass.chmod(0o700)
        os.environ.update(GIT_ASKPASS=str(askpass), GIT_TERMINAL_PROMPT='0')
        # Suppress ambient credential helpers; only this job's scoped token is used.
        os.environ.update(GIT_CONFIG_COUNT='1', GIT_CONFIG_KEY_0='credential.helper', GIT_CONFIG_VALUE_0='')
        repository = work/'repository'
        subprocess.run(['git', 'clone', '--quiet', DESTINATION, str(repository)], check=True)
        if not prepare_publication(site, repository, version, source_sha, f'{SOURCE}/src/tag/{tag}'):
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
