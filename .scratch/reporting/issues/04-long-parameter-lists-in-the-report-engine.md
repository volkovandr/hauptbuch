# Long parameter lists in the report engine (`addFrontierNode`, `fetchGridData`)

Status: ready-for-agent
Category: enhancement
Severity: low
Area: Reporting (`analytics` module: `ReportGridBuilder`, `ReportDataFetcher`, `ReportEngine`)

A readability refactor with no behaviour change. Stage e's tree engine grew two methods whose
signatures are now hard to read and easy to call wrong: several arguments share a type and sit next
to each other, and passing them in the wrong order compiles fine.

1. **`ReportGridBuilder.addFrontierNode`** takes 10 parameters and recurses. Five of them never
   change during the walk: `outerDim`, `innerDim`, `innerCandidatesByKey`,
   `sameDimensionChildrenByParentKey` and `expandedKeys`. Only `frontier`, `node`, `key`, `depth`
   and `parentKey` change per level. It carries a `@SuppressWarnings("PMD.ExcessiveParameterList")`
   whose comment argues that a context object "would just wrap this same parameter list". It
   wouldn't: moving the five fixed ones into a small record (or a private walker class holding them
   as fields) halves the recursive signature, and the suppression can then be **removed**, which is
   the reason to do this.
2. **`ReportDataFetcher.fetchGridData`** takes 8 parameters (spec, axes, types, resolved range,
   buckets, today, base currency, expanded keys), and a 9-parameter overload adds `granularity`.
   `ReportEngine` calls it twice with nearly the same arguments: once for the whole range and once
   per expanded Date bucket, where only range, buckets and granularity differ. Everything else is a
   per-render constant that belongs together, for example a `FetchContext(spec, axes, types, today,
   baseCurrency, expandedKeys)` record, leaving the call as `fetch(context, range, buckets,
   granularity)`. The 8-parameter convenience overload can then go.

Scan the helpers these two call (`mergeChildTurnover`, `childTurnover`, the balance counterparts)
while you're there: they pass the same fixed values along, and should take the same record rather
than re-spreading it.

## Constraints

- Pure refactor: no SQL, output or test-assertion changes. The existing `ReportGridBuilderTest`,
  `ReportEngineTest` and `ReportQuerySqlLogicTest` must pass unchanged, apart from call sites in
  tests that build these arguments directly.
- Keep the new records package-private in `analytics` (module-internal), next to `AxisPlan`/`GridData`.
- Don't add a second way to do it: pick one context shape per class and use it everywhere in that class.

## Done when

`addFrontierNode` has no PMD suppression, `fetchGridData` has one signature with at most 4–5
parameters, and `./gradlew check` is green.

## Comments

Filed 2026-09-23 from the stage e3/e4b `/code-review` leftovers (session-local
`.scratch/.workpackages-e`). Deliberately kept out of e5 so that e5's diff stays about features.
Good to pick up the next time either class is touched.
