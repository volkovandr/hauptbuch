# Hauptbuch

A self-hosted, single-user double-entry ledger (Microsoft Money replacement). The five design docs
under `docs/` are authoritative for domain rules; this glossary pins the canonical *terms* so
issues, tests, and discussions don't drift into synonyms.

## Language

### Receipts

**Receipt**:
A captured scan of a paper receipt moving through a lifecycle toward (at most) one transaction. A
receipt may die without a transaction; a transaction has at most one receipt.
_Avoid_: attachment, scan, document

**Receipt state**:
The stored lifecycle position of a receipt (new, pre-processed, processing, processed, committed,
discarded, failed). Drives the register filter. Distinct from the workflow step.
_Avoid_: status, stage (for the stored value)

**Workflow step**:
The UI surface currently shown for a receipt (pre-process, process, post-process, confirm). Gated
by state but not the same axis — a step is where you *are*, a state is what the receipt *is*.
_Avoid_: state (for the UI position), phase

**Discarded**:
A receipt deliberately not booked (junk, true duplicate) — kept for the record. Not deleted:
soft-delete is the orthogonal "remove this row" axis.
_Avoid_: deleted, rejected

### AI parsing

**AI Vocabulary**:
The operator-curated projection of the category taxonomy that receipt parsing may see — per
category an alias, a hide flag, and a category AI note. The only category information ever sent to
an AI provider. Owned by the categories concept, consumed by receipt parsing.
_Avoid_: category list, taxonomy export, custom nodes

**Category AI note**:
Freetext guidance attached to a category, injected into the prompt to steer how items under it are
filed — including instructing per-line tags or a beneficiary ("diesel → tag Car:Audi"). The AI only
echoes names such a note supplies; echoes resolve against live entities or are dropped.
_Avoid_: custom node, term, rule engine

**AI note (per-receipt)**:
Freetext guidance the operator attaches to one receipt before analysis to steer that one parse
("this is fuel"). Travels with the receipt; retained for re-analysis and audit.
_Avoid_: prompt, comment
