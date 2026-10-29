#!/usr/bin/env python3
"""Isolated method-aware JGit spike and resource benchmark; Python stdlib only."""
import argparse
import hashlib
import html
import importlib.util
import json
import math
import os
from pathlib import Path
import platform
import signal
import statistics
import subprocess
import sys
import tempfile
import time
from urllib.parse import unquote, urlparse
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('native_fixtures', HERE.parent / 'bsl-difftastic/run.py')
FIX = importlib.util.module_from_spec(spec)
spec.loader.exec_module(FIX)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def proc(pid):
    """Optional Linux live process metrics; null denotes unsupported/unobserved."""
    result = dict(rssBytes=None, highWaterBytes=None, threads=None, openFiles=None, io=None)
    try:
        for line in Path(f'/proc/{pid}/status').read_text().splitlines():
            key, value = line.split(':', 1)
            if key in ['VmRSS', 'VmHWM', 'Threads']:
                result[{'VmRSS': 'rssBytes', 'VmHWM': 'highWaterBytes', 'Threads': 'threads'}[key]] = int(value.split()[0]) * (1 if key == 'Threads' else 1024)
        result['openFiles'] = len(list(Path(f'/proc/{pid}/fd').iterdir()))
        result['io'] = {k: int(v) for k, v in (line.split(':') for line in Path(f'/proc/{pid}/io').read_text().splitlines())}
    except (OSError, ValueError):
        pass
    return result


def measured(command, log, timeout, env=None):
    """wait4 retains exact child CPU/maxRSS on POSIX; own process group only."""
    started = time.perf_counter()
    usage = dict(cpuUserSeconds=None, cpuSystemSeconds=None, wait4LaunchMaxRssBytes=None,
                 launcherRssBytesAtSpawn=proc(os.getpid())['rssBytes'],
                 sampledPeak=dict(rssBytes=None, highWaterBytes=None, threads=None, openFiles=None),
                 lastIo=None, timedOut=False)
    with log.open('wb') as stream:
        child = subprocess.Popen(list(map(str, command)), stdout=stream, stderr=subprocess.STDOUT,
                                 env=env, start_new_session=(os.name == 'posix'))
        usage['pid'] = child.pid
        deadline = time.monotonic() + timeout
        try:
            while True:
                try:
                    after_exec = Path(f'/proc/{child.pid}/exe').resolve(strict=True) == Path(command[0]).resolve()
                except OSError:
                    after_exec = False
                live = proc(child.pid) if after_exec else dict.fromkeys(['rssBytes', 'highWaterBytes', 'threads', 'openFiles', 'io'])
                for key in usage['sampledPeak']:
                    if live[key] is not None:
                        usage['sampledPeak'][key] = max(usage['sampledPeak'][key] or 0, live[key])
                if live['io'] is not None: usage['lastIo'] = live['io']
                if hasattr(os, 'wait4'):
                    pid, status, resource = os.wait4(child.pid, os.WNOHANG)
                    if pid:
                        child.returncode = os.waitstatus_to_exitcode(status)
                        usage.update(cpuUserSeconds=resource.ru_utime, cpuSystemSeconds=resource.ru_stime,
                                     wait4LaunchMaxRssBytes=resource.ru_maxrss * (1 if sys.platform == 'darwin' else 1024),
                                     minorFaults=resource.ru_minflt, majorFaults=resource.ru_majflt,
                                     voluntaryContextSwitches=resource.ru_nvcsw, involuntaryContextSwitches=resource.ru_nivcsw,
                                     inputBlocks=resource.ru_inblock, outputBlocks=resource.ru_oublock)
                        break
                elif child.poll() is not None:
                    break
                if time.monotonic() >= deadline:
                    usage['timedOut'] = True
                    if os.name == 'posix': os.killpg(child.pid, signal.SIGKILL)
                    else: child.kill()
                    child.wait()
                    break
                time.sleep(0.0005)
        finally:
            if child.returncode is None:
                if os.name == 'posix': os.killpg(child.pid, signal.SIGKILL)
                else: child.kill()
                child.wait()
        usage['exitCode'] = child.returncode
        usage['wallMs'] = (time.perf_counter() - started) * 1000
        usage['outputBytes'] = log.stat().st_size
        usage['pidExistsAfterWait'] = Path(f'/proc/{child.pid}').exists() if sys.platform.startswith('linux') else None
        if os.name == 'posix':
            # A killed Rust process can leave its thread group visible briefly after
            # the leader is reaped. Observe bounded cleanup rather than that race.
            settle = time.monotonic()
            while True:
                try: os.killpg(child.pid, 0); exists = True
                except ProcessLookupError: exists = False
                if not exists or time.monotonic() - settle >= 0.1: break
                time.sleep(0.001)
            usage['processGroupExistsAfterWait'] = exists
            usage['groupSettleMs'] = (time.monotonic() - settle) * 1000
        else: usage['processGroupExistsAfterWait'] = None
        usage['postExecSampledHighWaterBytes'] = usage['sampledPeak']['highWaterBytes']
    if not usage['timedOut'] and child.returncode:
        raise RuntimeError(f'Exit {child.returncode}: {log}')
    return usage


def build(args, out):
    rows = []
    for line in (args.configuration / 'org.eclipse.equinox.simpleconfigurator/bundles.info').read_text().splitlines():
        if not line or line.startswith('#'): continue
        n, v, url, level, auto = line.split(',')
        p = Path(unquote(urlparse(url).path))
        if not p.is_absolute(): raise ValueError('Expected absolute bundle URL: ' + url)
        rows.append((n, v, p, level))
    by_name = {n: p for n, _, p, _ in rows}
    env = dict(os.environ, JAVA_HOME=str(args.java_home), EDT_MCP_AUTO_START='false')
    classes = out / 'classes'; classes.mkdir()
    cp = os.pathsep.join(str(p) for _, _, p, _ in rows if p.suffix == '.jar')
    compiled = measured([args.java_home / 'bin/javac', '-proc:none', '-encoding', 'UTF-8', '-cp', cp,
                        '-d', classes, HERE / 'MethodProbe.java'], out / 'compile.log', 90, env)
    assert not compiled['timedOut']
    manifest = ('Manifest-Version: 1.0\nBundle-ManifestVersion: 2\n'
                'Bundle-SymbolicName: experiment.bslmethods;singleton:=true\n'
                'Bundle-Version: 0.0.1\nBundle-RequiredExecutionEnvironment: JavaSE-17\n'
                'Require-Bundle: org.eclipse.core.runtime,org.eclipse.equinox.app,\n'
                ' org.eclipse.xtext,org.eclipse.emf.ecore,org.eclipse.jgit,\n'
                ' com._1c.g5.v8.dt.bsl.model,com._1c.g5.v8.dt.mcore,\n'
                ' com.google.gson;bundle-version="[2.13.0,3.0.0)"\n'
                'Import-Package: com.sun.management\n\n')
    jar = out / 'probe.jar'
    with zipfile.ZipFile(jar, 'w') as z:
        z.writestr('META-INF/MANIFEST.MF', manifest)
        z.writestr('plugin.xml', '<plugin><extension point="org.eclipse.core.runtime.applications" '
                   'id="run"><application><run class="experiment.MethodProbe"/></application></extension></plugin>')
        for p in classes.rglob('*.class'): z.write(p, str(p.relative_to(classes)))
    return rows, by_name, jar, env


def java_command(args, out, rows, by_name, jar, mode):
    root = out / mode
    config = root / 'configuration'
    (config / 'org.eclipse.equinox.simpleconfigurator').mkdir(parents=True)
    lines = [f'{n},{v},{p.as_uri()},{l},false' for n, v, p, l in rows]
    lines.append(f'experiment.bslmethods,0.0.1,{jar.as_uri()},4,false')
    (config / 'org.eclipse.equinox.simpleconfigurator/bundles.info').write_text('\n'.join(lines) + '\n')
    (config / 'config.ini').write_text(
        'osgi.bundles=reference:' + by_name['org.eclipse.equinox.simpleconfigurator'].as_uri() + '@1:start\n'
        'osgi.bundles.defaultStartLevel=4\nosgi.framework=' + by_name['org.eclipse.osgi'].as_uri() + '\n'
        'org.eclipse.equinox.simpleconfigurator.configUrl=file:org.eclipse.equinox.simpleconfigurator/bundles.info\n')
    return [args.java_home / 'bin/java', '-Xmx512m', f'-Dprobe.input={out / "input.json"}',
            f'-Dprobe.output={root / "results.json"}', f'-Dprobe.mode={mode}',
            f'-Dprobe.samples={args.samples}', f'-Dprobe.warmup={args.warmup}',
            '-jar', by_name['org.eclipse.equinox.launcher'], '-install', args.edt,
            '-configuration', config, '-data', root / 'workspace',
            '-application', 'experiment.bslmethods.run', '-nosplash', '-consolelog']


def cases_for(args):
    cases = [dict(id=n, title=t, before=a, after=b, expected=e,
                  benchmark=n in ['expression', 'nested-if', 'move', 'invalid-string']) for n, t, a, b, e in FIX.CASES]
    def add(name, a, b, **extra):
        cases.append(dict(id=name, title=name, before=a, after=b, expected='changed', benchmark=True, **extra))
    add('repeat-edit', FIX.module('    Сообщить(1);\n' * 300),
        FIX.module('    Сообщить(1);\n' * 150 + '    Сообщить(2);\n' + '    Сообщить(1);\n' * 149))
    add('move-edit', FIX.METHOD_A + FIX.METHOD_B, FIX.METHOD_B + FIX.METHOD_A.replace('"A"', '"C"'))
    add('rename', FIX.METHOD_A, FIX.METHOD_A.replace('Первый', 'НовыйПервый'))
    add('duplicate-name', FIX.METHOD_A + FIX.METHOD_A, FIX.METHOD_A + FIX.METHOD_A.replace('"A"', '"C"'))
    add('move-comments', '// A\n' + FIX.METHOD_A + '// B\n' + FIX.METHOD_B,
        '// B\n' + FIX.METHOD_B + '// A\n' + FIX.METHOD_A)
    add('conditional-method-move', '#Если Клиент Тогда\n' + FIX.METHOD_A + FIX.METHOD_B + '#КонецЕсли\n',
        '#Если Клиент Тогда\n' + FIX.METHOD_B + FIX.METHOD_A + '#КонецЕсли\n')
    third = FIX.module('    Сообщить("C");', 'Третий')
    def area(name, body): return '#Область ' + name + '\n' + body + '#КонецОбласти\n'
    add('region-boundary', area('A', FIX.METHOD_A) + area('B', FIX.METHOD_B + third),
        area('A', FIX.METHOD_A + FIX.METHOD_B) + area('B', third))
    add('region-boundary-bom', '\ufeff' + area('A', FIX.METHOD_A + FIX.METHOD_B) + area('B', third),
        '\ufeff' + FIX.METHOD_A + area('A', FIX.METHOD_B) + area('B', third))
    originals = {}
    if args.corpus:
        for p in sorted(args.corpus.rglob('*.bsl')):
            originals[str(p)] = digest(p)
            source = p.read_bytes().decode('utf-8')
            cases.append(dict(id='corpus/' + str(p.relative_to(args.corpus)), title=p.parent.name,
                              before=source, after='\n' + source, expected='unchanged', benchmark=False))
        edits = [('ОбщийКлиент', 'Позиция) + ИмяФормы;', 'Позиция + 0)\n\t\t+ ИмяФормы;'),
                 ('РаботаСМножеством', 'По СоставКлюча.ВГраница() - 1 Цикл', 'По СоставКлюча.ВГраница() - 2 Цикл'),
                 ('РаботаСМассивом', 'УстановитьБезопасныйРежим(Истина);', 'УстановитьБезопасныйРежим(Ложь);')]
        for name, old, new in edits:
            p = args.corpus / 'src/CommonModules' / name / 'Module.bsl'
            a = p.read_bytes().decode('utf-8')
            assert old in a
            add('real-edit/' + name, a, a.replace(old, new, 1), replacement=dict(old=old, new=new))
    for n in [10, 100, 1000, 5000]:
        a = '\n'.join(FIX.module(f'    Сообщить({i});', f'Метод{i}') for i in range(n))
        add(f'scale/{n}', a, a.replace(f'Сообщить({n//2})', f'Сообщить({n+n//2})'))
    return cases, originals


def native_once(binary, case, out, timeout=30):
    start, cpu = time.perf_counter(), time.thread_time_ns()
    with tempfile.TemporaryDirectory(prefix='native-', dir=out / 'temp') as folder:
        folder = Path(folder)
        a, b = folder / 'before.bsl', folder / 'after.bsl'
        a.write_bytes(case['before'].encode()); b.write_bytes(case['after'].encode())
        ready = time.perf_counter()
        env = {k: v for k, v in os.environ.items() if not k.startswith('DFT_')}
        env['DFT_UNSTABLE'] = 'yes'
        usage = measured([binary, '--display=json', '--color=never', a, b], folder / 'stdout', timeout, env)
        if usage['timedOut']: return None, usage
        raw = json.loads((folder / 'stdout').read_text())
        read = time.perf_counter()
        # Cache lines once. The older fixture validator repeatedly splits the entire
        # module per row, which would make the harness itself quadratic on large inputs.
        lines = {side: case[key].split('\n') for side, key in [('lhs', 'before'), ('rhs', 'after')]}
        for chunk in raw.get('chunks', []):
            for pair in chunk:
                for side, row in pair.items():
                    data = lines[side][row['line_number']].encode()
                    for span in row['changes']:
                        assert data[span['start']:span['end']].decode() == span['content']
                        # Validate UTF-8 -> UTF-16 boundaries as the Java viewer adapter must do.
                        data[:span['start']].decode().encode('utf-16-le')
                        data[:span['end']].decode().encode('utf-16-le')
        usage['invalidAlignedRows'] = [pair for pair in raw.get('aligned_lines', [])
            if any(n is not None and not 0 <= n < len(lines[side]) for n, side in zip(pair, ['lhs', 'rhs']))]
        usage.update(tempInputBytes=a.stat().st_size + b.stat().st_size,
                     writeSnapshotsMs=(ready - start) * 1000,
                     readJsonMs=(read - ready) * 1000 - usage['wallMs'],
                     rangeAlignmentMs=(time.perf_counter() - read) * 1000)
    usage.update(endToEndMs=(time.perf_counter() - start) * 1000,
                 wrapperThreadCpuNs=time.thread_time_ns() - cpu,
                 allocatedBytes=None, heap=None, gc=None, parserDiffStages=None)
    return raw, usage


def stats(values):
    values = sorted(v for v in values if v is not None)
    if not values: return None
    return dict(n=len(values), median=statistics.median(values), p95=values[math.ceil(len(values)*.95)-1], max=max(values))


def resource_delta(before, after):
    result = dict(heapUsed=after['heap']['used'] - before['heap']['used'],
                  heapCommitted=after['heap']['committed'] - before['heap']['committed'],
                  threads=after['threads'] - before['threads'],
                  openFiles=None if before['openFiles'] is None or after['openFiles'] is None else after['openFiles'] - before['openFiles'])
    result['gcCountTimeMs'] = {name: [None if a is None or b is None else b-a for a, b in zip(values, after['gcCountTimeMs'][name])]
                              for name, values in before['gcCountTimeMs'].items()}
    result['linuxIo'] = {key: after['linuxIo'][key] - value for key, value in before['linuxIo'].items()} if before['linuxIo'] and after['linuxIo'] else None
    return result


def validate(cases, profiles, native):
    indexed = {c['id']: c for c in cases}
    for mode in ['parse', 'flat', 'methods']:
        rows = profiles[mode]['cases']
        assert len(rows) == len(cases)
        for row in rows:
            c, r = indexed[row['id']], row['result']
            assert r['detached'] and r['byteChanges'] == (c['before'] != c['after'])
            for side, key in [('left', 'before'), ('right', 'after')]:
                source = c[key].encode('utf-16-le')
                for s in r[side]:
                    assert source[s['start']*2:(s['start']+s['length'])*2].decode('utf-16-le') == s['text'], (row['id'], s)
            alignment = r['lines']['aligned']
            if mode == 'parse': continue
            for col, key in [(0, 'before'), (1, 'after')]:
                numbers = [p[col] for p in alignment if p[col] is not None]
                # RawText has no phantom final empty line; still covers every actual line exactly once.
                expected = len(c[key].splitlines()) if c[key] else 0
                assert numbers == list(range(expected)), (row['id'], mode, 'alignment')
            # Text fallback deliberately reports whitespace changes when syntax is malformed.
            if r['fallback'] == 'syntax-errors:text-lines': assert r['changed'] == r['byteChanges']
            else: assert r['changed'] == (c['expected'] != 'unchanged'), (row['id'], mode, 'status')
    methods = {r['id']: r['result'] for r in profiles['methods']['cases']}
    for name, contents in [('expression', {'100', '120'}), ('repeat-edit', {'1', '2'}),
                           ('move-edit', {'"A"', '"C"'})]:
        r = methods[name]
        assert {s['text'] for side in ['left', 'right'] for s in r[side]} == contents, (name, r)
    assert methods['move']['moves'] and not methods['move']['left'] and not methods['move']['right']
    assert methods['move-edit']['moves']
    assert methods['duplicate-name']['fallback'] == 'ambiguous-identity:flat-tokens'
    assert methods['conditional-method-move']['fallback'] == 'preprocessor:flat-tokens'
    assert methods['region-boundary']['fallback'] == 'region-placement:flat-tokens'
    assert methods['region-boundary-bom']['fallback'] == 'region-placement:flat-tokens'
    for mode in ['flat', 'methods']:
        values = {r['id']: r['result'] for r in profiles[mode]['cases']}
        for n in [10, 100, 1000, 5000]:
            r = values[f'scale/{n}']
            assert {s['text'] for side in ['left', 'right'] for s in r[side]} == {str(n//2), str(n+n//2)}, (mode, n)
    if 'real-edit/ОбщийКлиент' in methods:
        assert {s['text'] for side in ['left', 'right'] for s in methods['real-edit/ОбщийКлиент'][side]} == {'+', '0'}
    for c, r in zip(cases, native):
        expected = ('changed' if c['before'] != c['after'] else 'unchanged') if r['language'].startswith('Text') else c['expected']
        assert r['id'] == c['id'] and r['status'] == expected, (c['id'], r['status'])


def render(cases, profiles, out):
    """Preserve full bytes in details; rows show independent move metadata and edits."""
    methods = {m: {r['id']: r['result'] for r in profiles[m]['cases']} for m in ['flat', 'methods']}
    sections = []
    for c in cases:
        if c['id'].startswith(('corpus/', 'scale/')): continue
        blocks = []
        for mode in ['difftastic', 'flat', 'methods']:
            if mode == 'difftastic':
                raw = json.loads((out / 'cases' / c['id'] / 'difftastic.json').read_text())
                bad = [pair for pair in raw.get('aligned_lines', []) if any(n is not None and not 0 <= n < len(c[key].split('\n')) for n, key in zip(pair, ['before', 'after']))]
                # Preview only: blank invalid cells; original JSON remains untouched on disk.
                raw['aligned_lines'] = [[n if n is None or 0 <= n < len(c[key].split('\n')) else None for n, key in zip(pair, ['before', 'after'])] for pair in raw.get('aligned_lines', [])]
                content = FIX.preview((c['id'], c['title'], c['before'], c['after'], c['expected']), raw, {'lhs': '', 'rhs': ''})
                if bad: content = '<p>INVALID native alignment (blanked in this preview): ' + html.escape(str(bad)) + '</p>' + content
            else:
                r = methods[mode][c['id']]
                panes = []
                for side, key in [('left', 'before'), ('right', 'after')]:
                    text = c[key]; data = text.encode('utf-16-le')
                    spans = [(len(data[:s['start']*2].decode('utf-16-le')), len(data[:(s['start']+s['length'])*2].decode('utf-16-le'))) for s in r[side]]
                    bounds = sorted({0, len(text)} | {p for s in spans for p in s})
                    rendered = ''.join(('<mark>' if any(a <= start < b for a, b in spans) else '<span>') + html.escape(text[start:end])
                                       + ('</mark>' if any(a <= start < b for a, b in spans) else '</span>') for start, end in zip(bounds, bounds[1:]))
                    panes.append('<pre>' + rendered + '</pre>')
                content = '<p>' + html.escape(json.dumps(dict(moves=r['moves'], fallback=r['fallback'], byteChanges=r['byteChanges']), ensure_ascii=False)) + '</p><div class="panes">' + ''.join(panes) + '</div>'
            blocks.append('<article><h3>' + mode + '</h3>' + content + '</article>')
        sections.append('<section><h2>' + html.escape(c['title']) + '</h2>' + ''.join(blocks) + '</section>')
    (out / 'preview.html').write_text('<!doctype html><meta charset="utf-8"><title>BSL method comparison</title><style>'
        'body{font:15px system-ui;margin:24px}section{border-top:2px solid #888;margin:30px 0}.panes{display:grid;grid-template-columns:1fr 1fr;gap:12px}'
        'pre{font:14px/1.5 monospace;white-space:pre-wrap;overflow-wrap:anywhere;margin:0}mark{background:#ffcf80}'
        'article{padding:12px;background:#f7f7f7;margin:12px 0}table{width:100%;table-layout:fixed}.ln{color:#555;width:40px}.changed{background:#ffcf80}</style>'
        '<h1>Difftastic · flat JGit · method-aware JGit</h1><p>Throwaway experiment. Move metadata is separate from token highlighting. No Apply/Reject.</p>' + ''.join(sections), encoding='utf-8')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for key in ['edt', 'configuration', 'java-home', 'difft', 'output']:
        p.add_argument('--' + key, required=True, type=Path)
    p.add_argument('--corpus', type=Path)
    p.add_argument('--samples', type=int, default=50)
    p.add_argument('--warmup', type=int, default=10)
    args = p.parse_args()
    if args.samples < 1 or args.warmup < 0: p.error('Invalid sample counts')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    (out / 'temp').mkdir()
    cases, originals = cases_for(args)
    rows, by_name, jar, env = build(args, out)
    originals[str(args.difft.resolve())] = digest(args.difft)
    for n, _, path, _ in rows:
        if n == 'com.ditrix.edt.mcp.server': originals[str(path)] = digest(path)
    sources = {str(path.relative_to(ROOT)): digest(path) for path in [HERE / 'run.py', HERE / 'MethodProbe.java', HERE.parent / 'bsl-difftastic/run.py']}
    write(out / 'baseline.json', dict(artifacts=originals, sources=sources, nativeSourceCommit='130e39a5767fd67442a3a7cdf4b2f1f4dacfb01c'))
    write(out / 'input.json', cases)
    profiles, processes = {}, {}
    for mode in ['idle', 'parse', 'flat', 'methods', 'cleanup']:
        command = java_command(args, out, rows, by_name, jar, mode)
        processes[mode] = measured(command, out / mode / 'process.log', 360, env)
        assert not processes[mode]['timedOut'], mode
        profiles[mode] = json.loads((out / mode / 'results.json').read_text())
        print('JVM completed:', mode, flush=True)
    native = []
    for c in cases:
        folder = out / 'cases' / c['id']; folder.mkdir(parents=True)
        for side in ['before', 'after']: (folder / (side + '.bsl')).write_bytes(c[side].encode())
        raw, first = native_once(args.difft, c, out)
        write(folder / 'difftastic.json', raw)
        samples = []
        if c['benchmark']:
            for _ in range(args.warmup): native_once(args.difft, c, out)
            for _ in range(args.samples):
                _, usage = native_once(args.difft, c, out)
                assert not usage['timedOut']; samples.append(usage)
        native.append(dict(id=c['id'], status=raw['status'], language=raw['language'], firstSample=first, samples=samples))
        write(out / 'native.json', native)
        print('Native completed:', c['id'], flush=True)
    # Timeout cleanup of a real native comparison; immediate budget is intentionally expired.
    _, timeout = native_once(args.difft, cases[-1], out, timeout=0.001)
    write(out / 'native-timeout.json', timeout)
    assert timeout['timedOut'] and not timeout['pidExistsAfterWait'] and not timeout['processGroupExistsAfterWait']
    assert not list((out / 'temp').iterdir())
    validate(cases, profiles, native)
    for path, sha in originals.items(): assert digest(Path(path)) == sha, path
    for path, sha in sources.items(): assert digest(ROOT / path) == sha, path
    render(cases, profiles, out)
    summary = dict(environment=dict(platform=platform.platform(), processor=platform.processor(), python=sys.version,
                                    cpuCount=os.cpu_count(), pageSize=os.sysconf('SC_PAGE_SIZE') if hasattr(os, 'sysconf') else None),
                   platformVersions=profiles['methods']['resolvedBundles'],
                   installedBundleVersions={n: sorted({v for name, v, _, _ in rows if name == n}) for n in profiles['methods']['resolvedBundles']},
                   newRuntimeDependencies=[], probeJarBytes=jar.stat().st_size, sources=sources,
                   inputSha256=digest(out / 'input.json'), preservedArtifactCount=len(originals),
                   processProfiles=processes, nativeTimeoutCleanup=timeout,
                   runtimeProfiles={m: dict(providerSetupMs=r['providerSetupMs'], readyUptimeMs=r['jvmUptimeAtProviderReadyMs'],
                                            entry=r['entry'], final=r['final'], delta=resource_delta(r['entry'], r['final'])) for m, r in profiles.items()},
                   warmIdle=profiles['idle']['warmIdle'],
                   cycles=profiles['methods']['cycles'], cleanupDiagnostic=profiles['cleanup']['cleanupDiagnostic'],
                   cancellation=profiles['methods']['cancellation'],
                   samples=args.samples, warmup=args.warmup, cases=[])
    native_index = {r['id']: r for r in native}
    indexes = {m: {r['id']: r for r in profiles[m]['cases']} for m in ['parse', 'flat', 'methods']}
    for c in cases:
        row = dict(id=c['id'], bytesBefore=len(c['before'].encode()), bytesAfter=len(c['after'].encode()), benchmark=c['benchmark'])
        for mode in indexes:
            r = indexes[mode][c['id']]; value = r['result']
            row[mode] = dict(fallback=value['fallback'], errors=len(value['leftErrors']) + len(value['rightErrors']),
                             spans=len(value['left']) + len(value['right']), moves=len(value['moves']),
                             uptimeBeforeFirstMs=r['jvmUptimeBeforeFirstMs'], uptimeAfterFirstMs=r['jvmUptimeAfterFirstMs'],
                             resourceWindowDelta=resource_delta(r['resourcesBefore'], r['resourcesAfter']),
                             firstSample=r['firstSample'], warm={k: stats(s[k] for s in r.get('samples', [])) for k in r['firstSample']})
        r = native_index[c['id']]
        row['difftastic'] = dict(language=r['language'], status=r['status'], firstSample=r['firstSample'],
                                 warm={k: stats(s[k] for s in r['samples']) for k in ['endToEndMs', 'wallMs', 'wait4LaunchMaxRssBytes', 'postExecSampledHighWaterBytes', 'cpuUserSeconds', 'cpuSystemSeconds']})
        summary['cases'].append(row)
    write(out / 'summary.json', summary)
    print('PASS:', len(cases), 'cases;', out / 'summary.json', flush=True)


if __name__ == '__main__':
    main()
