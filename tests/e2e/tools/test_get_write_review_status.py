"""Read-only BSL review lookup errors; attended success cases use review_probe.py."""

from harness import call, assert_error, assert_error_quality, assert_no_diff, e2e_test


@e2e_test(tool="get_write_review_status", kind="read")
def test_get_write_review_status_missing_identity_is_actionable():
    error = assert_error(call("get_write_review_status", {}), "missing reviewId or requestId")
    assert_error_quality(error, names=["reviewId", "requestId"], suggests=["Exactly one", "required"])
    assert_no_diff()


@e2e_test(tool="get_write_review_status", kind="read")
def test_get_write_review_status_blank_id_is_actionable():
    error = assert_error(call("get_write_review_status", {"reviewId": " "}), "blank reviewId")
    assert_error_quality(error, names=["reviewId"], suggests=["write_module_source"])
    assert_no_diff()


@e2e_test(tool="get_write_review_status", kind="read")
def test_get_write_review_status_unknown_id_is_not_a_rejection():
    unknown = "e2e-review-not-retained"
    error = assert_error(call("get_write_review_status", {"reviewId": unknown}), "unknown reviewId")
    assert_error_quality(error, names=["reviewId", unknown], suggests=["Read the module", "restart"])
    assert_no_diff()


@e2e_test(tool="get_write_review_status", kind="read")
def test_get_write_review_status_requires_one_identity():
    result = call("get_write_review_status", {"reviewId": "review", "requestId": "request"})
    error = assert_error(result, "ambiguous receipt/review identity")
    assert_error_quality(error, names=["reviewId", "requestId"], suggests=["Exactly one"])
    assert_no_diff()


@e2e_test(tool="get_write_review_status", kind="read")
def test_get_write_review_status_unknown_request_id_is_actionable():
    unknown = "e2e-request-not-retained"
    error = assert_error(call("get_write_review_status", {"requestId": unknown}), "unknown requestId")
    assert_error_quality(error, names=["requestId", unknown], suggests=["Read the module", "retention"])
    assert_no_diff()
