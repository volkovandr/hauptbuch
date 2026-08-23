# Editing a simple transaction can't turn it into a split

Status: needs-triage
Severity: medium
Area: Transaction register — edit mode

A simple (single-category) transaction can't be converted into a split while editing — the two
entry shapes behave as if they were unrelated forms. Logically this should be possible: it's still
the same transaction, just needing more than one category line now.

Request: allow switching a simple transaction into split mode from its edit view, the same
"Split" affordance already available when creating a new transaction.

## Comments

Filed 2026-08-21 from the `potential-feature-ideas.md` idea list.

See also `transaction-register-ui/21` (unify the simple and split entry models): if a simple
transaction becomes a one-line split, this stops being a feature to build — there is no conversion
to perform, only a line to add.
