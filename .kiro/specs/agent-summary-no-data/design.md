# Agent Summary No-Data Bugfix Design

## Overview

The AI agent's Summary tab always shows "No summary data available" even when log data exists in MongoDB. This is caused by a chain of five defects across three files (`db.py`, `main.py`, `role_agent.py`). The fix is surgical: add optional time-range parameters to `fetch_logs()`, wire time-range parsing into `agent_query()`, add a service-filter fallback retry, and format the summary dict into a human-readable `answer` string before returning the response. No changes are needed to QA mode, the `/ask` endpoint, or PDF generation logic.

---

## Glossary

- **Bug_Condition (C)**: The set of `AgentQueryRequest` inputs with `mode = "summary"` that trigger the "No summary data available" symptom — caused by any of the five identified defects.
- **Property (P)**: The desired behavior for buggy inputs — the response must contain a non-empty `answer` string alongside `pdf_url` when log data exists.
- **Preservation**: All existing behavior for QA mode, the `/ask` endpoint, admin/developer scoping, PDF generation, and backward-compatible `fetch_logs()` calls that omit time range arguments.
- **`fetch_logs()`**: The function in `agent/db.py` that queries MongoDB for logs filtered by role and services. Currently accepts no time-range parameters.
- **`agent_query()`**: The FastAPI POST handler in `agent/main.py` that dispatches to QA or summary mode. Currently does not parse or pass time range.
- **`generate_summary()`**: The function in `agent/role_agent.py` that delegates to `_build_summary_context()`. Returns a hardcoded failure dict when `logs` is empty, with no retry.
- **`_build_summary_context()`**: The function in `agent/role_agent.py` that computes the summary dict. Places "No data available" in `insights[]`, not in `answer`.
- **`parse_time_range()`**: The existing utility in `agent/ai.py` that extracts a `{start, end, label}` dict from a natural-language query string.
- **`isBugCondition(X)`**: Pseudocode predicate — returns `true` when a summary request will produce an empty `answer` due to any of the five defects.

---

## Bug Details

### Bug Condition

The bug manifests when a user submits a request with `mode = "summary"`. The response always has `answer = ""` because: (1) `main.py` never sets `answer` in summary mode; (2) `fetch_logs()` ignores any time range in the query; (3) an empty fetch result causes `generate_summary()` to return immediately with no retry; (4) `_build_summary_context()` puts the "no data" string in `insights[]` rather than `answer`; and (5) even on success, `main.py` only passes `pdf_url` to `_success_response()`.

**Formal Specification:**
```
FUNCTION isBugCondition(X)
  INPUT: X of type AgentQueryRequest
  OUTPUT: boolean

  IF X.mode ≠ "summary" THEN RETURN false

  RETURN (
    // Defect 1 & 4: summary mode never sets answer field
    answer_field_in_success_response_for_summary(X) = ""

    // Defect 2: time range phrase in query is silently ignored
    OR (contains_time_range_phrase(X.query)
        AND fetch_logs_receives_no_time_bounds())

    // Defect 3: empty fetch result causes immediate failure, no retry
    OR (fetch_logs_returns_empty(X.role, X.services)
        AND no_service_filter_fallback_attempted())

    // Defect 5: "no data" placed in insights[], not answer
    OR (logs_empty AND summary_context_insights_contains_no_data_string()
        AND answer_field = "")
  )
END FUNCTION
```

### Examples

- **Admin, "last 7 days", data exists**: `fetch_logs("admin", [])` returns 500 logs. `generate_summary()` succeeds. `main.py` calls `_success_response(pdf_url=...)` — `answer` is `""`. Frontend shows "No summary data available". *(Defect 1/4)*
- **Developer, "last 15 days", data exists but service filter mismatch**: `fetch_logs("developer", ["auth-service"])` returns `[]` because the stored field is `serviceName` not `service` (or the service name has a casing difference). `generate_summary([])` returns `{insights: ["No data available for your role"]}`. No retry is attempted. *(Defect 3)*
- **Admin, "errors this week", data exists**: Query contains "this week" but `fetch_logs()` has no `from_time`/`to_time` parameters, so all logs ever stored are returned regardless of the time phrase. *(Defect 2)*
- **Any role, empty collection**: `_build_summary_context([])` returns `{insights: ["No data available for your role"]}`. The string is inside `insights[]`, not in `answer`. `main.py` never reads `insights[]` to build an `answer`. *(Defect 5)*

---

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- QA mode (`mode = "qa"`) must continue to call `answer_query()` and return `{ success: true, answer: "<text>" }` exactly as before.
- The `/ask` endpoint must continue to operate independently and call `ask_ai()` from `role_agent.py` unchanged.
- Admin requests with no service filter must continue to fetch all logs across all services.
- Developer requests must continue to be scoped to their assigned services.
- PDF generation via `generate_pdf_report()` must continue to produce a valid PDF and return its path.
- `fetch_logs()` called without `from_time`/`to_time` arguments must continue to return all matching logs (backward-compatible default `None` means no time filter applied).
- `generate_summary()` and `_build_summary_context()` internal logic for non-empty log lists must remain unchanged.

**Scope:**
All inputs where `X.mode ≠ "summary"` are completely unaffected by this fix. This includes:
- All QA mode requests via `/api/agent/query`
- All `/ask` endpoint requests
- Any direct calls to `fetch_logs()`, `generate_summary()`, or `generate_pdf_report()` from other code paths

---

## Hypothesized Root Cause

Based on code inspection of `main.py`, `db.py`, and `role_agent.py`:

1. **`main.py` summary mode never sets `answer`** (`agent_query()`, lines ~100–115): The summary branch calls `_success_response(pdf_url=f"/api/agent/reports/{pdf_name}")` and never passes an `answer` argument. `_success_response()` defaults `answer` to `""`. The frontend reads `response?.answer` and gets an empty string, triggering the fallback message.

2. **`db.py` `fetch_logs()` has no time-range parameters**: The function signature is `fetch_logs(role, services)`. There is no `from_time` or `to_time` parameter and no `timestamp` filter in the MongoDB query. Any time phrase in the user's query is silently discarded.

3. **`main.py` does not parse or pass time range**: Even though `parse_time_range()` exists in `ai.py`, `agent_query()` never imports or calls it. The `payload.query` string is passed to `answer_query()` / `generate_summary()` but the extracted time bounds are never forwarded to `fetch_logs()`.

4. **`generate_summary()` in `role_agent.py` returns hardcoded failure with no retry**: The guard at the top of `generate_summary()` checks `if not logs` and immediately returns a failure dict. There is no retry, no fallback to a broader query, and no signal back to `main.py` that the emptiness might be due to a filter mismatch rather than truly absent data.

5. **`_build_summary_context()` puts "No data available" in `insights[]`**: When `normalized` is empty, the function returns `{..., 'insights': ['No data available for your role'], ...}`. The `answer` field does not exist in this dict. `main.py` never reads `insights[]` to construct an `answer` string, so the frontend always receives `answer = ""`.

---

## Correctness Properties

Property 1: Bug Condition — Summary Mode Returns Non-Empty Answer When Data Exists

_For any_ `AgentQueryRequest` where `mode = "summary"` and MongoDB contains at least one log document accessible to the given role and services, the fixed `agent_query()` function SHALL return `{ success: true, answer: "<non-empty formatted text>", pdf_url: "<path>" }` where `answer` is a human-readable summary string beginning with `"Summary Report:\n"`.

**Validates: Requirements 2.1, 2.4, 2.5**

Property 2: Preservation — Non-Summary Requests Are Unaffected

_For any_ `AgentQueryRequest` where `mode ≠ "summary"`, or for any call to the `/ask` endpoint, the fixed code SHALL produce exactly the same response as the original code, preserving all existing QA mode, `/ask` endpoint, and PDF generation behavior.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6**

---

## Fix Implementation

### Changes Required

Assuming the root cause analysis above is correct:

---

**File**: `agent/db.py`

**Function**: `fetch_logs()`

**Specific Changes**:

1. **Add optional time-range parameters**: Change the signature to:
   ```python
   def fetch_logs(
       role: str,
       services: list[str] | None,
       from_time: datetime | None = None,
       to_time: datetime | None = None,
   ) -> list[dict[str, Any]]:
   ```
   Default `None` for both parameters preserves backward compatibility — all existing callers that omit them continue to work without modification.

2. **Apply timestamp filter when provided**: After building the base `query` dict (admin or developer branch), inject a `timestamp` range clause only when at least one bound is supplied:
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

3. **Update the `fetchLogs` alias** at the bottom of the file to match the new signature (it is a simple alias so no change is needed beyond the function itself).

---

**File**: `agent/main.py`

**Function**: `agent_query()`

**Specific Changes**:

1. **Import `parse_time_range`**: Add to the imports at the top of `main.py`:
   ```python
   from ai import parse_time_range
   ```

2. **Parse time range from query**: At the start of the `try` block in `agent_query()`, before calling `fetch_logs()`, parse the time range:
   ```python
   time_range = parse_time_range(query)
   from_time = time_range.get("start")
   to_time = time_range.get("end")
   ```

3. **Pass time range to `fetch_logs()`**: Change the `fetch_logs()` call to:
   ```python
   logs = fetch_logs(role, services, from_time=from_time, to_time=to_time)
   ```

4. **Add service-filter fallback retry**: After the initial `fetch_logs()` call, if `logs` is empty and the role is `"developer"`, retry without the service filter to distinguish "data exists but filtered out" from "truly no data":
   ```python
   if not logs and role == "developer":
       admin_logs = fetch_logs("admin", None, from_time=from_time, to_time=to_time)
       if admin_logs:
           # Data exists in MongoDB but is filtered out by the service scope.
           # Return a clear scoped-empty message rather than a generic failure.
           return _success_response(
               answer="No log data found for your assigned services in the requested time range."
           )
       # Truly no data — fall through to the existing "No data available" path
   ```

5. **Format summary dict into human-readable `answer` string**: In the `summary` branch, after `generate_summary(logs)` succeeds and `pdf_path` is validated, build the `answer` text before calling `_success_response()`:
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

---

**File**: `agent/role_agent.py`

**No changes required.** `generate_summary()` and `_build_summary_context()` are correct for non-empty log lists. The hardcoded-failure guard in `generate_summary()` for empty logs is now superseded by the retry logic in `main.py` — by the time `generate_summary()` is called, `main.py` has already handled the empty-logs case. No modifications to `_build_summary_context()` are needed.

---

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate each defect on the unfixed code, then verify the fix works correctly and preserves all existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Write unit tests that call `agent_query()` directly with `mode = "summary"` and a mock MongoDB collection containing log documents. Assert that `response["answer"]` is non-empty. Run these tests on the UNFIXED code to observe failures and confirm the root cause.

**Test Cases**:
1. **Answer field is empty on success**: Call `agent_query()` with `mode = "summary"`, mock `fetch_logs()` to return 10 log dicts, assert `response["answer"] != ""` — will fail on unfixed code because `_success_response(pdf_url=...)` never sets `answer`. *(Confirms Defect 1/4)*
2. **Time range is ignored**: Call `fetch_logs("admin", None)` with a MongoDB collection containing logs from two different months, then call it again with `from_time` / `to_time` bounding one month — both calls should return the same count on unfixed code because no time filter exists. *(Confirms Defect 2)*
3. **No retry on empty service-scoped fetch**: Call `agent_query()` with `role = "developer"`, `services = ["nonexistent-service"]`, mock MongoDB to have logs for other services — assert the response distinguishes "scoped empty" from "truly empty". Will fail on unfixed code because no retry is attempted. *(Confirms Defect 3)*
4. **"No data" string in insights, not answer**: Call `generate_summary([])` directly, assert `result["insights"][0] == "No data available for your role"` and `result.get("answer")` is `None` — confirms Defect 5 by showing the string is in the wrong field.

**Expected Counterexamples**:
- `response["answer"]` is `""` even when `response["pdf_url"]` is a valid path
- `fetch_logs()` returns identical results regardless of `from_time`/`to_time` arguments (because the parameters don't exist yet)
- Possible causes: missing `answer` assignment in summary branch, missing time-filter in MongoDB query, missing retry logic

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**
```
FOR ALL X WHERE isBugCondition(X) AND mongo_has_logs_for(X.role, X.services) DO
  response ← agent_query_fixed(X)
  ASSERT response["success"] = true
  ASSERT response["answer"] ≠ ""
  ASSERT response["answer"].startswith("Summary Report:")
  ASSERT response["pdf_url"] ≠ ""
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed function produces the same result as the original function.

**Pseudocode:**
```
FOR ALL X WHERE NOT isBugCondition(X) DO
  ASSERT agent_query_original(X) = agent_query_fixed(X)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many test cases automatically across the input domain (varying queries, roles, service lists)
- It catches edge cases that manual unit tests might miss (e.g., empty service list, unusual query strings)
- It provides strong guarantees that QA mode behavior is unchanged for all non-summary inputs

**Test Plan**: Observe QA mode behavior on UNFIXED code first, capture the response shape, then write property-based tests asserting the same shape is preserved after the fix.

**Test Cases**:
1. **QA mode preservation**: For any `AgentQueryRequest` with `mode = "qa"`, assert `response["answer"]` is a non-empty string and `response["pdf_url"]` is `""` — same as before the fix.
2. **`fetch_logs()` backward compatibility**: Call `fetch_logs(role, services)` without `from_time`/`to_time` — assert it returns the same results as before (no time filter applied).
3. **`/ask` endpoint preservation**: POST to `/ask` with any valid `AskRequest` — assert the response shape `{"type": "text", "answer": "..."}` is unchanged.
4. **PDF generation preservation**: Assert that `pdf_url` is still present and non-empty in the summary response after the fix.

### Unit Tests

- Test `fetch_logs()` with `from_time`/`to_time` — verify MongoDB query includes `timestamp` range filter
- Test `fetch_logs()` without `from_time`/`to_time` — verify no `timestamp` filter is added (backward compat)
- Test `agent_query()` summary mode with non-empty logs — verify `answer` starts with `"Summary Report:"`
- Test `agent_query()` summary mode with empty developer-scoped fetch but data exists globally — verify scoped-empty message is returned
- Test `agent_query()` summary mode with truly empty MongoDB — verify `"No data available"` response
- Test `agent_query()` QA mode — verify `answer_query()` result is returned unchanged

### Property-Based Tests

- Generate random `AgentQueryRequest` values with `mode = "qa"` and verify `response["answer"]` is always a non-empty string (preservation of QA mode)
- Generate random `(role, services, from_time, to_time)` combinations and verify `fetch_logs()` with `from_time = None, to_time = None` always returns a superset of results compared to a bounded call (time filter correctness)
- Generate random summary dicts and verify `answer_text` formatting always produces a string containing all six expected fields (`Time Range Used`, `Role Applied`, `Total Logs Found`, `Key Errors`, `Top Services`, `Recommendations`)

### Integration Tests

- Full summary flow: POST `{ query: "last 7 days", mode: "summary", role: "admin", services: [] }` against a running agent with seeded MongoDB data — assert `response.answer` is non-empty and `response.pdf_url` resolves to a downloadable PDF
- Time-range scoping: POST with "last 1 day" vs "last 30 days" against a collection with logs spread over multiple weeks — assert the two responses report different `Total Logs Found` counts
- Developer scoping with valid services: POST with `role = "developer"` and a service name that exists in MongoDB — assert `response.answer` contains that service name in `Top Services`
- Developer scoping with no matching services: POST with `role = "developer"` and a service name that does not exist — assert `response.answer` contains the scoped-empty message, not a generic error
