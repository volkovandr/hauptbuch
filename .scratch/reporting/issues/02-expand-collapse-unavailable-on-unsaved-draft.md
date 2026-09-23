# Expand/collapse is unavailable while viewing an unsaved draft — inconvenient in practice

Status: needs-triage
Category: enhancement
Severity: medium
Area: Reporting (`analytics` module, stage e — row-tree expansion)

`reporting.md` §9.1 currently reads: "An unsaved Report falls back to its initial setting each time,
which is fine because it is throwaway." In practice this means the row-tree expand/collapse toggle
(`SavedReportController#toggleExpansion`, `/reports/{id}/expand`) only stays interactive on a saved
Report's own page with **zero** unsaved edits (`ReportSpecQueryString.isPresent(params)` is false).
The instant any settings-strip control resubmits — including reselecting Rows/Columns/Scope back to
their already-saved value — the page becomes an "unsaved draft," and the toggle silently swaps from
a clickable htmx button to a static, disabled span (`fragments/report-table-body.html`) until Save or
Discard.

Owner feedback after re-testing the stage e1/e2 tree-engine fixes (2026-09-23): this is confusing and
"very inconvenient" in normal use — trying a different Rows/Scope combination before deciding whether
to save it is a completely ordinary thing to want, and losing all expand/collapse the moment you do
makes exploring a draft report substantially worse than exploring a saved one. The disabled control
now at least *looks* disabled (opacity + a `title` tooltip, fixed same day as a smaller UX bug), but
the underlying limitation remains.

Fix would need expand/collapse to work against the **current effective spec** (from query params when
present, else the saved spec) rather than always `saved.spec()`/`saved.expandedNodeKeys()`, with the
resulting state kept **ephemeral** (not persisted) for a draft — matching §9.1's existing rule that
draft view state doesn't survive a reload, just extending it to also survive a same-page toggle.
Touches `SavedReportController`, `ReportEditorController` (the `/reports/new` case), and possibly
`PresetRendering`/`ReportTableViewAssembler` for how `toggleReportId` is threaded through. Real scope
increase, not a bug fix — worth planning as its own slice rather than folding into the stage e1/e2
follow-up that found it.

## Comments

Filed 2026-09-23 after the owner re-tested the stage e1/e2 nested-tree-engine bug-fix follow-up and
confirmed the three original fixes (multilevel expand, tags stuck fully-expanded, spurious triangles)
work correctly — the remaining friction they hit was this pre-existing, spec-endorsed "throwaway"
behavior, not a defect in that follow-up. See `.scratch/.workpackages-e` for the full diagnosis
(session-local, untracked).
