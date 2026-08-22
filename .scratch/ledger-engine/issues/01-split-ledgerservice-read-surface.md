# Split `LedgerService`'s batched read accessors off the engine, and delete the PMD coupling suppression

Status: needs-triage
Category: enhancement
Severity: low
Area: `ledger` — `LedgerService`

## What prompted this

`observability/03` added a `Logger` to `LedgerService`. That alone pushed PMD's
`CouplingBetweenObjects` from 19 to 21 against a threshold of 20, so the class now carries a
suppression:

```java
@SuppressWarnings("PMD.CouplingBetweenObjects")
```

The rule reports at the type node, so there is no narrower scope available — the annotation
silences the rule for the whole class from now on, not just for the two SLF4J types that tripped
it. `LedgerService` is the double-entry engine, the class where unnoticed coupling growth matters
most, so leaving that blind spot untracked is not acceptable even though the suppression itself was
the right call at the time (CLAUDE.md §1.9's narrowed-suppression path — it was that or raise the
threshold for every class in the project).

## The underlying shape

`LedgerService` is documented as "three operations" — `recordTransaction`, `editTransaction`,
`voidTransaction` — but has since accumulated six batched read accessors that exist only because
other modules cannot reach `ledger`'s repositories directly:

- `findPostings`
- `tagsForTransaction`
- `tagIdsForPosting`
- `labelsForTagIds`
- `voidedTransactionIds`
- `datesForTransactions`

(`findTransaction` is arguably in the same group.) These are why the type count is high: they drag
in `Collection`, `Map`, `Set`, `LocalDate`, `TransactionTag`, `Posting` and the two repositories,
none of which the three write operations need. It is a textbook Divergent Change — the class is
edited both when the engine's invariants change and whenever another module needs a new read.

## Suggested direction (not decided — needs triage)

Move the read accessors to their own public type in `ledger` (e.g. `LedgerReadService`, or split by
subject if that reads better), leaving `LedgerService` as the write engine its Javadoc already
describes. Callers in `operations` and `receipts` switch to the new type; the module boundary is
unchanged, so `ApplicationModules.verify()` should stay green.

**Done when:** the `@SuppressWarnings("PMD.CouplingBetweenObjects")` on `LedgerService` is deleted
and `./gradlew check` is green without it.

## Comments

Filed 2026-08-22 alongside `observability/03`. Not urgent — nothing is broken, and the suppression
is correctly justified in place — but it should not be forgotten, because the cost is silent.
Both reviewers of `observability/03` independently raised the class-level scope as the main
objection to that change.
