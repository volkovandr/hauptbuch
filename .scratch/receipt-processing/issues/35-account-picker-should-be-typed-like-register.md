# The receipt screen's Account picker should be a typed picker, like the register's

Status: resolved
Category: enhancement
Severity: minor
Area: Receipts — processing screen, paying-account field (`receipt-process.html` `#receipt-account`)

## Symptom
On the receipt processing screen the paying account is a plain `<select>`. The operator can only
scan the list by eye and click. In the register the account is a text input with a datalist
(`#entry-account`, `#split-account`): typing two or three letters and pressing Enter picks it,
which is faster. The two screens pick the same thing in different ways.

## Root cause
The receipt header was built as a `<select>` of `register.accounts()` (leaves only, full-path
labels since issue 32), not with the register's typed account input and its
`/register/account/resolve` round-trip.

## Accepted solution (owner's decision)
The receipt screen's Account field works like the register's: a text input with a datalist of
the account options; type a few letters, Enter picks the match.

Keep what the current `<select>` guarantees:
- **Own accounts only.** The receipt's funding leg is never a person; the `for Max` / `by Max`
  person sigils the register accepts are not offered or accepted here.
- **Undetected stays empty.** When detection found nothing the field is empty and Confirm is
  blocked (issue 01). An unknown or ambiguous typed value is treated the same: no account, Confirm
  blocked, not silently mapped to some account.
- **Leaves only**, labelled with the full path (issue 32).
- **Changing the account still refreshes the editor** (the `hx-post` to
  `/receipts/{id}/lines/currency` on `change`), since the account drives the currency mode.

Once this is a typed picker it also gets the select-all-on-first-focus behaviour from
`general-ux/issues/07-*`.

## Notes
- Reuse the register's account input pattern and the existing account-text resolution; do not
  build a second resolver. The form currently posts `accountId`; the change from id to text (or a
  resolved hidden id) touches the receipt editor's form binding and its MockMvc tests.

## Comments

Triaged 2026-10-18: enhancement, ready-for-agent. Do it together with or after `general-ux/issues/07-*`, so the new field gets select-all-on-first-focus right away.

Resolved 2026-10-20: the field is an `accountText` input over a datalist of the post-to leaves
(`data-select-on-focus`), resolved by `POST /receipts/account/resolve` over the same
`PostToAccountService.resolve` the register uses. The hidden `accountId` the editor binds is
unchanged, so the form binding and the Confirm gate needed no change. A resolved or cleared value
fires `receipt-account-resolved`, which refreshes the editor (currency mode); an unresolvable value
(unknown, ambiguous, group, `for`/`by` sigil) clears the id, shows the reason, and does not refresh,
so the typed text survives for correction.
