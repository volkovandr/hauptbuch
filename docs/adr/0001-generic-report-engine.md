# A generic report engine replaces the two hand-built reports

`requirements.md` originally specified reporting as two fixed screens — a category×month matrix
(FR-ANA-07) and a consolidated-balance timeline (FR-ANA-09) — with FR-ANA-08 explicitly forbidding
anything more graphical ("no large/cartoonish charts") and FR-ANA-09 calling the timeline "the one
graphical report relied upon". After returning to Microsoft Money in daily use, the owner concluded
that its **reporting breadth** was the thing worth reproducing, not two of its outputs: the value was
in being able to ask a new question without waiting for a screen to be built. We therefore built one
engine — dimensions on rows/columns/series, a measure list, scope and filters, four renderers — and
demoted the matrix and the timeline to **Presets** of it.

## Consequences

- **FR-ANA-08 was rewritten, not deleted.** Its intent (this must not become an over-graphical,
  number-hiding app) survives as a rule the engine can be held to: every chart's grid is one
  deterministic click away in the same frame, never hover-only. Without that replacement the engine
  would have quietly removed the only thing protecting `requirements.md` §5.0.
- **Expressiveness is pinned to an acceptance set**, not to "generic". The four reports the owner
  actually opened in Money (`reporting.md` §16) are the bar; features no report in that set needs
  were cut — most visibly `min`/`max`/`avg` over balances, which need an order-of-operations rule
  with a defensible and an indefensible answer and which nobody had asked a question requiring.
- **The engine must refuse to print meaningless numbers**, which a hand-built report never had to:
  closing balances summed along the time axis, account-currency measures spanning two currencies, and
  grand totals across overlapping tags all render `—`. Generality bought the need for a legality
  layer (`reporting.md` §7); two bespoke screens had their legality baked in by construction.
- **Charts stay server-rendered SVG** rather than a charting library, so the generalisation did not
  cost a fourth bespoke JS leaf (CLAUDE.md §1.6). Recorded as a rejected alternative in
  `tech-stack.md` rather than as its own ADR.
