# Rethink which rows/columns/series dimension combinations are allowed

Status: needs-info
Category: enhancement
Severity: medium
Area: Reporting (`analytics` module: `ReportSpec` validation, `ReportEngine.axisPlan`, `AxisPlan`, `CellValuation`, `AutoExpansion.canNestUnder`, `ReportQueryRepository`; `docs/reporting.md` §3, §4, §8.2, §10)

## Trigger (owner)

**Account on rows × Currency on columns** is refused, yet it's a sensible report: the currency mix of
each account, and for a parent account like `Cash` the split of its children by currency. More
combinations like it are probably refused for no product reason. The owner asks for the whole set of
rules to be rethought, not just this one pair.

## The rules enforced today

| # | Rule | Where | Documented? |
|---|------|-------|-------------|
| 1 | At most **one non-Date dimension family** across rows and columns together. A cross-axis crosstab of two different dimensions (Category × Account, Account × Currency, Tag × Payee, …) is refused. | `ReportEngine` (message cites "reporting.md §3") | **No.** §3 only says that two dimensions on the *same* axis nest rather than multiply; it says nothing against rows and columns carrying different dimensions. This is a stage-a engine shortcut: `AxisPlan` has a single `nonDateDim`, and each cell is keyed by a single node. |
| 2 | Date can't be on both rows and columns. | `ReportEngine` | Implied. |
| 3 | Date can't combine with another dimension on the same axis (no `rows = [Category, Date]`). | `ReportSpec` | §8.2, loosely. |
| 4 | The same dimension can't repeat on one axis. | `ReportSpec` | §3. |
| 5 | Only a hierarchical dimension (Category/Account/Tag) can be the *outer* one when nesting: `Account > Currency` is allowed, `Currency > Account` isn't. | `AutoExpansion.canNestUnder` | §9.1, partly. |
| 6 | Rows and series can't both be set. | `ReportSpec` | §11a.3 calls it "a temporary engine limit". |
| 7 | Tag and Payee have no closing balance. | `ReportEngine` | §5 (correct: they aren't accounts). |

Rule 7 is a genuine domain rule, and 2 and 4 are sound. Rules 1, 3, 5 and 6 are engine limits that
look like design decisions from the outside.

## Questions to settle (grilling)

- **Cross-axis crosstab (rule 1).** Allow any non-Date dimension on rows × any on columns? Every cell
  becomes the intersection of two node subtrees. The e1 approach, a synthetic `IS_ONE_OF` subtree
  filter per outer node (§3 nesting), generalises to "row node filter AND column node filter". But
  a filter per cell means one query per cell, so it needs a real two-dimension grouped query
  instead. Which pairs make sense, and which are meaningless (e.g. Person × Category, §4.1)?
- **Column hierarchies.** A crosstab puts hierarchies on columns, which can't be expanded in v1
  (§9.1, Q-REP-2). Is a flat top level acceptable on columns, or does this pull column expansion
  forward? This interacts with issue 08's case 2, where ticked children would become top-level
  nodes, and with pies (issues 13/14, since columns are the slices).
- **Closing balance on a crosstab.** Account × Currency is fine, since both are account attributes.
  Category × Tag with a closing balance isn't (rule 7). State the legality rule per pair rather
  than listing exceptions.
- **Date with another dimension on one axis (rule 3).** Is `rows = [Category, Date]` (each category
  expanding into months) wanted, or is Date on the other axis always the better report?
- **Non-hierarchical outer (rule 5).** Is `Currency > Account` wanted?
- **Rows + series (rule 6).** Keep refusing, or build it?
- Record the outcome in `reporting.md` §3 as an explicit legality table, and fix the engine's
  "§3" citation, which doesn't back rule 1 today.

## Notes

- Of the rules above, only rule 1 blocks the owner's example. Lifting it is the engine change with
  the widest impact: `AxisPlan`, `CellValuation`'s single-key cell lookup, the candidate/turnover
  queries, `RowColumnSuppression` and the totals all assume one non-Date family. Plan it as its own
  slice, with `sqlLogicTest` coverage for the two-dimension grouping (cross-currency case included).
- The settings strip should make illegal pairs unenterable, or explain them, rather than surface an
  engine exception (§11a.3).

## Comments

Filed 2026-09-23 from owner testing at the close of reporting stage e. `needs-info` until the
grilling above produces a legality table. Likely a candidate for its own stage after f.
