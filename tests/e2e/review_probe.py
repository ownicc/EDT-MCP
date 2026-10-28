#!/usr/bin/env python3
"""Attended review probe against a disposable project copy; never accepts a review."""

import argparse
import json
import os
from pathlib import Path
import subprocess


def test_write_review_does_not_write_before_acceptance(project, module, directory):
    from harness import call, assert_ok

    target = directory / "src" / module
    before = target.read_bytes()
    status_before = subprocess.check_output(
        ["git", "-C", str(directory), "status", "--porcelain"], text=True)
    diff_before = subprocess.check_output(
        ["git", "-C", str(directory), "diff", "--binary", "HEAD"])
    result = call("write_module_source", {
        "projectName": project, "modulePath": module, "mode": "append",
        "source": "// EDT_MCP_REVIEW_PROBE_20261028\n",
    })
    assert_ok(result, "prepare attended BSL review")
    payload = result.structured
    assert payload is not None, result.text
    assert payload["status"] == "awaitingApproval", payload
    assert payload["applied"] is False, payload
    assert payload["reviewId"], payload
    assert target.read_bytes() == before, "Source changed before acceptance"
    assert subprocess.check_output(
        ["git", "-C", str(directory), "diff", "--binary", "HEAD"]) == diff_before
    assert subprocess.check_output(
        ["git", "-C", str(directory), "status", "--porcelain"], text=True) == status_before
    polled = call("get_write_review_status", {"reviewId": payload["reviewId"]})
    assert_ok(polled, "round-trip review ID")
    assert polled.structured["reviewId"] == payload["reviewId"]
    assert polled.structured["status"] == "awaitingApproval"
    assert "EDT_MCP_REVIEW_PROBE_20261028" not in json.dumps(polled.structured)
    return payload


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", default="8765")
    parser.add_argument("--project", default="KASL")
    parser.add_argument("--module")
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--review-id", help="Poll an existing review without submitting another write")
    args = parser.parse_args()
    os.environ["MCP_PORT"] = args.port
    from harness import initialize, call, assert_ok

    initialize()
    if args.review_id:
        result = call("get_write_review_status", {"reviewId": args.review_id})
        assert_ok(result, "review status")
        payload = result.structured
    else:
        if not args.module or not args.directory:
            parser.error("preparation requires --module and --directory of a disposable project copy")
        payload = test_write_review_does_not_write_before_acceptance(
            args.project, args.module, args.directory.resolve())
    print(json.dumps(payload, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
