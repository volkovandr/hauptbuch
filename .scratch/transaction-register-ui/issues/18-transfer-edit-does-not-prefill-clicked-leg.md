# Editing a transfer always pre-selects the source account, not the leg that was clicked

Status: needs-triage
Severity: low
Area: Transaction register — edit mode

When editing a transfer transaction, the Account field is always pre-filled with the transfer's
source account, even when the edit was triggered from the destination account's row. It still
works, but it's more consistent for the pre-filled account to match whichever leg's row was
clicked.

## Comments

Filed 2026-08-21 from the `potential-feature-ideas.md` idea list.
