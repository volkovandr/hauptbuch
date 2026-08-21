# Inline category creation from the dock has no confirmation step

Status: needs-triage
Severity: medium
Area: Transaction register — entry (category picker, create-new)

Typing a category name that doesn't exist yet creates it inline on save, with no confirmation.
This risks creating an unintended category from a typo, and it's ambiguous in a specific way:
typing `Food - Milk` creates `Milk` as a subcategory of `Food` — probably what's wanted — but
there's no way to instead create a genuinely new top-level category literally named
`Food - Milk`. There's a workaround (create it explicitly via category management first), but the
inline flow doesn't make clear which outcome it's about to produce.

Request: a lightweight confirmation before an inline create commits, stating plainly what will be
created (name + parent, if any) so a typo or an ambiguous dash-separated name doesn't silently
create the wrong thing.

## Comments

Filed 2026-08-21 from the `potential-feature-ideas.md` idea list.
