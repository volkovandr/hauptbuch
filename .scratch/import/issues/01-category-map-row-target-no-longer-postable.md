# A category-map row whose target stopped being a postable leaf renders "→ null"

Status: needs-triage
Severity: minor
Area: Import review — category map panel (`ImportCategoryMapPanel`)

## What happens

`import_category.account_id` is set once, when the owner maps a Money path. Nothing
re-validates it afterwards. If that category later stops being a postable leaf — the owner
subdivides it (typing a deeper `Parent - Child` for another row turns the former leaf into a
group), or it is removed — then:

- `ImportCategoryMapPanel` looks the id up in `postableCategoryPaths()`, misses, and the row
  renders `Audi:Fuel → null`;
- the row is still `mapped()`, so its `<details>` renders **collapsed** — easy to miss on a
  long page;
- `import_category.account_id` now points at a **group** account — a leaves-only violation that
  surfaces only at commit (f2).

## Where to fix

Not a panel bug to patch in isolation — it belongs in the **commit gate**. Add a gate
condition (plan **e4** issues list, or **f1**) that every mapped `import_category.account_id`
still resolves to a postable category leaf, listed as an issue linking back to the row when it
does not. The panel should also show the stale row **open** with a warning rather than
collapsed-with-"→ null".

## Comments

Filed 2026-09-03 from a `/code-review` finding on the d1/d2 slices. The sibling findings from
that review: the taxonomy-mutation-before-validation bug (fixed in the d2 commit) and the
per-row INFO logging in the bulk loop (lowered to DEBUG in the d2 commit).
