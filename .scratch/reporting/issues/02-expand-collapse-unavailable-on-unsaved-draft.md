# Expand/collapse is unavailable while viewing an unsaved draft — inconvenient in practice

Status: ready-for-agent
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

Triaged 2026-09-23 (owner): **scheduled as its own slice directly after reporting stage e5**, not
folded into e5. It is now more urgent, because e5's second dropdown per axis will have the operator
exploring unsaved drafts much more often. Scope decisions for whoever picks it up:

- **Amend `reporting.md` §9.1 in the same change.** Replace "falls back to its initial setting each
  time, which is fine because it is throwaway" with: a draft's expansion state is ephemeral. It
  survives toggles and settings changes on the same page, but not a reload, and is never persisted.
- Where the draft's expanded keys live between requests is the open design point. The options are a
  hidden form field carried along with the settings-strip resubmits (no JS, fits htmx), or a query
  param. §9.1 currently says expansion state is "deliberately not in the URL", so choose the hidden
  field unless there's a reason not to.
- When a draft is saved, its current expansion state should become the saved Report's
  `expanded_node_keys`.
- Cover it in the integration tier: toggle on a draft (`/reports/new` and a saved Report with
  unsaved edits), change a setting, and assert the expansion is still rendered.

Implemented 2026-09-24 (branch `feat/reporting`), awaiting owner confirmation. A draft's expansion
travels as an `expanded` parameter in the page's own state, and every settings form and both Save
forms carry it. On a saved Report with no edits, the toggle still POSTs and persists. Everywhere else
(a draft, a Preset, `/reports/new`) it re-GETs the page with the toggled set and replaces the URL.
Toggling on an untouched page carries only the expansion, so the page does not become a draft. A
draft starts from the saved Report's remembered expansion, and Save / Save as new store the draft's.
`reporting.md` §9.1/§9.2 amended (v0.3).

Deviation from the triage, for the owner to confirm: the hidden field lands in the address bar
anyway, since every settings form uses `hx-replace-url`. So a draft's expansion also survives a
reload or bookmark of the draft URL, like the rest of the draft (§11a.1). Keeping it out of the URL
would need the server to rewrite the replaced URL on every response.
