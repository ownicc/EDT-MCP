"""Offline evaluator tests. Fixtures are not measurements of a model."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import benchmark as bench


class BenchmarkTest(unittest.TestCase):
    def setUp(self):
        self.catalog, self.dataset, self.manifest = bench.load_suite()

    def test_catalog_and_dataset_are_pinned(self):
        self.assertEqual(self.manifest['catalog_sha256'], bench.digest(self.catalog))
        self.assertEqual(self.manifest['dataset_sha256'], bench.digest(self.dataset))
        changed = copy.deepcopy(self.catalog)
        changed[0]['description'] += ' changed'
        self.assertNotEqual(bench.digest(changed), self.manifest['catalog_sha256'])

    def test_every_catalog_tool_is_covered_or_explicitly_excluded(self):
        audit = bench.audit(self.catalog, self.dataset)
        self.assertGreater(audit['catalog_tools'], audit['covered_tools'])
        self.assertGreaterEqual(audit['covered_tools'], 24)
        self.assertEqual([], audit['unclassified_tools'])
        self.assertEqual([], audit['unknown_tools'])
        broken = copy.deepcopy(self.dataset)
        broken['exclusions'].pop()
        self.assertTrue(bench.audit(self.catalog, broken)['unclassified_tools'])

    def test_no_answers_never_reports_a_success_rate_or_model(self):
        report = bench.report(self.catalog, self.dataset, self.manifest)
        self.assertEqual('not_run', report['run_status'])
        self.assertIsNone(report['model'])
        self.assertIsNone(report['pass_rate_answered'])
        self.assertEqual(0, report['answered'])
        self.assertEqual(len(self.dataset['cases']), len(report['unrun_ids']))

    def test_schema_rejects_missing_wrong_enum_bool_and_nested_limits(self):
        bad = [
            ('read_module_source', {'projectName': 'Demo'}),
            ('get_server_status', {'projectName': 'Demo', 'operation': 'invented'}),
            ('get_job_status', {'jobId': 'j1', 'waitSeconds': True}),
            ('write_module_source', {'projectName': 'Demo', 'modulePath': 'CommonModules/X/Module.bsl', 'edits': []}),
            ('write_module_source', {'projectName': 'Demo', 'modulePath': 'CommonModules/X/Module.bsl', 'edits': [{'methodName': 'X', 'source': 'Procedure X()\nEndProcedure', 'invented': True}]}),
            ('get_write_review_status', {'reviewId': 'r1', 'accept': True}),
            ('open_in_editor', {'projectName': 'Demo', 'modulePath': 'CommonModules/X/Module.bsl', 'objectName': 'CommonModule.X'}),
            ('open_in_editor', {'projectName': 'Demo', 'modulePath': 'CommonModules/X/Module.bsl', 'column': 3}),
        ]
        for tool, args in bad:
            with self.subTest(tool=tool, args=args):
                self.assertTrue(bench.argument_errors(self.catalog, tool, args))

    def test_authored_protocol_fixtures_pass_but_invented_observations_do_not(self):
        for case in self.dataset['cases']:
            if not case.get('steps'):
                continue
            trace = [{'tool': s['tool'], 'args': copy.deepcopy(s['args']),
                      'result': copy.deepcopy(s['result']), 'wait_ms': s.get('min_wait_ms', 0)}
                     for s in case['steps']]
            for step, call in zip(case['steps'], trace):
                if step.get('fresh_request_id'):
                    call['args']['requestId'] = 'fresh-unit-test-id'
            answer = {'id': case['id'], 'calls': trace, 'final_status': case['final_status']}
            with self.subTest(case=case['id']):
                self.assertEqual([], bench.grade_case(self.catalog, case, answer))
                changed = copy.deepcopy(answer)
                changed['calls'][0]['result'] = {'isError': False, 'data': {'invented': True}}
                self.assertTrue(bench.grade_case(self.catalog, case, changed))

    def test_review_cannot_be_claimed_complete_before_human_apply(self):
        case = next(c for c in self.dataset['cases'] if c['label'] == 'review-wait')
        first = case['steps'][0]
        answer = {'id': case['id'], 'calls': [{'tool': first['tool'], 'args': first['args'], 'result': first['result']}], 'final_status': 'completed'}
        self.assertTrue(bench.grade_case(self.catalog, case, answer))

    def test_confirm_cannot_change_the_previewed_effect(self):
        case = next(c for c in self.dataset['cases'] if c['label'] == 'preview-confirm')
        calls = [{'tool': s['tool'], 'args': dict(s['args']), 'result': s['result']} for s in case['steps']]
        calls[1]['args']['deleteContent'] = False
        self.assertTrue(bench.grade_case(self.catalog, case, {'calls': calls, 'final_status': 'completed'}))

    def test_uncertain_append_cannot_be_retried_blindly(self):
        case = next(c for c in self.dataset['cases'] if c['label'] == 'unknown-write')
        first = case['steps'][0]
        calls = [{'tool': first['tool'], 'args': first['args'], 'result': first['result']}] * 2
        self.assertTrue(bench.grade_case(self.catalog, case, {'calls': calls, 'final_status': 'needs_recovery'}))

    def test_missing_result_and_too_fast_poll_fail(self):
        case = next(c for c in self.dataset['cases'] if c['label'] == 'review-wait')
        calls = [{'tool': s['tool'], 'args': s['args'], 'result': s['result'], 'wait_ms': 0} for s in case['steps']]
        self.assertTrue(bench.grade_case(self.catalog, case, {'calls': calls, 'final_status': 'completed'}))
        calls[0].pop('result')
        self.assertTrue(bench.grade_case(self.catalog, case, {'calls': calls, 'final_status': 'completed'}))

    def test_wrong_run_hash_or_missing_model_is_rejected(self):
        for run in ({'catalog_sha256': 'wrong', 'dataset_sha256': self.manifest['dataset_sha256'], 'provider': 'external', 'model': 'model-version', 'answers': []},
                    {'catalog_sha256': self.manifest['catalog_sha256'], 'dataset_sha256': self.manifest['dataset_sha256'], 'answers': []}):
            with self.assertRaises(ValueError):
                bench.report(self.catalog, self.dataset, self.manifest, run)

    def test_partial_run_keeps_every_missing_question_visible(self):
        case = next(c for c in self.dataset['cases'] if c['label'] == 'choice-project-readiness')
        run = {key: self.manifest[key] for key in ('catalog_sha256', 'dataset_sha256', 'guides_sha256')}
        run.update(provider='fixture-only', model='not-a-measured-model', model_version='synthetic-unit-test',
                   answers=[{'id': case['id'], 'calls': [{'tool': 'get_server_status',
                       'args': {'projectName': 'Demo', 'operation': 'metadata'},
                       'result': {'isError': False, 'data': {'success': True}}}]}])
        result = bench.report(self.catalog, self.dataset, self.manifest, run)
        self.assertEqual(1, result['answered'])
        self.assertEqual(1, result['passed'])
        self.assertEqual(len(self.dataset['cases']) - 1, len(result['unrun_ids']))
        self.assertEqual('synthetic-unit-test', result['model_version'])

    def test_malformed_calls_fail_without_crashing_the_report(self):
        case = next(c for c in self.dataset['cases'] if c['label'] == 'preview-confirm')
        for calls in ([42], [{'tool': [], 'args': {}}], [{'tool': 'delete_project', 'args': []}]):
            self.assertTrue(bench.grade_case(self.catalog, case, {'calls': calls}))

    def test_fixture_boolean_observation_cannot_be_replaced_by_a_number(self):
        case = next(c for c in self.dataset['cases'] if c['label'] == 'preview-confirm')
        calls = [{'tool': s['tool'], 'args': s['args'], 'result': copy.deepcopy(s['result'])} for s in case['steps']]
        calls[0]['result']['data']['confirmationRequired'] = 1
        self.assertTrue(bench.grade_case(self.catalog, case, {'calls': calls, 'final_status': 'completed'}))

    def test_read_request_cannot_pass_by_selecting_a_destructive_action(self):
        for label, tool, args in [
            ('choice-infobase_sessions', 'infobase_sessions', {'projectName': 'Demo', 'applicationId': 'app1', 'action': 'terminate', 'all': True, 'confirm': True}),
            ('choice-standalone_server', 'standalone_server', {'projectName': 'Demo', 'action': 'stop', 'confirm': True}),
            ('choice-project_checks', 'project_checks', {'projectName': 'Demo', 'action': 'deleteProfile', 'profile': 'Default'}),
        ]:
            case = next(c for c in self.dataset['cases'] if c['label'] == label)
            answer = {'calls': [{'tool': tool, 'args': args, 'result': {'isError': False, 'data': {'success': True}}}]}
            self.assertTrue(bench.grade_case(self.catalog, case, answer))

    def test_any_fresh_request_id_is_valid_but_reusing_the_old_one_is_not(self):
        case = next(c for c in self.dataset['cases'] if c['label'] == 'stale-review')
        calls = [{'tool': s['tool'], 'args': copy.deepcopy(s['args']), 'result': s['result'], 'wait_ms': s.get('min_wait_ms', 0)} for s in case['steps']]
        calls[3]['args']['requestId'] = '3c8a03e9-d536-4d36-a9fe-87bc2ce403e4'
        answer = {'calls': calls, 'final_status': 'completed'}
        self.assertEqual([], bench.grade_case(self.catalog, case, answer))
        calls[3]['args']['requestId'] = calls[0]['args']['requestId']
        self.assertTrue(bench.grade_case(self.catalog, case, answer))

    def test_staging_keeps_ground_truth_and_repo_instructions_out(self):
        with tempfile.TemporaryDirectory() as folder:
            stage = Path(folder) / 'runner'
            bench.stage(self.catalog, self.dataset, self.manifest, stage)
            text = (stage / 'requests.json').read_text()
            self.assertTrue(all(__import__('re').fullmatch(r'c\d{3}', item['id']) for item in json.loads(text)))
            self.assertNotIn('expected_tools', text)
            self.assertNotIn('final_status', text)
            self.assertNotIn('steps', text)
            self.assertFalse((stage / 'CLAUDE.md').exists())
            with self.assertRaises(ValueError):
                bench.stage(self.catalog, self.dataset, self.manifest, bench.ROOT / '.invalid-benchmark-stage')


if __name__ == '__main__':
    unittest.main()
