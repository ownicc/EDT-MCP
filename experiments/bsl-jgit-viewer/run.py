#!/usr/bin/env python3
"""Stage a separate visible EDT and a test-only startup fragment; stdlib only."""
import argparse
import bisect
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
from urllib.parse import unquote, urlparse

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def spans(source, tokens):
    # Independent range projection from frozen UTF-16 offsets; no production helper.
    units = source.encode('utf-16-le')
    lines, start, index = [], 0, 0
    while index < len(units) // 2:
        char = units[index*2:index*2+2]
        if char in (b'\r\0', b'\n\0'):
            end = index
            index += 2 if char == b'\r\0' and units[index*2+2:index*2+4] == b'\n\0' else 1
            lines.append((start, end)); start = index
        else:
            index += 1
    lines.append((start, index))
    starts = [line[0] for line in lines]
    result = []
    for token in tokens:
        begin, end = token['start'], token['start'] + token['length']
        if begin == end:
            continue
        first, last = bisect.bisect_right(starts, begin)-1, bisect.bisect_right(starts, end-1)-1
        for number in range(first, last+1):
            offset, stop = lines[number]
            a, b = min(stop-offset, max(0, begin-offset)), min(stop-offset, end-offset)
            result.append(dict(line=number, start=a, length=max(0, b-a)))
    return sorted(result, key=lambda s: (s['line'], s['start']))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--native-install', type=Path, required=True)
    p.add_argument('--native-workspace', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    p.add_argument('--java-home', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--bundle', type=Path, required=True)
    args = p.parse_args()
    out = args.output.resolve(); out.mkdir(exist_ok=False, parents=True)
    installation = out / 'edt'
    shutil.copytree(args.native_install, installation, symlinks=True,
                    ignore=shutil.ignore_patterns('org.eclipse.osgi', '*.log', '.lock'))
    shutil.copytree(args.native_workspace, out / 'workspace', symlinks=True,
                    ignore=shutil.ignore_patterns('.lock', '*.log', '.bak_*.log'))
    plugin = installation / 'prototype-plugins' / args.bundle.name
    shutil.copy2(args.bundle, plugin)
    rows = []
    info = installation / 'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info'
    for line in info.read_text().splitlines():
        if not line or line.startswith('#'): continue
        name, version, url, level, auto = line.split(',')
        if name == 'com.ditrix.edt.mcp.server':
            with zipfile.ZipFile(plugin) as z:
                manifest = z.read('META-INF/MANIFEST.MF').decode()
            version = next(row.split(': ', 1)[1] for row in manifest.splitlines() if row.startswith('Bundle-Version: '))
            url = plugin.as_uri()
        rows.append((name, version, url, level, auto))
    cp = os.pathsep.join([str(plugin)] + [unquote(urlparse(row[2]).path) for row in rows if row[2].endswith('.jar')])
    classes = out / 'classes'; classes.mkdir()
    with (out / 'compile-probe.log').open('w') as log:
        subprocess.run([args.java_home / 'bin/javac', '-proc:none', '-encoding', 'UTF-8', '-cp', cp,
                        '-d', classes, HERE / 'ViewerProbe.java'], stdout=log, stderr=log, check=True)
    jar = out / 'viewer-probe.jar'
    with zipfile.ZipFile(jar, 'w') as z:
        z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nBundle-ManifestVersion: 2\n'
                   'Bundle-SymbolicName: experiment.bslviewer;singleton:=true\nBundle-Version: 0.0.1\n'
                   'Fragment-Host: com.ditrix.edt.mcp.server\nBundle-RequiredExecutionEnvironment: JavaSE-17\n\n')
        z.writestr('fragment.xml', '<fragment><extension point="org.eclipse.ui.startup"><startup '
                   'class="com.ditrix.edt.mcp.server.ui.ViewerProbe"/></extension></fragment>')
        for file in classes.rglob('*.class'): z.write(file, str(file.relative_to(classes)))
    rows.append(('experiment.bslviewer', '0.0.1', jar.as_uri(), '4', 'false'))
    info.write_text('#encoding=UTF-8\n' + '\n'.join(','.join(row) for row in rows) + '\n')
    inputs = json.loads((args.evidence / 'input.json').read_text())
    old = {item['id']: item['result'] for item in json.loads((args.evidence / 'methods/results.json').read_text())['cases']}
    expected = []
    for item in inputs:
        result = old[item['id']]
        row = dict(fallback=result['fallback'])
        for side, key in [('left', 'before'), ('right', 'after')]:
            row[side] = spans(item[key], result[side])
            row[side + 'Moves'] = spans(item[key], [dict(start=m[side+'Start'], length=m[side+'Length']) for m in result['moves']])
        expected.append(row)
    (out / 'input.json').write_text(json.dumps(inputs, ensure_ascii=False))
    (out / 'expected.json').write_text(json.dumps(expected, ensure_ascii=False))
    (out / 'test.ini').write_text('com.ditrix.edt.mcp.server/mcpUpdateCheckInterval=never\n')
    command = [str(installation / '1cedt'), '-vm', str(args.java_home / 'bin/java'),
               '-configuration', str(installation / 'configuration'), '-data', str(out / 'workspace'),
               '-clean', '-consolelog', '-pluginCustomization', str(out / 'test.ini'), '-vmargs', '-Dedt.mcp.bslDiff=jgit',
               '-Dviewer.probe.output=' + str(out), '-Dviewer.probe.input=' + str(out / 'input.json'),
               '-Dviewer.probe.expected=' + str(out / 'expected.json')]
    env = dict(os.environ, GTK_THEME='Adwaita:light', GDK_BACKEND='x11', NO_AT_BRIDGE='0', EDT_MCP_AUTO_START='true', EDT_MCP_PORT='8766')
    with (out / 'process.log').open('w') as log:
        command = ['xvfb-run', '-a', '-s', '-screen 0 1600x1000x24', *command]
        process = subprocess.Popen(command, cwd=installation, env=env, stdout=log, stderr=log, start_new_session=True)
    (out / 'launch.json').write_text(json.dumps(dict(pid=process.pid, command=command), indent=2))
    print(json.dumps(dict(output=str(out), pid=process.pid)))


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(f"Probe compilation failed ({error.returncode}); inspect compile-probe.log") from None
