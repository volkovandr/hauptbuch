# Counterpart reports: "where did the money from this account go?" (Account × Category, Account × Account)

Status: needs-info
Category: enhancement
Severity: medium
Area: Reporting (`analytics` module: `ReportEngine`, `AxisPlan`, `CellValuation`, `ReportQueryRepository`, `ReportSpec` validation; `docs/reporting.md` §3, §4, §5, §6)

## The question (owner)

"Where did my money from BankAaa go?" Put **Account** on rows, **Category** on columns, filter
Account on `BankAaa`, and read which categories each account paid for. Also **Account × Account**,
to investigate transfers between accounts.

Today this is refused: at most one non-Date dimension family across rows and columns (issue 15,
rule 1). And even with that lifted, a cell keyed by (account, category) doesn't exist at posting
grain: no posting is on both `BankAaa` and `Food`. A cell needs the **counterpart** of a posting,
meaning the leg(s) on the other side of the same transaction.

## The model (settled in the 2026-09-24 grilling)

Each balance-sheet posting gets two labels: **its own account** (the row) and **its counterpart**
(the column), which is the account(s) on the opposite side of its transaction.

- A counterpart that is an income/expense account gives its **category**.
- A counterpart that is another balance-sheet account gives a built-in **Transfers** node.
- **Split transactions are divided in proportion** (owner decision). Food 60 + Household 40, paid
  by Cash 50 + BankAaa 50: BankAaa → Food = 50 × 60/100 = 30, BankAaa → Household = 20. This is the
  only rule under which row and column totals always reconcile. With one account on either side it
  is exact.
- Two postings on the **same** side are not counterparts of each other. That cell is **blank**
  (§7.3), not `0,00`.
- The filter readings keep their `reporting.md` §6.2 meaning:
  - **"amounts booked to BankAaa"**: only BankAaa's postings are counted; the Category columns say
    where that money went.
  - **"transactions touching BankAaa"**: every balance-sheet posting of those transactions is
    counted.

### Worked examples (owner)

**Example 1.** Paid 100 by BankAaa card in a supermarket: Food 50, cash back 50.
Postings: `BankAaa −100`, `Food +50`, `Cash +50`. Account filter: touching `BankAaa`.

|         | Food | Transfers | Total |
|---------|-----:|----------:|------:|
| BankAaa |  −50 |       −50 |  −100 |
| Cash    |      |       +50 |   +50 |
| Total   |  −50 |         0 |   −50 |

Row totals equal each account's turnover. The Food column total is what was spent on food. The grand
total (−50) is the net change in balance-sheet money. (Signs as shown are the balance-sheet side's
sign, so spending reads negative. See issue 20 for the sign question in general.)

**Example 2.** Food 100, paid Cash 50 + BankAaa 50. Postings: `Food +100`, `Cash −50`,
`BankAaa −50`. Account filter ticks only `BankAaa`.

- "touching": rows `BankAaa −50` and `Cash −50`, both against Food.
- "booked to": row `BankAaa −50` against Food only. No Cash row: nothing went from BankAaa to Cash.

## Decided

- **Transfers is a built-in node of the Category dimension and the Category filter**, the same
  pattern as the "Personal debts" node in the Account filter (issues 06/11). With an empty Category
  filter it shows whenever a transfer happened in range. Ticking only `Food` excludes it ("how much
  food did I buy with BankAaa's card?" has no room for transfers). Ticking `Food` + Transfers keeps
  both. No separate toggle. Excluding just Transfers is issue 09's exclude mode.
- Account × Account is allowed. Its cells are the transfer flows between two balance-sheet accounts.

## Open questions

1. **What Scope means in a counterpart report.** In Example 1 the summed side is the balance sheet
   (Scope `asset`, plus `liability` for credit cards); the categories come from the counterpart, not
   from Scope. The same information could be read from the expense side (Scope `expense`, rows =
   paying account as "who paid"), but that side can't show Transfers, because a transfer has no
   expense leg. Does Scope stay a free choice here, or follow from the dimensions?
2. **"Income/Expenses" on the Account axis.** The owner wants categories *never* to appear as
   accounts by default (issue 08 enforces the §4 split), but an option to show an "Income/Expenses"
   super-parent holding the whole category tree on the Account axis. Probably a Scope-level toggle,
   **off** by default. Unlike Transfers it can't be a plain filter node, because an empty filter
   would then bring categories back.
3. **Measures.** How the debit/credit/net legs (§5.3) and closing balance apply to a counterpart
   cell. Closing balance probably has no counterpart meaning.
4. **Engine shape.** A counterpart cell needs a proportional-allocation query over the transaction's
   opposite legs, not a per-cell filter. It needs `sqlLogicTest` coverage for splits,
   cross-currency legs (allocate on `base_amount`?) and the conditional sum-to-zero case.

## Scheduling

After reporting slice f, folded into issue 15 (the dimension-combination legality rethink), since
lifting rule 1 is the prerequisite. Needs its own design pass and a `reporting.md` section before
implementation.

## Comments

Filed 2026-09-24 from the owner grilling session on issue 08. Related:
`08-unticked-hierarchy-nodes-still-render-as-rows.md`,
`15-rethink-which-dimension-combinations-are-allowed.md`,
`09-exclude-mode-for-hierarchy-filters.md`, `20-sign-presentation-of-spending-and-income.md`.
