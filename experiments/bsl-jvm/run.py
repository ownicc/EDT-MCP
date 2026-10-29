#!/usr/bin/env python3
"""Throwaway JVM comparison; creates a separate Equinox configuration/workspace.

Python/Maven are experiment orchestration only. The compared Java engines run
inside EDT's existing JVM stack; no source project is imported or changed.
"""
import argparse
import hashlib
import html
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
from urllib.parse import unquote, urlparse
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def digest(path):
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()


def run(command, log, **kwargs):
    timeout = kwargs.pop('timeout')
    usage = {}
    with log.open('w') as stream:
        process = subprocess.Popen(list(map(str, command)), stdout=stream,
                                   stderr=subprocess.STDOUT, start_new_session=(os.name == 'posix'), **kwargs)
        deadline = time.monotonic() + timeout
        try:
            while process.poll() is None:
                status = Path(f'/proc/{process.pid}/status')
                try:
                    for line in status.read_text().splitlines():
                        if line.startswith('VmHWM:'):
                            usage['sampledRssHighWaterKiB'] = max(usage.get('sampledRssHighWaterKiB', 0), int(line.split()[1]))
                except FileNotFoundError:
                    pass  # Optional Linux footprint measurement only.
                remaining = deadline - time.monotonic()
                if remaining <= 0: raise subprocess.TimeoutExpired(command, timeout)
                try: process.wait(timeout=min(0.1, remaining))
                except subprocess.TimeoutExpired: pass
            if process.returncode: raise subprocess.CalledProcessError(process.returncode, command)
        finally:
            if process.poll() is None:
                if os.name == 'posix': os.killpg(process.pid, signal.SIGKILL)
                else: process.kill()
                process.wait()
    return usage


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--edt', required=True, type=Path)
    ap.add_argument('--configuration', required=True, type=Path)
    ap.add_argument('--java-home', required=True, type=Path)
    ap.add_argument('--maven', required=True, type=Path)
    ap.add_argument('--difft', required=True, type=Path)
    ap.add_argument('--corpus', type=Path)
    ap.add_argument('--output', required=True, type=Path)
    ap.add_argument('--profile', action='store_true', help='Also measure parser/JGit/GumTree in separate JVMs')
    args = ap.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    # Preserve old prototype artifacts; verify again after this experiment.
    originals = {str(args.difft.resolve()): digest(args.difft)}
    rows = []
    for line in (args.configuration / 'org.eclipse.equinox.simpleconfigurator/bundles.info').read_text().splitlines():
        if line and not line.startswith('#'):
            name, version, url, level, auto = line.split(',')
            path = Path(unquote(urlparse(url).path))
            if not path.is_absolute():
                raise ValueError('Expected absolute bundle URL: ' + url)
            rows.append((name, version, path, level, auto))
            if name == 'com.ditrix.edt.mcp.server': originals[str(path)] = digest(path)
    by_name = {name: path for name, _, path, _, _ in rows}
    env = dict(os.environ, JAVA_HOME=str(args.java_home), EDT_MCP_AUTO_START='false')
    libs = out / 'lib'
    run([args.maven, '-B', '-f', HERE / 'pom.xml',
         'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies',
         '-DincludeScope=runtime', f'-DoutputDirectory={libs}'], out / 'dependencies.log',
        env=env, timeout=180)
    dependencies = [{'name': p.name, 'bytes': p.stat().st_size, 'sha256': digest(p)}
                    for p in sorted(libs.glob('*.jar'))]
    classes = out / 'classes'
    classes.mkdir()
    classpath = os.pathsep.join(map(str, [p for _, _, p, _, _ in rows if p.suffix == '.jar']
                                   + sorted(libs.glob('*.jar'))))
    run([args.java_home / 'bin/javac', '-proc:none', '-encoding', 'UTF-8',
         '-cp', classpath, '-d', classes, HERE / 'Probe.java'], out / 'compile.log', timeout=90)
    jar = out / 'probe.jar'
    manifest = ('Manifest-Version: 1.0\nBundle-ManifestVersion: 2\n'
                'Bundle-SymbolicName: experiment.bsljvm;singleton:=true\n'
                'Bundle-Version: 0.0.1\nBundle-RequiredExecutionEnvironment: JavaSE-17\n'
                'Require-Bundle: org.eclipse.core.runtime,org.eclipse.equinox.app,\n'
                ' org.eclipse.xtext,org.eclipse.emf.ecore,org.eclipse.jgit\n'
                'Bundle-ClassPath: .,\n ' + ',\n '.join('lib/' + d['name'] for d in dependencies) + '\n\n')
    with zipfile.ZipFile(jar, 'w') as z:
        z.writestr('META-INF/MANIFEST.MF', manifest)
        z.writestr('plugin.xml', '<plugin><extension point="org.eclipse.core.runtime.applications" '
                   'id="run"><application><run class="experiment.Probe"/></application></extension></plugin>')
        for p in classes.rglob('*.class'): z.write(p, str(p.relative_to(classes)))
        for p in libs.glob('*.jar'): z.write(p, 'lib/' + p.name)
    config = out / 'configuration'
    (config / 'org.eclipse.equinox.simpleconfigurator').mkdir(parents=True)
    bundle_lines = [f'{n},{v},{p.as_uri()},{l},false' for n, v, p, l, _ in rows]
    bundle_lines.append(f'experiment.bsljvm,0.0.1,{jar.as_uri()},4,false')
    (config / 'org.eclipse.equinox.simpleconfigurator/bundles.info').write_text('\n'.join(bundle_lines) + '\n')
    (config / 'config.ini').write_text(
        'osgi.bundles=reference:' + by_name['org.eclipse.equinox.simpleconfigurator'].as_uri() + '@1:start\n'
        'osgi.bundles.defaultStartLevel=4\nosgi.framework=' + by_name['org.eclipse.osgi'].as_uri() + '\n'
        'org.eclipse.equinox.simpleconfigurator.configUrl=file:org.eclipse.equinox.simpleconfigurator/bundles.info\n')
    spec = importlib.util.spec_from_file_location('difft_experiment', HERE.parent / 'bsl-difftastic/run.py')
    fixtures = importlib.util.module_from_spec(spec)
    sys.dont_write_bytecode = True
    spec.loader.exec_module(fixtures)
    cases = [dict(id=n, title=t, before=a, after=b, expected=e) for n, t, a, b, e in fixtures.CASES]
    cases += [dict(id='repeat-edit', title='Повторяющиеся вызовы',
                   before=fixtures.module('    Сообщить(1);\n' * 30),
                   after=fixtures.module('    Сообщить(1);\n' * 15 + '    Сообщить(2);\n' + '    Сообщить(1);\n' * 14), expected='changed'),
              dict(id='move-edit', title='Перестановка с изменением',
                   before=fixtures.METHOD_A + fixtures.METHOD_B,
                   after=fixtures.METHOD_B + fixtures.METHOD_A.replace('"A"', '"C"'), expected='changed')]
    if args.corpus:
        for p in sorted(args.corpus.rglob('*.bsl')):
            originals[str(p)] = digest(p)
            text = p.read_bytes().decode('utf-8')
            cases.append(dict(id='corpus/' + str(p.relative_to(args.corpus)), title=p.parent.name,
                              before=text, after='\n' + text, expected='unchanged'))
    body = '\n'.join(fixtures.module(f'    Сообщить({i});', f'Метод{i}') for i in range(1000))
    cases.append(dict(id='large', title='1000 процедур', before=body,
                      after=body.replace('Сообщить(500)', 'Сообщить(1500)'), expected='changed'))
    (out / 'input.json').write_text(json.dumps(cases, ensure_ascii=False))
    (out / 'baseline.json').write_text(json.dumps(dict(sourceCommit='130e39a5767fd67442a3a7cdf4b2f1f4dacfb01c',
        artifacts=originals, dependencies=dependencies), indent=2))
    java_command = [args.java_home / 'bin/java', '-Xmx512m',
         f'-Dprobe.input={out / "input.json"}', f'-Dprobe.output={out / "java.json"}',
         '-jar', by_name['org.eclipse.equinox.launcher'],
         '-install', args.edt, '-configuration', config, '-data', out / 'workspace',
         '-application', 'experiment.bsljvm.run', '-nosplash', '-consolelog']
    usage = run(java_command, out / 'equinox.log', env=env, timeout=240)
    profiles = {}
    if args.profile:
        for engine in ['parse-only', 'jgit', 'gumtree']:
            profile_command = [str(arg) for arg in java_command]
            profile_command[profile_command.index(f'-Dprobe.output={out / "java.json"}')] = f'-Dprobe.output={out / (engine + ".json")}'
            profile_command.insert(2, '-Dprobe.engines=' + ('' if engine == 'parse-only' else engine))
            profiles[engine] = run(profile_command, out / (engine + '.log'), env=env, timeout=240)
            profiles[engine]['memory'] = json.loads((out / (engine + '.json.memory.json')).read_text())
    java = {r['id']: r for r in json.loads((out / 'java.json').read_text())}
    results = []
    for case in cases:
        folder = out / 'cases' / case['id']
        folder.mkdir(parents=True)
        for key in ['before', 'after']: (folder / (key + '.bsl')).write_bytes(case[key].encode())
        raw, ms = fixtures.compare(args.difft, folder / 'before.bsl', folder / 'after.bsl')
        fixtures.validate_ranges(raw, dict(lhs=case['before'], rhs=case['after']))
        (folder / 'difftastic.json').write_text(json.dumps(raw, ensure_ascii=False, indent=2))
        row = java[case['id']]
        row.update(expected=case['expected'], difftastic=dict(language=raw['language'], status=raw['status'],
                   medianProcessMs=ms, changedSpans=len(fixtures.tokens(raw))))
        for engine in ['jgit', 'gumtree']:
            value = row[engine]
            if 'error' in value: continue
            for side, key in [('left', 'before'), ('right', 'after')]:
                data = case[key].encode('utf-16-le')
                for span in value[side]:
                    assert data[span['start']*2:(span['start']+span['length'])*2].decode('utf-16-le') == span['content']
            value['expectedStatusMatches'] = value['changed'] == (case['expected'] not in ['unchanged'])
        results.append(row)
    for path, expected in originals.items(): assert digest(Path(path)) == expected, path
    report = dict(dependencies=dependencies, cases=results, preservedArtifacts=originals, javaProcess=usage,
                  profiles=profiles,
                  sources={str(p.relative_to(ROOT)): digest(p) for p in [HERE / 'Probe.java', HERE / 'run.py', HERE / 'pom.xml', HERE.parent / 'bsl-difftastic/run.py']},
                  platform={n: v for n, v, _, _, _ in rows if n in ['com._1c.g5.v8.dt.bsl', 'org.eclipse.xtext', 'org.eclipse.jgit']},
                  allInputsDetached=all(r['detached'] for r in results))
    (out / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    # Status agreement is only a coarse check; also guard precise changed content.
    assert report['allInputsDetached']
    indexed = {r['id']: r for r in results}
    for engine in ['jgit', 'gumtree']:
        for row in results:
            assert 'error' not in row[engine], (row['id'], engine, row[engine])
            assert row[engine]['expectedStatusMatches'], (row['id'], engine, 'unexpected status')
        for name, contents in [('expression', {'100', '120'}), ('repeat-edit', {'1', '2'}),
                               ('large', {'500', '1500'})]:
            value = indexed[name][engine]
            assert {s['content'] for side in ['left', 'right'] for s in value[side]} == contents, (name, engine)
    preview(cases, results, out)
    print(out / 'results.json')


def utf16_index(text, units):
    return len(text.encode('utf-16-le')[:units * 2].decode('utf-16-le'))


def preview(cases, results, out):
    sections = []
    for case, row in zip(cases, results):
        if case['id'].startswith('corpus/') or case['id'] == 'large': continue
        blocks = []
        for engine in ['difftastic', 'jgit', 'gumtree']:
            value = row[engine]
            spans = {}
            if engine == 'difftastic':
                raw = json.loads((out / 'cases' / case['id'] / 'difftastic.json').read_text())
                for side, key in [('lhs', 'before'), ('rhs', 'after')]:
                    lines = case[key].split('\n')
                    spans[key] = []
                    for chunk in raw.get('chunks', []):
                        for pair in chunk:
                            if side not in pair: continue
                            item = pair[side]
                            n = item['line_number']
                            base = sum(len(s) + 1 for s in lines[:n])
                            for span in item['changes']:
                                data = lines[n].encode()
                                a = base + len(data[:span['start']].decode())
                                b = base + len(data[:span['end']].decode())
                                spans[key].append((a, b))
                    if raw['status'] in ['created', 'deleted']:
                        spans[key] = [(0, len(case[key]))]
            else:
                for side, key in [('left', 'before'), ('right', 'after')]:
                    spans[key] = [(utf16_index(case[key], s['start']),
                                   utf16_index(case[key], s['start'] + s['length']))
                                  for s in value.get(side, [])]
            panes = []
            for key in ['before', 'after']:
                text = case[key]
                points = sorted({0, len(text)} | {x for pair in spans[key] for x in pair})
                rendered = ''.join(('<mark>' if any(a <= start < b for a, b in spans[key]) else '<span>')
                                   + html.escape(text[start:end])
                                   + ('</mark>' if any(a <= start < b for a, b in spans[key]) else '</span>')
                                   for start, end in zip(points, points[1:]))
                panes.append('<pre>' + rendered + '</pre>')
            blocks.append('<article><h3>' + engine + '</h3><div class="panes">' + ''.join(panes) + '</div></article>')
        sections.append('<section><h2>' + html.escape(case['title']) + '</h2><p>'
                        + ('EDT syntax errors: recovered tree, production fallback required' if row['requiresFallback'] else 'EDT parsed without syntax errors')
                        + '</p>' + ''.join(blocks) + '</section>')
    (out / 'preview.html').write_text('<!doctype html><meta charset="utf-8"><title>BSL JVM experiment</title>'
        '<style>body{font:16px sans-serif;margin:24px}section{border-top:2px solid #888;padding:16px 0}'
        '.panes{display:grid;grid-template-columns:1fr 1fr;gap:12px}pre{white-space:pre-wrap;overflow-wrap:anywhere;'
        'background:#f5f5f5;padding:12px;margin:0}mark{background:#ffd58a}h3{margin-bottom:8px}</style>'
        '<h1>BSL: Difftastic / EDT + JGit / EDT + GumTree</h1>'
        '<p>Identical source snapshots. Highlights only; no synchronized row alignment or Apply/Reject.</p>'
        + ''.join(sections))


if __name__ == '__main__':
    main()
