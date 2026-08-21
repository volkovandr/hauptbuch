# Creating a non-top-level category doesn't default its type to the parent's

Status: needs-triage
Severity: minor
Area: Category management (category create/edit form)

When creating a category that isn't top-level, its type (income/expense) should be set
automatically from the parent category's type rather than left for the user to pick — a
subcategory's type is never actually independent of its parent's.

Related to category-management issue 01 (inline child-creation button), but applies to the
existing create flow too, regardless of entry point.

## Comments

Filed 2026-08-21 from the `potential-feature-ideas.md` idea list.
