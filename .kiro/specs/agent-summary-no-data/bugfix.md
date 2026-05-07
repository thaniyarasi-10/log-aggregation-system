# Bugfix Requirements Document

## Introduction

The AI agent's Summary tab in `FloatingAgent.jsx` always displays "No summary data available" even when log data exists in MongoDB. This is caused by a chain of five related defects: the backend summary mode never returns a text `answer` field (only a `pdf_url`), `fetch_logs()` ignores time range parameters so queries are unbounded, `generate_summary()` returns a hardcoded failure string when logs are empty rather than retrying, the summary mode endpoint has no code path to produce a human-readable text answer, and `_build_summary_context()` places the "no data" message inside an `insights` list item rather than the `answer` field the frontend reads. Together these defects mean the frontend always receives an empty `answer` and shows the fallback message.

---

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN the user submits a summary request and log data exists in MongoDB THEN the system returns `{ success: true, answer: "", pdf_url: "..." }` with an empty `answer` field, causing the frontend to display "No summary data available"

1.2 WHEN the user includes a time range phrase (e.g. "last 15 days") in the summary query THEN the system ignores the time bounds and queries all logs ever stored, because `fetch_logs()` accepts no `from_time`/`to_time` parameters and `main.py` does not pass them

1.3 WHEN `fetch_logs()` returns an empty list (e.g. due to a service filter mismatch or an empty collection for the default query) THEN `generate_summary()` immediately returns a dict whose `insights` list contains the string `"No data available for your role"` with no retry or fallback

1.4 WHEN summary mode completes successfully THEN the system returns only `pdf_url` in the response and leaves `answer` as an empty string, because the summary code path calls `generate_pdf_report()` and passes the result to `_success_response(pdf_url=...)` without ever setting `answer`

1.5 WHEN `_build_summary_context()` receives an empty normalized log list THEN the system places `"No data available for your role"` inside the `insights` list field, not in the top-level `answer` field that the frontend reads via `response?.answer`

### Expected Behavior (Correct)

2.1 WHEN the user submits a summary request and log data exists in MongoDB THEN the system SHALL return `{ success: true, answer: "<formatted summary text>", pdf_url: "..." }` with a non-empty `answer` field so the frontend Summary tab renders the structured text

2.2 WHEN the user includes a time range phrase in the summary query THEN the system SHALL parse the time range, pass `from_time` and `to_time` to `fetch_logs()`, and filter the MongoDB query to only return documents whose `timestamp` falls within that range

2.3 WHEN `fetch_logs()` returns an empty list due to a role/service filter mismatch THEN the system SHALL retry the fetch without the service filter (falling back to all logs for the role) before concluding that no data is available

2.4 WHEN summary mode completes successfully THEN the system SHALL set the `answer` field to a human-readable formatted summary string (containing totals, top errors, most impacted service, and recommendations) in addition to the `pdf_url`

2.5 WHEN `_build_summary_context()` produces a valid summary dict THEN the system SHALL format that dict into a plain-text `answer` string in `main.py` before returning the response, so `response?.answer` is always a non-empty string when data exists

### Unchanged Behavior (Regression Prevention)

3.1 WHEN the user submits a QA (ask) mode request THEN the system SHALL CONTINUE TO return `{ success: true, answer: "<answer text>" }` with the existing `answer_query()` logic unchanged

3.2 WHEN the user submits a summary request as an admin with no service filter THEN the system SHALL CONTINUE TO fetch all logs across all services and generate the summary

3.3 WHEN the user submits a summary request as a developer with valid assigned services THEN the system SHALL CONTINUE TO scope the log fetch to only those services

3.4 WHEN `fetch_logs()` is called without time range arguments THEN the system SHALL CONTINUE TO return all matching logs (backward-compatible default), so existing callers that do not pass time bounds are unaffected

3.5 WHEN the summary mode generates a PDF THEN the system SHALL CONTINUE TO produce a valid PDF file and return its download URL in `pdf_url`

3.6 WHEN the `/ask` endpoint is called THEN the system SHALL CONTINUE TO operate independently of the `/api/agent/query` endpoint changes

---

## Bug Condition Pseudocode

### Bug Condition Function

```pascal
FUNCTION isBugCondition(X)
  INPUT: X of type AgentQueryRequest where X.mode = "summary"
  OUTPUT: boolean

  // Returns true when any of the five defects can trigger the "No summary data" symptom
  RETURN (
    X.mode = "summary"
    AND (
      // Defect 1: backend never sets answer for summary mode
      answer_field_of_response(X) = ""
      // Defect 2: time range in query is not passed to fetch_logs
      OR (contains_time_range_phrase(X.query) AND fetch_logs_ignores_time_bounds())
      // Defect 3: empty fetch result causes immediate failure with no retry
      OR (fetch_logs_returns_empty(X.role, X.services) AND no_fallback_retry())
    )
  )
END FUNCTION
```

### Property: Fix Checking

```pascal
// Property: Fix Checking — Summary mode must return non-empty answer when data exists
FOR ALL X WHERE isBugCondition(X) AND mongo_has_logs_for(X.role, X.services) DO
  response ← agent_query'(X)
  ASSERT response.success = true
  ASSERT response.answer ≠ ""
  ASSERT response.answer ≠ "No summary data available"
END FOR
```

### Property: Preservation Checking

```pascal
// Property: Preservation Checking — QA mode and non-summary paths must be unaffected
FOR ALL X WHERE NOT isBugCondition(X) DO
  ASSERT agent_query(X) = agent_query'(X)
END FOR
```
