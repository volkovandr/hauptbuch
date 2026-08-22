# A category can't be deleted when every other category of its type has currency leaves — and an empty category shouldn't need a target at all

Status: needs-triage
Category: bug
Severity: high
Area: Category management — `CategoryService.deleteTargetOptions`, `operations.DeletionService`,
`category-edit.html` delete panel

## Request (owner)

Created a new income category, then tried to delete it right away to check the new logging. The
system refuses: *"No other leaf category of this type to move the postings to — create one first."*
That doesn't make sense:

1. There are no postings — the category was just created and no transactions have been entered
   since.
2. There are several other income categories available. Why can't the postings move to one of
   those?

## What actually happens (confirmed against the dev database)

Two separate defects stack up here. Both are reproducible; the second is the one that makes the
whole income taxonomy undeletable.

### 1. Deleting an empty category still demands a reassignment target

`DeletionService.deleteCategory(subtreeRootId, targetLeafId)` takes a target unconditionally, and
the delete panel in `category-edit.html` hides the entire form when `deleteTargets` is empty:

```html
<p class="muted" th:if="${#lists.isEmpty(deleteTargets)}">
  No other leaf category of this type to move the postings to — create one first.
</p>
```

Nothing anywhere asks whether the subtree carries any postings. A category with zero postings has
nothing to reassign, so it should delete outright with no target picker at all — which is exactly
the case the owner hit (the new category, `account_id = 95`, has 0 postings).

### 2. Any category that has spawned a currency leaf can never be a delete target

`CategoryService.deleteTargetOptions` filters candidates through `isLeafAfterDeletion`, which counts
**every** live child — including `CurrencyLeafService`'s hidden auto-managed per-currency leaves:

```java
private boolean isLeafAfterDeletion(long accountId, Set<Long> deletedSubtree) {
  return accountService.findChildrenOf(accountId).stream()
      .allMatch(child -> deletedSubtree.contains(child.accountId()));
}
```

This is deliberate and unit-tested (`CategoryServiceTest
.deleteTargetOptionsExcludesCategoryThatStillHasCurrencyLeafChildren`) — reassigning postings
straight onto a parent would violate leaves-only (data-model §5). But the consequence in a real
multi-currency book is that the target list empties out completely. The dev database's income side:

| id | name | live children | postings |
|----|------|---------------|----------|
| 10 | Salary | 4 (all currency leaves: CHF/CZK/EUR/GBP) | 0 |
| 13 | Bottle deposit returns | 5 (all currency leaves) | 0 |
| 41 | Interest | 2 (all currency leaves) | 0 |
| 95 | Found on the street | 0 | 0 |

Every real income category has been posted to in at least one currency, so every one of them has
currency-leaf children and is excluded. The currency leaves themselves are excluded too (they never
appear in `manageableCategories()`). Category 95 is the subtree root being deleted, so it's
excluded as well — the list is empty and the form never renders.

The general shape: **once a category has been posted to in any currency, it can never again serve
as a delete target.** In a book that has been in use for a while that's every category, so
categories of that type become permanently undeletable. Same mechanism applies to `expense`.

## Why the "not a safe target" reasoning is too strict

The exclusion protects leaves-only, but the project already has the mechanism that makes a parent a
perfectly good semantic target: `CurrencyLeafService.resolveCurrencyLeaf(categoryId, currencyCode)`
routes a posting to the right per-currency leaf, creating it on first use (data-model §6.5). It is
what the entry dock uses on every commit — the user picks `Food`, the engine picks `Food/EUR`.

Reassignment should do the same thing: for each posting being moved, resolve the target category
against **that posting's currency** and file it on the resolved leaf, rather than refusing to offer
the parent at all. That also matches the owner's intuition in the report — "move it to another
income category" is a semantic choice, and which currency leaf it lands on is not the user's
problem.

## Suggested direction (not yet decided — needs triage)

- **Empty subtree ⇒ no target.** If the subtree carries no postings (live or voided), delete it
  without asking. This alone fixes the reported case and is independent of the rest.
- **Offer semantic parents as targets**, and reassign per-posting through
  `CurrencyLeafService.resolveCurrencyLeaf` so each posting lands on the target's leaf for its own
  currency. `isLeafAfterDeletion` then only needs to exclude categories with *real* children, not
  ones whose only children are auto-managed currency leaves.
- Note that `PostingReassignmentRepository.reassignPostings(subtree, targetLeafId)` is currently a
  single bulk `update` to one target id — per-currency routing makes it a per-currency (or
  per-posting) operation, so that repository method changes shape too.

## Comments

Filed 2026-08-22 by the owner while exercising the new create/delete logging (`observability/02`).
Marked high severity: defect 2 makes an entire account type permanently undeletable in any book
that uses more than one currency, and there is no workaround from the UI.

Verified against the running dev database rather than inferred — the income-category table above is
the live data at the time of filing.
