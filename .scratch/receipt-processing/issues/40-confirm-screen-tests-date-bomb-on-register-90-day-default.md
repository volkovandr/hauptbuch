# Two ReceiptConfirmScreenIntegrationTest register assertions broke when the calendar passed 2026-11-01

Status: resolved
Category: bug (test only — no production defect)
Severity: minor
Area: `ReceiptConfirmScreenIntegrationTest` (integration tier), register default date range

## Symptom
`./gradlew integrationTest` on 2026-11-02: 1017 tests, 2 failed, both in
`ReceiptConfirmScreenIntegrationTest`:

- `confirmTagsThePayingLegWithWhatEveryLineShares` — `GET /register?accountId=…` lacks `#Trips:France-2026`.
- `confirmingAllZeroReceiptBooksPendingReviewPlaceholderThatRendersMutedInTheRegister` — the page lacks `register__row--pending`.

Both fail on the *register page* assertion only; the ledger assertions before it (tags on legs,
`pending_review` lifecycle, legs) pass, so confirm itself works.

## Root cause
The tests book their transaction on the hard-coded `DAY = "2026-08-03"` (line 50), then fetch
`/register` with only `accountId`. With no date filter the register defaults to the **last 90 days**
(`RegisterController`, `DatePreset.LAST_90_DAYS.rangeAt(LocalDate.now(clock))`, register §2.3).
2026-08-03 + 90 days = 2026-11-01, so from 2026-11-02 the row is outside the default window and the
page renders without it. The suite was green until then; nothing in the code changed.

This is a date bomb: any test that books on a fixed past date and then reads the register through the
default range will fail the same way once that date ages out.

## Proposed solution (not yet accepted)
In the two tests, pass an explicit range on the register request that contains `DAY`
(`fromDate`/`toDate` params), rather than depend on the default window. Do not move `DAY` to
"today": the other tests use it for date-sensitive assertions. Then grep the integration and
browser tiers for other `get("/register")` calls without a range after a fixed date, and fix the
same way.

## Tests
The two failing tests are the verification; re-run `./gradlew integrationTest`.

## Comments
