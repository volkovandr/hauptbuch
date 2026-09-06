# Remove the both-split mirror residual machinery (dead code for a Money-impossible state)

Status: ready-for-agent
Category: bug
Severity: minor (dead code + a latent false-positive path; no user-visible bug today)
Area: Import review — mirror matching & issues list (`ImportMirrorRepository`, `ImportIssuesPanel`, `ImportIssues`, `templates/import-review.html`)

## Background

e4 built a dedicated surface for the "same-currency transfer whose **both** sightings are a Money
split" residual: `MATCHED_PAIRS` excludes such a pair (`not (a.non_funding_legs > 1 and
b.non_funding_legs > 1)`), `ImportMirrorRepository.unresolvedSplitMirrors` /
`UNRESOLVED_SPLIT_MIRROR_PAIRS` detects it, `ImportUnresolvedMirror` and `ImportIssues.MirrorRow`
carry it, `ImportIssuesPanel` maps it, and `import-review.html` renders an "Unresolved transfer …
Resolve by hand before committing" list item. Issues 04 and 05 (both now `wontfix`) were filed
against this surface.

Triage (2026-09-06, owner call) established the residual **cannot occur** from a real MS Money
export:

- A Money transfer has one **authored** side (which may be a split line, `S[Account B]`) and one
  **auto-generated** side. Money never lets you split the auto-generated far side of a transfer, so
  at most one sighting of any real transfer is a split — the split-vs-plain case, which
  `MATCHED_PAIRS` already resolves.
- Empirical: the full migration corpus (10 QIF files, ~20 years, 3,079 transfer legs, 717 split
  transactions containing a transfer line) has **zero** (date, amount) pairs with a split-authored
  transfer leg on both sides.
- The only way to produce two mutually-referencing split sightings is entering the same transfer
  as a split line in both accounts by hand — a duplicate to fix in Money, not something to book.

So the surfacing machinery is dead code. It is also mildly **harmful**: if two *unrelated*
same-day, same-amount split transfers between one account pair ever occurred,
`unresolvedSplitMirrors` would flag them as one "unresolved mirror" and tell the owner to resolve
two legitimately distinct transfers.

## What to remove

Delete the residual **surfacing** path end to end:

- `ImportMirrorRepository.unresolvedSplitMirrors` and the `UNRESOLVED_SPLIT_MIRROR_PAIRS` SQL constant.
- `ImportUnresolvedMirror` (record, now unused).
- `ImportIssues.MirrorRow` and the `unresolvedMirrors` component of the `ImportIssues` record —
  including its handling in the compact constructor, `EMPTY`, and `empty()`.
- `ImportIssuesPanel`'s `unresolvedMirrors` population and the `toMirrorRow` mapper.
- The `unresolvedMirrors` `<ul>` block in `templates/import-review.html`.
- Tests that exist only for this surface:
  - `ImportMirrorMatchingSqlLogicTest` — the `unresolvedSplitMirrors` assertions (see "keep" below).
  - `ImportIssuesPanelTest.takesTheUnresolvedParkLegCountFromItsCallerAndFormatsTheBothSplitMirrorResidual`
    and the `unresolvedSplitMirrors` stubbing in the other cases.
  - `ImportScreenIntegrationTest.issuesListSurfacesTheBothSplitMirrorResidualWithoutBlockingTheGate`.

## What to KEEP

- **The `not (a.non_funding_legs > 1 and b.non_funding_legs > 1)` guard in `MATCHED_PAIRS`.** It is
  load-bearing, not decoration: `matchAndMark`'s `else` branch assumes that when `legs != 1` the
  mirror side is the plain one. Without the guard a both-split pair would be marked `mirrored` on
  the whole split transaction and silently drop its category legs. Keep the guard; keep a focused
  test proving it (a trimmed `bothSightingsSplitLinksNothing` in `ImportMirrorMatchingSqlLogicTest`
  that asserts neither side links and both stay `ready`, with a comment that this defends a
  Money-impossible state).

## Docs

- `docs/implementation-plan-import.md` — the e4 stage description (the "Unresolved mirrors" bullet
  and the `Done when` test list) and the v0.22 changelog entry: trim to reflect that the both-split
  residual is not surfaced, only guarded. Do **not** rewrite history wholesale — a short "superseded
  by issue 07" note in the style of the existing `expect-file` supersession notes is enough.
- `docs/import.md` — §6 does not describe the residual as a concept, so likely no change; confirm
  while editing.

## Agent Brief

**Category:** bug (dead-code / latent false-positive removal)
**Summary:** Delete the both-split mirror residual surfacing machinery; keep the `MATCHED_PAIRS` guard.

**Current behavior:**
`ImportMirrorRepository.unresolvedSplitMirrors` detects same-currency transfer pairs where both
sightings are splits and `ImportIssuesPanel` / `ImportIssues.unresolvedMirrors` /
`import-review.html` surface them as "resolve by hand before committing". This state is
unreachable from a real Money export and the detector can false-positive on two unrelated
same-day same-amount split transfers between one account pair.

**Desired behavior:**
The `unresolvedMirrors` concept is gone from the repository, the `ImportIssues` record, the panel,
and the template. Mirror matching itself is unchanged: split-vs-plain pairs still resolve, and a
both-split pair still links nothing (the guard stays). The review page renders identically for
every real campaign.

**Key interfaces:**
- `ImportIssues` record: drop the `List<MirrorRow> unresolvedMirrors` component and `MirrorRow`
  nested record; update `EMPTY`, the compact constructor, and `empty()`.
- `ImportMirrorRepository`: remove `unresolvedSplitMirrors` and `UNRESOLVED_SPLIT_MIRROR_PAIRS`;
  keep `SAME_CURRENCY_RANKED_TRANSFER_LEGS`, `MATCHED_PAIRS` (guard intact), and `matchAndMark`.
- `ImportIssuesPanel`: stop populating `unresolvedMirrors`.
- `ImportUnresolvedMirror`: delete.
- `import-review.html`: remove the `unresolvedMirrors` list block; leave the rest of the `#issues`
  section untouched.

**Acceptance criteria:**
- [ ] No production or test source references `unresolvedMirrors`, `unresolvedSplitMirrors`,
      `ImportUnresolvedMirror`, or `MirrorRow`
      (`grep -rn 'unresolvedMirrors\|unresolvedSplitMirrors\|ImportUnresolvedMirror\|MirrorRow' src/`
      is empty).
- [ ] `ImportMirrorMatchingSqlLogicTest` still proves: split-vs-plain resolves (one side
      `mirrored`), and a both-split pair links nothing with both sides `ready`.
- [ ] The `not (a.non_funding_legs > 1 and b.non_funding_legs > 1)` guard is still present in
      `MATCHED_PAIRS`.
- [ ] `docs/implementation-plan-import.md` e4 stage + changelog reflect the removal in a short note.
- [ ] `./gradlew check` fully green.

**Out of scope:**
- Any change to how split-vs-plain mirrors resolve, to cross-currency parking, or to the commit
  gate conditions in `ImportIssues.locked()`.
- Adding a duplicate-scan-style adjudication for the (unreachable) both-split case.
- Touching `ImportMirrorRepository.matchAndMark`'s branch logic.

## Comments

> *This was generated by AI during triage.*

Filed 2026-09-06 out of the triage of issues 04 and 05. See those files (both `wontfix`) for the
full "why Money cannot produce this" reasoning and the corpus scan. Owner chose triage option 2:
close 04 + 05 as `wontfix`, file this cleanup separately.

### 2026-09-06 — implemented, owner-confirmation pending

Branch `import/qif-plan`, commit `227aa75`. `./gradlew check` fully green.

Removed `ImportMirrorRepository#unresolvedSplitMirrors` + `UNRESOLVED_SPLIT_MIRROR_PAIRS`,
`ImportUnresolvedMirror`, `ImportIssues.MirrorRow` + the record's `unresolvedMirrors` component
(5-arg → 4-arg constructor), the `ImportIssuesPanel` mirror mapping (the panel no longer injects
`ImportMirrorRepository`), the `import-review.html` block, and the dead-only tests
(`ImportScreenIntegrationTest.issuesListSurfacesTheBothSplitMirrorResidualWithoutBlockingTheGate`;
the `unresolvedSplitMirrors` assertions in `ImportMirrorMatchingSqlLogicTest`, including the
now-empty `unresolvedSplitMirrorsIsEmptyWhen…` case).

Kept the `MATCHED_PAIRS` `not (a.non_funding_legs > 1 and b.non_funding_legs > 1)` guard with an
updated docstring, and a trimmed `bothSightingsSplitLinksNothing` (asserts neither side links and
both stay `ready`). Docs: `implementation-plan-import.md` e4 stage + v0.27 changelog. `import.md`
§6 needed no change (it never described the residual as a concept).
