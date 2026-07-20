# Register direction arrows sit on inconsistent sides between transfers and debt legs

Status: needs-triage
Severity: low
Area: Transaction register — row display (§2.6), account & category columns

Transfers and person-debt legs both carry a direction arrow, but they encode the direction
**differently**: transfers keep the arrow on a fixed side and vary the glyph (`←`/`→`); debt legs
keep the glyph fixed (`→`) and vary the *side*. Side-by-side in the register the two read
inconsistently.

> Always show the arrow on the **right** of the account/person name in the **Account** column, and on
> the **left** of the name in the **Category** column. Let the glyph (`←`/`→`) carry the direction.

## Current behaviour (`register.html`)

**Account column** — person debt leg only (a real account is a bare name, no arrow):

| Direction | Renders | Arrow side |
|---|---|---|
| income / debit (they owe you more) | `→ Max` | left |
| credit (you owe them more) | `Max →` | right |

Side toggles with direction; glyph is always `→`.

**Category column** chips:

| Chip kind | Renders | Arrow side |
|---|---|---|
| transfer inbound (`From ←`) | `← Cash` | left |
| transfer outbound (`To →`) | `→ Cash` | left |
| person debt, outbound | `→ Max` | left |
| person debt, inbound | `Max →` | right |

Transfers are consistent (arrow left, glyph varies). Person legs toggle the side and pin the glyph —
that is the mismatch.

## Desired behaviour

Fix the **side per column**, and let the **glyph** carry direction (as transfers already do):

- **Account column:** arrow always to the **right** of the name — `Max →` (credit/outbound) and
  `Max ←` (debit/inbound).
- **Category column:** arrow always to the **left** of the label — `→ Max` / `← Max`, matching how
  transfer chips already sit.

Result: within a column the arrow is always in the same place, and a transfer chip and a person chip
point the same way for the same real flow. The direction each glyph should point still follows the
existing income/inbound flags — this is a rendering-position change, so confirm the glyph mapping
against register §2.6 rather than only mirroring today's `→`.

## Scope

- Template-only change in `register.html` (the two `th:if` arrow spans in the Account cell, and the
  person-chip arrow spans in the Category cell). No view-model change expected — `r.income()`,
  `chip.inbound()` etc. already carry the direction.
- Update the `register.html` comments (they document the current "→ Name on a debit, Name → on a
  credit" convention) and register §2.6 to the new fixed-side rule.
- Purely visual — add/adjust a Playwright assertion only if one already covers arrow rendering;
  otherwise no test tier applies.

## Comments
