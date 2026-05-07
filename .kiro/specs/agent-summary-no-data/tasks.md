# Implementation Plan

- [ ] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - Summary Mode Returns Empty Answer
  - **CRITICAL**: This test MUST FAIL on unfixed code — failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior — it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate all five defects exist
  - **Scoped PBT Approach**: Use `hypothesis` to generate `AgentQueryRequest`-shaped inputs with `mode="summary"` and mock MongoDB returning at least one log document; assert `response["answer"]` is non-empty
  - Create `agent/tests/test_bug_condition.py`
  - Mock `fetch_logs` to return a list of 10 realistic log dicts (service, level, message, timestamp, responseTime fields)
  - Mock `generate_pdf_report` to return a valid temp file path so the test isolates the `answer` defect
  - Call `agent_query()` directly (import the FastAPI handler, not via HTTP) with `mode="summary"`, `role="admin"`, `services=[]`
  - Assert `response["answer"] != ""` — this FAILS on unfixed code because `_success_response(pdf_url=...)` never sets `answer` (Defect 1/4)
  - Also write a direct unit assertion: call `fetch_logs("admin", None)` on a mock collection and confirm the function signature has no `from_time`/`to_time` params — documents Defect 2
  - Also call `generate_summary([])` directly and assert `result.get("answer")` is `None` while `result["insights"][0] == "No data available for your role"` — documents Defect 5
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS (this is correct — it proves the bug exists)
  - Document counterexamples found (e.g. `response["answer"] == ""` even when `response["pdf_url"]` is a valid path)
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.1, 1.4, 1.5_

- [ ] 2. Fix `db.py` — add time range parameters to `fetch_logs()`
  - Add `from_time: datetime | None = None` and `to_time: datetime | None = None` optional parameters to `fetch_logs()` signature
  - Import `datetime` is already present; ensure `from datetime import datetime` is used (not just the module)
  - After building the base `query` dict (admin or developer branch), inject a `timestamp` range clause only when at least one bound is supplied:
    ```python
    if from_time is not None or to_time is not None:
        time_filter: dict[str, Any] = {}
        if from_time is not None:
            time_filter["$gte"] = from_time
        if to_time is not None:
            time_filter["$lte"] = to_time
        if role_name == "admin":
            query["timestamp"] = time_filter
        else:
            # developer branch uses $or; wrap with $and to add timestamp
            query = {"$and": [query, {"timestamp": time_filter}]}
    ```
  - Update the `fetchLogs = fetch_logs` alias at the bottom of the file (no signature change needed — alias is a reference)
  - Backward-compatible: all existing callers that omit `from_time`/`to_time` continue to work unchanged
  - _Bug_Condition: isBugCondition(X) where X.mode = "summary" AND contains_time_range_phrase(X.query) AND fetch_logs_ignores_time_bounds()_
  - _Expected_Behavior: fetch_logs returns only documents whose timestamp falls within [from_time, to_time] when either bound is provided_
  - _Preservation: fetch_logs called without from_time/to_time returns all matching logs — backward-compatible default None means no time filter applied_
  - _Requirements: 1.2, 2.2, 3.4_

- [ ] 3. Fix `main.py` — wire time range parsing, fallback retry, and answer text formatting
  - Add `from ai import parse_time_range` to the imports at the top of `main.py`
  - At the start of the `try` block in `agent_query()`, before calling `fetch_logs()`, parse the time range from the query string:
    ```python
    time_range = parse_time_range(query)
    from_time = time_range.get("start")
    to_time = time_range.get("end")
    ```
  - Change the `fetch_logs()` call to pass the parsed bounds:
    ```python
    logs = fetch_logs(role, services, from_time=from_time, to_time=to_time)
    ```
  - Add service-filter fallback retry immediately after the initial `fetch_logs()` call — if `logs` is empty and `role == "developer"`, retry as admin to distinguish "scoped empty" from "truly empty":
    ```python
    if not logs and role == "developer":
        admin_logs = fetch_logs("admin", None, from_time=from_time, to_time=to_time)
        if admin_logs:
            return _success_response(
                answer="No log data found for your assigned services in the requested time range."
            )
        # Truly no data — fall through to the existing "No data available" path
    ```
  - In the `summary` branch, after `generate_summary(logs)` succeeds and `pdf_path` is validated, build the `answer` text before calling `_success_response()`:
    ```python
    time_range_label = time_range.get("label", "All time")
    role_label = role.title()
    total_logs = summary.get("totalLogs", 0)

    top_errors = summary.get("insights", [])
    key_errors_text = "; ".join(top_errors[:3]) if top_errors else "None detected"

    top_services = summary.get("services", [])
    top_services_text = ", ".join(
        s["name"] for s in top_services[:3] if isinstance(s, dict) and s.get("name")
    ) or "None"

    recommendations = summary.get("recommendations", [])
    recommendations_text = "; ".join(recommendations[:2]) if recommendations else "None"

    answer_text = (
        f"Summary Report:\n"
        f"- Time Range Used: {time_range_label}\n"
        f"- Role Applied: {role_label}\n"
        f"- Total Logs Found: {total_logs}\n"
        f"- Key Errors: {key_errors_text}\n"
        f"- Top Services: {top_services_text}\n"
        f"- Recommendations: {recommendations_text}"
    )

    return _success_response(
        answer=answer_text,
        pdf_url=f"/api/agent/reports/{pdf_name}",
    )
    ```
  - _Bug_Condition: isBugCondition(X) where X.mode = "summary" AND answer_field_of_response(X) = ""_
  - _Expected_Behavior: response["answer"].startswith("Summary Report:") AND response["answer"] != "" AND response["pdf_url"] != ""_
  - _Preservation: QA mode path is untouched; /ask endpoint is untouched; admin no-service-filter path is untouched; developer scoped path is untouched_
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 3.3_

- [ ] 4. Write fix-checking property test (run on FIXED code — expected to PASS)
  - **Property 1: Expected Behavior** - Summary Mode Returns Non-Empty Answer When Data Exists
  - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
  - The test from task 1 encodes the expected behavior; when it passes it confirms the fix is correct
  - Additionally, create `agent/tests/test_fix_checking.py` with a `hypothesis`-based property test:
    - Use `@given` to generate varied log lists (non-empty, with realistic service/level/message/timestamp fields)
    - For each generated log list, mock `fetch_logs` to return it and mock `generate_pdf_report` to return a valid temp path
    - Call `agent_query()` with `mode="summary"`, `role="admin"`, `services=[]`
    - Assert `response["success"] == True`
    - Assert `response["answer"] != ""`
    - Assert `response["answer"].startswith("Summary Report:")`
    - Assert `"Time Range Used:" in response["answer"]`
    - Assert `"Role Applied:" in response["answer"]`
    - Assert `"Total Logs Found:" in response["answer"]`
    - Assert `"Key Errors:" in response["answer"]`
    - Assert `"Top Services:" in response["answer"]`
    - Assert `"Recommendations:" in response["answer"]`
    - Assert `response["pdf_url"] != ""`
  - Also test the `fetch_logs()` time-range filter directly: call with `from_time`/`to_time` bounding a single day against a mock collection with logs spread over two months; assert only logs within the range are returned
  - Also test the developer fallback retry: mock `fetch_logs` to return `[]` for the developer-scoped call and a non-empty list for the admin retry; assert `response["answer"] == "No log data found for your assigned services in the requested time range."`
  - Run test on FIXED code
  - **EXPECTED OUTCOME**: Tests PASS (confirms bug is fixed)
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [ ] 5. Write preservation property test (run on FIXED code — expected to PASS)
  - **Property 2: Preservation** - QA Mode and Non-Summary Paths Are Unaffected
  - **IMPORTANT**: Follow observation-first methodology — observe QA mode behavior on unfixed code first, then encode those observations as property assertions
  - Create `agent/tests/test_preservation.py` with `hypothesis`-based property tests:
  - **QA mode preservation**: Use `@given` to generate varied query strings and log lists; for each, mock `fetch_logs` to return the log list and call `agent_query()` with `mode="qa"`, `role="admin"`, `services=[]`; assert `response["success"] == True` and `response["answer"] != ""` and `response["pdf_url"] == ""`
  - **`fetch_logs()` backward compatibility**: Use `@given` to generate `(role, services)` pairs; call `fetch_logs(role, services)` without `from_time`/`to_time` against a mock collection; assert the result is a superset of (or equal to) the result of `fetch_logs(role, services, from_time=some_past_time, to_time=some_future_time)` — i.e. omitting time params never returns fewer logs than a maximally wide time window
  - **Answer text formatting preservation**: Use `@given` to generate varied summary dicts; call the formatting logic directly and assert the output always contains all six expected section labels (`Time Range Used`, `Role Applied`, `Total Logs Found`, `Key Errors`, `Top Services`, `Recommendations`)
  - Observe: QA mode calls `answer_query()` and returns `{ success: True, answer: "<text>", pdf_url: "" }` — encode this shape as the invariant
  - Verify tests PASS on FIXED code
  - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

- [ ] 6. Checkpoint — Ensure all tests pass
  - Run the full test suite: `cd agent && python -m pytest tests/ -v`
  - Ensure task 1 exploration test now passes (was failing on unfixed code, now passes after fix)
  - Ensure task 4 fix-checking property tests pass
  - Ensure task 5 preservation property tests pass
  - Confirm no regressions in QA mode, `/ask` endpoint, or PDF generation paths
  - If any test fails, diagnose before patching — check whether the failure is in `db.py` time filter injection, `main.py` answer formatting, or the fallback retry logic
  - Ask the user if questions arise
