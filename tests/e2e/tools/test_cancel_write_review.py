"""Withdrawal errors are non-mutating; success/races use registry and workbench fixtures."""

from harness import call, assert_error, assert_error_quality, assert_no_diff, e2e_test


@e2e_test(tool="cancel_write_review", kind="action")
def test_cancel_write_review_missing_id_is_actionable():
    error = assert_error(call("cancel_write_review", {}), "missing reviewId")
    assert_error_quality(error, names=["reviewId"], suggests=["required"])
    assert_no_diff()


@e2e_test(tool="cancel_write_review", kind="action")
def test_cancel_write_review_blank_id_is_actionable():
    error = assert_error(call("cancel_write_review", {"reviewId": " "}), "blank reviewId")
    assert_error_quality(error, names=["reviewId"], suggests=["write_module_source"])
    assert_no_diff()


@e2e_test(tool="cancel_write_review", kind="action")
def test_cancel_write_review_unknown_id_does_not_claim_cancellation():
    unknown = "e2e-review-not-retained"
    error = assert_error(call("cancel_write_review", {"reviewId": unknown}), "unknown reviewId")
    assert_error_quality(error, names=["reviewId", unknown], suggests=["Read the module", "restart"])
    assert_no_diff()
