# Category/Account filter: unticked nodes and their untouched parents still render as rows

Status: ready-for-agent
Category: bug
Severity: medium
Area: Reporting (`analytics` module: `ReportQueryRepository` candidate queries, `ReportDataFetcher`, `ReportGridBuilder`, `RowColumnSuppression`, `ReportEngine`; `docs/reporting.md` §4, §6.3, §7.3, §9)

## What happens

Two related cases with Account (or Category) as a dimension and a filter on the same field:

1. **Unticked sibling.** Untick one account in the Account filter. Its row (or column) still
   appears, with blank cells. It disappears only when *Suppress empty rows/columns* is ticked. This
   is because the candidate queries (`topLevelAccounts`, `childAccountCandidates`, …) list every
   node regardless of filters, and suppression is the only thing that hides a filtered-out one
   (`reporting.md` §7.3).
2. **Parent of ticked children.** The owner wants to see all Cash accounts but *not* the `Cash`
   parent row. They tick only the child accounts. The `Cash` parent still renders, with numbers,
   because a parent row is the subtotal of its (ticked) children. Suppression can't hide it because
   it isn't empty.

A third, related defect: the **Account** dimension lists whatever account types the Scope contains
(`ReportEngine` passes `spec.scope().accountTypes()` to `topLevelAccounts`), so with an `expense`
scope `Food`/`Rent`/`Salary` appear as "accounts" next to `BankAaa`. `reporting.md` §4 already says
Account covers `asset`/`liability`/`equity` and Category covers `income`/`expense`; the code
doesn't follow it. The owner found this the most confusing thing when experimenting.

## Decided (owner grilling, 2026-09-24)

The rule applies whenever a hierarchy dimension on an axis (Account, Category or Tag) has a filter on
the **same field**. One rule for all three hierarchies.

1. **The ticked nodes are the axis's top level.** Their ancestors do not render.
   - Tick `Cash-EUR` and `BankAaa` (a parent): the top level is exactly `Cash-EUR` and `BankAaa`
     (expandable). No `Cash` row.
   - Tick `Cash-EUR` and `Cash-USD` (all children of `Cash`): both are top-level; `Cash` still does
     not appear. Only ticking `Cash` itself puts `Cash` on the axis.
   - Tick grandchildren under different parents: only those grandchildren, at the top level, with
     none of the intermediate parents.
   - Ticking a parent ticks its whole subtree (grey, disabled ticks, as today, §11a.5). A parent and
     one of its descendants can't both be ticked, so the top level never contains a node and its
     own ancestor.
   - Nothing ticked means no filter: the real tree roots, as today.
2. **Ticked nodes always appear**, even with no postings in range. An empty one is blank and can be
   hidden by the *Suppress empty rows/columns* toggle, like any empty row.
3. **Unticked nodes appear only if actually touched.**
   - Under **"amounts booked to …"**: only the ticked nodes. Nothing else can carry a number.
   - Under **"transactions touching …"**: also every node that holds a posting in a qualifying
     transaction, rendered **under its normal tree root** (e.g. `BankBbb`, collapsed), not flat next
     to the ticked nodes. Such a node always carries a number, so suppression doesn't apply to it.
     Untouched nodes don't appear. For tags, "touched" means another tag on the same transactions,
     and `(unspecified)` keeps working under a ticked tag.
4. **A promoted node shows its full path** (`Food:Restaurants`, not `Restaurants`) when its parent
   isn't on the axis. Otherwise `General`/`Other` leaves become ambiguous at the top level. A node
   shown under its real parent keeps its short name.
5. **Account and Category follow §4 regardless of Scope.** The Account dimension lists only
   `asset`/`liability`/`equity` accounts; Category lists only `income`/`expense`. Account rows with
   an `expense`-only scope then show the existing scope-mismatch message
   (`ScopeDimensionMismatch`) instead of listing categories as accounts. Bringing categories onto
   the Account axis deliberately is issue 19's "Income/Expenses" option, not a side effect of
   Scope.
6. **Unchanged:** `auto` expansion (§9.2: exactly one ticked node starts expanded) and the
   subtotal / group-header parent setting apply to the promoted top level as to any other.

Note the rule also gives the owner's pie of `Cash` split per currency account (closing balance,
Base) without column expansion: tick `Cash`'s children, put Account on columns, and the children are
the top-level slices. See `13-single-slice-pie-renders-empty.md`.

## Implementation notes

- Candidate lists: when the dimension's own field is filtered, the top-level candidates are the
  ticked nodes (plus, under "touching", the roots of touched nodes), not `topLevelAccounts`.
  §7.3's sentence "a candidate list … is built from every live member regardless of the Report's
  own filters" is superseded for this case. Rewrite it.
- The personal-debt synthetic node (issues 06/11) must work as a ticked top-level node too.
- **Docs, same change:** `reporting.md` §6.3 (subtree rule + promotion), §7.3 (candidate lists),
  §9.1 or §9.2 (ticked nodes as the top level, full-path labels), and a line in §4 that the
  Account/Category split holds regardless of Scope.
- **Tests.** `sqlLogicTest` for the candidate queries: ticked-only under "booked to", ticked plus
  touched under "touching" (a split transaction touching an unticked account), an empty ticked node.
  Unit: the frontier/label logic (full path only when the parent is absent). Integration: the
  rendered axis for the `Cash-EUR` + `BankAaa` example.

## Comments

Filed 2026-09-23 from owner testing at the close of reporting stage e. Related:
`09-exclude-mode-for-hierarchy-filters.md`.

2026-09-23, owner use case that depends on case 2: a pie of `Cash` split per currency account
(closing balance, Base). Pie slices come from the column axis, and columns can't be expanded in v1
(`reporting.md` §9.1, Q-REP-2), so Account on columns only ever gives top-level slices, here just
`Cash`. If ticking only the children of `Cash` made them the top-level axis nodes, this pie would
work without column expansion, and the table would stop needing save-then-expand. Weigh this when
deciding case 2. See also `13-single-slice-pie-renders-empty.md`.

2026-09-24: triaged in an owner grilling session; decisions recorded above. Scheduled before
reporting slice f (f's "a cell's postings match the list it opens" guarantee needs the right row set
first). The grilling also produced `19-counterpart-report-account-by-category.md` (deferred until
after f) and `20-sign-presentation-of-spending-and-income.md`.

Implemented 2026-09-24 (branch `feat/reporting`), awaiting owner confirmation. How each decided rule
is met:

- **Rules 1 and 3 (grouping).** The account and tag "top ancestor" CTEs take the ticked ids as extra
  roots, and the walk down from a real root stops at a ticked node, so every posting still rolls up
  to exactly one top-level node. The filters' subtree lookup and the child queries stop at ticked
  nodes the same way.
- **Candidates.** "Booked to" lists only the ticked nodes. "Touching" lists them plus the real roots,
  then `PromotedNodes.withoutUntouchedRoots` drops the roots the data shows untouched (on a nested
  axis's inner dimension too).
- **Rule 4.** Ticked nodes get full-path labels from `promotedAccountCandidates` /
  `promotedTagCandidates`.
- **Rule 5.** Category and Account narrow their queries to their own types
  (`ScopeDimensionMismatch.ownAccountTypes`). A scope with none of them leaves an empty axis plus the
  mismatch message.
- **Structure.** The candidate lookups moved out of `ReportDataFetcher` into a new `AxisCandidates`
  (PMD's God Class limit), and `realId` moved to `AxisNode`.
- **Docs.** `reporting.md` §4, §6.3, §7.3 and §9.2 amended.

Known limits:

- Under "touching", expanding a real root still lists its untouched children blank (suppressible),
  as before.
- A ticked personal-debt leaf shows under its cosmetic account name until issues 06/11 land.
