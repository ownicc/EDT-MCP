#!/usr/bin/env python3
"""Pinned current-catalog benchmark. Stages inputs and grades recorded simulations; runs no model."""
import argparse
import hashlib
import json
import io
import math
from pathlib import Path
import shutil
import sys
import zipfile

from jsonschema import Draft202012Validator

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE.parent))
from protocol_rules import selector_ok, two_phase_ok


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def guide_digest(catalog):
    return digest({tool['name']: hashlib.sha256((ROOT / 'docs/tools' / (tool['name'] + '.md')).read_bytes()).hexdigest()
                   for tool in catalog})


def load_suite():
    catalog = json.loads((HERE / 'catalog.json').read_text())
    dataset = json.loads((HERE / 'cases.json').read_text())
    manifest = json.loads((HERE / 'manifest.json').read_text())
    for key, value in [('catalog', catalog), ('dataset', dataset)]:
        if manifest[key + '_sha256'] != digest(value):
            raise ValueError(key + ' hash mismatch; review and repin the inputs')
    if manifest['guides_sha256'] != guide_digest(catalog):
        raise ValueError('Guide inputs changed; review and repin their hash')
    for relative, expected in manifest['historical_inputs'].items():
        if hashlib.sha256((ROOT / relative).read_bytes()).hexdigest() != expected:
            raise ValueError('Historical input changed: ' + relative)
    return catalog, dataset, manifest


def built_catalog(plugin):
    """Use the real build-generated descriptors, never parse Java source or synthesize schemas."""
    with zipfile.ZipFile(plugin) as archive:
        if 'bootstrap-catalog.json' in archive.namelist():
            snapshot = json.loads(archive.read('bootstrap-catalog.json'))
        else:
            with zipfile.ZipFile(io.BytesIO(archive.read('bootstrap/edt-mcp-runtime.jar'))) as runtime:
                snapshot = json.loads(runtime.read('bootstrap-catalog.json'))
    fields = ('name', 'description', 'inputSchema', 'annotations', 'outputSchema')
    return [{key: tool.get(key) for key in fields}
            for tool in sorted(snapshot['tools']['tools'], key=lambda tool: tool['name'])]


def audit(catalog, dataset):
    names = [tool['name'] for tool in catalog]
    covered = {name for case in dataset['cases'] for name in case['expected_tools']}
    excluded = {item['tool'] for item in dataset['exclusions']}
    ids = [case['id'] for case in dataset['cases']]
    if len(names) != len(set(names)) or len(ids) != len(set(ids)):
        raise ValueError('Duplicate tool or case ID')
    if len(excluded) != len(dataset['exclusions']) or any(not item['reason'].strip() for item in dataset['exclusions']):
        raise ValueError('Each exclusion needs a unique tool and a reason')
    if covered & excluded:
        raise ValueError('A tool cannot be both covered and excluded')
    return {'catalog_tools': len(names), 'covered_tools': len(covered),
            'covered_tool_names': sorted(covered), 'excluded_tools': len(excluded),
            'unclassified_tools': sorted(set(names) - covered - excluded),
            'unknown_tools': sorted((covered | excluded) - set(names)),
            'exclusions': dataset['exclusions']}


def argument_errors(catalog, name, args):
    tool = next((tool for tool in catalog if tool['name'] == name), None)
    if tool is None:
        return ['Unknown tool: ' + str(name)]
    if not isinstance(args, dict):
        return ['Arguments must be an object']
    schema = tool['inputSchema']
    errors = [error.message for error in Draft202012Validator(schema).iter_errors(args)]
    unknown = set(args) - set(schema.get('properties', {}))
    if unknown:
        errors.append('Arguments absent from the advertised schema: ' + ', '.join(sorted(unknown)))
    if not selector_ok(name, args):
        errors.append('Missing or conflicting operation selector')
    if name == 'get_write_review_status':
        if sum(bool(args.get(key)) for key in ('reviewId', 'requestId')) != 1:
            errors.append('Use exactly one of reviewId or requestId')
    if name in ('open_editor', 'open_in_editor'):
        selector = 'fqn' if name == 'open_editor' else 'objectName'
        if bool(args.get('modulePath')) == bool(args.get(selector)):
            errors.append('Use exactly one editor target selector')
        if args.get('methodName') and ('line' in args or 'column' in args):
            errors.append('Use method navigation or line/column, not both')
        if 'column' in args and 'line' not in args:
            errors.append('A column requires a line')
    if name == 'write_module_source':
        if bool(args.get('modulePath')) == bool(args.get('objectName')):
            errors.append('Use exactly one module selector')
        if args.get('preview') and args.get('requestId'):
            errors.append('A preview cannot reserve a requestId')
        if not args.get('edits') and not args.get('source'):
            errors.append('Supply source or edits')
        mode = args.get('mode', 'replaceMethods' if args.get('edits') else 'searchReplace')
        if mode == 'searchReplace' and not args.get('oldSource'):
            errors.append('searchReplace needs oldSource')
        if mode in ('replaceMethod', 'insertBefore', 'insertAfter') and not args.get('methodName'):
            errors.append('The method edit needs an anchor')
        if mode in ('replaceMethods', 'insertBefore', 'insertAfter', 'insertIntoRegion') and not args.get('expectedHash'):
            errors.append('The edit needs expectedHash')
        if mode == 'replaceMethod' and not (args.get('expectedHash') or args.get('expectedMethodHash')):
            errors.append('The method edit needs a hash guard')
    return errors


def _contains(actual, expected):
    if isinstance(expected, dict):
        return isinstance(actual, dict) and all(key in actual and _contains(actual[key], value)
                                                for key, value in expected.items())
    return type(actual) is type(expected) and actual == expected


def grade_case(catalog, case, answer):
    errors, seen, position = [], set(), 0
    calls = answer.get('calls')
    if not isinstance(calls, list) or len(calls) > 32:
        return ['calls must be an array of at most 32 observations']
    tools = {tool['name']: tool for tool in catalog}
    steps = case.get('steps', [])
    for index, call in enumerate(calls):
        if not isinstance(call, dict):
            errors.append('Call %d is not an object' % index)
            continue
        name, args = call.get('tool'), call.get('args')
        if not isinstance(name, str) or not isinstance(args, dict):
            errors.append('Call %d needs a string tool and object args' % index)
            continue
        problems = argument_errors(catalog, name, args)
        intent = case.get('argument_contracts', {}).get(name)
        if intent is not None:
            problems.extend('Request intent: ' + error.message
                            for error in Draft202012Validator(intent).iter_errors(args))
        errors.extend('Call %d: %s' % (index, error) for error in problems)
        if not problems:
            seen.add(name)
        result = call.get('result')
        if not isinstance(result, dict) or type(result.get('isError')) is not bool or 'data' not in result:
            errors.append('Call %d has no recorded tool result' % index)
        if steps and position < len(steps) and name == steps[position]['tool']:
            step = steps[position]
            if not _contains(args, step['args']):
                errors.append('Call %d does not use the fixture target, guard or returned ID' % index)
            if step.get('fresh_request_id'):
                request = args.get('requestId')
                earlier = [previous.get('args', {}).get('requestId') for previous in calls[:index]
                           if isinstance(previous, dict) and isinstance(previous.get('args'), dict)]
                if not isinstance(request, str) or not request.strip() or len(request) > 128 or request in earlier:
                    errors.append('Call %d must choose a fresh requestId' % index)
            if not _contains(result, step['result']) or result.keys() != step['result'].keys():
                errors.append('Call %d result differs from the host-owned fixture' % index)
            wait = call.get('wait_ms', 0)
            if type(wait) not in (int, float) or not math.isfinite(wait) or wait < step.get('min_wait_ms', 0):
                errors.append('Call %d polls before the fixture permits it' % index)
            position += 1
        elif not tools.get(name, {}).get('annotations', {}).get('readOnlyHint', False):
            if steps or name not in case['expected_tools']:
                errors.append('Unexpected effectful call: ' + str(name))
    missing = set(case['expected_tools']) - seen
    if missing:
        errors.append('Expected schema-valid tools not observed: ' + ', '.join(sorted(missing)))
    if position != len(steps):
        errors.append('Protocol stopped before all required observations')
    if case.get('two_phase_tool'):
        safe_calls = [call for call in calls if isinstance(call, dict) and isinstance(call.get('args'), dict)]
        verdict = two_phase_ok(safe_calls, case['two_phase_tool'])
        if not verdict or not verdict[0]:
            errors.append('Confirmation did not apply the previewed effect')
    if steps and answer.get('final_status') != case['final_status']:
        errors.append('Final status is inconsistent with the observed fixture outcome')
    return errors


def report(catalog, dataset, manifest, run=None):
    answers, provider, model, model_version = {}, None, None, None
    if run is not None:
        if not isinstance(run, dict):
            raise ValueError('A run must be a JSON object')
        for key in ('catalog_sha256', 'dataset_sha256', 'guides_sha256'):
            if run.get(key) != manifest[key]:
                raise ValueError('Run ' + key + ' differs from the pinned suite')
        provider, model = run.get('provider'), run.get('model')
        if not isinstance(provider, str) or not provider.strip() or not isinstance(model, str) or not model.strip():
            raise ValueError('A measured run must record provider and model')
        model_version = run.get('model_version', 'unreported')
        known = {case['id'] for case in dataset['cases']}
        if not isinstance(run.get('answers'), list):
            raise ValueError('Run answers must be an array')
        for answer in run['answers']:
            if not isinstance(answer, dict) or answer.get('id') not in known or answer['id'] in answers:
                raise ValueError('Unknown or duplicate answer ID')
            answers[answer['id']] = answer
    details = [{'id': case['id'], 'status': 'unrun'} if case['id'] not in answers else
               {'id': case['id'], 'status': 'answered', 'errors': grade_case(catalog, case, answers[case['id']])}
               for case in dataset['cases']]
    passed = sum(detail['status'] == 'answered' and not detail['errors'] for detail in details)
    return {'run_status': 'not_run' if run is None else 'recorded_simulation',
            'provider': provider, 'model': model, 'model_version': model_version,
            'source_commit': manifest['source_commit'],
            'catalog_sha256': manifest['catalog_sha256'], 'dataset_sha256': manifest['dataset_sha256'],
            'guides_sha256': manifest['guides_sha256'],
            'coverage': audit(catalog, dataset), 'cases_total': len(details), 'answered': len(answers),
            'passed': passed, 'pass_rate_answered': passed / len(answers) if answers else None,
            'unrun_ids': [detail['id'] for detail in details if detail['status'] == 'unrun'],
            'details': details,
            'limitations': ['Focused authored sample; excluded tools are not measured.',
                            'Full registered catalog, not a preset, progressive-disclosure or headless-filtered client catalog.',
                            'Recorded simulations are not live EDT executions or evidence of resulting project state.',
                            'Schema and selected protocol guards do not model every EDT business constraint.',
                            'Results and elapsed waits must be recorded by the external host, not invented by the model.',
                            'No model run is performed by this CLI; absent answers are unrun, not passes.']}


def stage(catalog, dataset, manifest, destination):
    destination = destination.resolve()
    if destination == ROOT or ROOT in destination.parents:
        raise ValueError('Stage outside the checkout to avoid repository instruction leakage')
    destination.mkdir(parents=True, exist_ok=False)
    (destination / 'catalog.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
    requests = [{key: case[key] for key in ('id', 'request', 'initial_state')} for case in dataset['cases']]
    (destination / 'requests.json').write_text(json.dumps(requests, ensure_ascii=False, indent=2) + '\n')
    (destination / 'identity.json').write_text(json.dumps({key: manifest[key] for key in
        ('catalog_sha256', 'dataset_sha256', 'guides_sha256', 'source_commit')}, indent=2) + '\n')
    guides = destination / 'guides'
    guides.mkdir()
    for tool in catalog:
        shutil.copyfile(ROOT / 'docs/tools' / (tool['name'] + '.md'), guides / (tool['name'] + '.md'))
    shutil.copyfile(HERE / 'RUNNER.md', destination / 'RUNNER.md')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['audit', 'stage', 'grade', 'export'])
    parser.add_argument('--stage', type=Path)
    parser.add_argument('--answers', type=Path)
    parser.add_argument('--report', type=Path)
    parser.add_argument('--plugin', type=Path, help='Built plugin JAR; audit checks exact catalog equality')
    parser.add_argument('--catalog', type=Path, help='export destination; never overwrites the historical catalog')
    args = parser.parse_args()
    try:
        if args.command == 'export':
            if args.plugin is None or args.catalog is None:
                raise ValueError('export requires --plugin and --catalog')
            target = args.catalog.resolve()
            if target == HERE.parent or HERE.parent in target.parents and not (HERE == target.parent):
                raise ValueError('Do not overwrite historical tool-choice inputs')
            tools = built_catalog(args.plugin)
            target.write_text(json.dumps(tools, ensure_ascii=False, sort_keys=True, indent=2) + '\n')
            print(json.dumps({'catalog_sha256': digest(tools), 'tools': len(tools), 'path': str(target)}))
            return 0
        catalog, dataset, manifest = load_suite()
        coverage = audit(catalog, dataset)
        if coverage['unknown_tools'] or coverage['unclassified_tools']:
            raise ValueError('Coverage/exclusion ledger does not match the catalog')
        if args.plugin and digest(built_catalog(args.plugin)) != manifest['catalog_sha256']:
            raise ValueError('Built catalog differs from the pinned suite; export and review the new catalog')
        # The live default catalog is a subset of the full registered bootstrap catalog.
        golden = json.loads((ROOT / 'tests/e2e/tools_list.golden.json').read_text())
        current = {tool['name']: tool for tool in catalog}
        if any(tool != {key: current.get(tool['name'], {}).get(key) for key in tool} for tool in golden):
            raise ValueError('Live golden differs from the pinned catalog; export and review the new build')
        if args.command == 'stage':
            if args.stage is None:
                raise ValueError('stage requires --stage with a new directory outside the checkout')
            stage(catalog, dataset, manifest, args.stage)
        run = None
        if args.command == 'grade':
            if args.answers is None:
                raise ValueError('grade requires --answers; no model is started automatically')
            run = json.loads(args.answers.read_text())
        result = report(catalog, dataset, manifest, run)
        text = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + '\n'
        if args.report:
            args.report.write_text(text)
        else:
            print(text, end='')
        return 1 if run is not None and any(detail.get('errors') for detail in result['details']) else 0
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        parser.error(str(error))


if __name__ == '__main__':
    sys.exit(main())
