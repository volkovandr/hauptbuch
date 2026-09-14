# `fragments/report-table-body.html :: body` selector pulls the whole `<body>`, not just the table

Status: needs-triage
Severity: low
Area: Reporting (`analytics` module, `fragments/report-table-body.html`)

Every `th:replace="~{fragments/report-table-body :: body}"` call renders a spurious extra
`<body>...</body>` wrapper around the `<table class="report">` markup — visible with `curl` against
`/reports/preset/balance-sheet` (pre-existing, stage b) and now also through every Frame
(`fragments/frame.html`, plan stage d2) that shows a table. Root cause: the fragment's `th:fragment`
name (`body`) is the same string as its enclosing HTML `<body>` tag, and Thymeleaf's fragment
selector appears to resolve the ambiguity in favour of the tag rather than the named fragment.

Harmless in a real browser today — HTML5 parsing drops a stray mid-document `<body>` open tag and
its content simply lands in the page's one real `<body>`, so nothing visibly breaks — but it is
invalid markup that a future consumer (screen reader, htmx OOB swap, snapshot test) could trip on,
and it will now render at least once per configured Frame on `/reports`, `/reports/layout`, and the
main page.

Fix is presumably renaming the fragment (`th:fragment="tableBody"` or similar) and updating every
caller — out of scope for the change that found it (plan stage d2, which only added new callers of
the existing, already-affected fragment).

## Comments

Filed 2026-09-14 while manually verifying reporting-stage-d2 (the Frame-fragment rework) against the
dev server with `curl`.
