# A both-split mirror residual books its transfer leg twice at commit, but the gate stays unlocked

Status: needs-triage
Category: bug
Severity: medium (latent until f2)
Area: Import review — commit gate (`ImportIssues.locked()`) / mirror matching (`ImportMirrorRepository`, plan e1/e4)

## What happens

A same-currency transfer appears twice — once in each account's export. e1's automatic matching
pairs the two sightings and marks one `mirrored` so f2 books the transfer once. But when **both**
sightings are a Money split (`MATCHED_PAIRS`'s `where` clause excludes `a.non_funding_legs > 1 and
b.non_funding_legs > 1`), neither can be excluded wholesale — dropping either side would also drop
its unrelated category legs. So both stay `ready` and **both book their own transfer leg at f2**,
double-counting the transferred amount across the two accounts.

`ImportUnresolvedMirror` / `ImportIssuesPanel` surface this residual on the review, but
`ImportIssues.locked()` deliberately **excludes** `unresolvedMirrors` from the gate conditions
(`ImportIssues.java` — `locked()` checks only unmapped accounts, expecting-file, unmapped
categories, and parked cross-currency legs). So a campaign with an unresolved both-split residual
and everything else clean reports an **unlocked** e4 gate. With f1 the duplicate-scan condition
adds another lock until that is run and adjudicated, but once it is, `commitReady()` is true and f2
would commit the double-count.

## Where to fix

Decision needed before f2 books anything. Options:

- **Block the gate** on an unresolved both-split residual (add `unresolvedMirrors` to
  `ImportIssues.locked()` and `lockReasons()`), forcing the owner to resolve it by hand (re-split
  in Money and re-export, or accept and fix in the ledger after commit) — safest, but there is no
  in-app fix action, so the gate would be un-openable without leaving the app.
- **Handle it at f2**: book the transfer leg from one sighting only and skip the other's transfer
  leg while still booking its category legs — i.e. a leg-level exclusion rather than a
  transaction-level one. More work in the commit path.
- **Keep informational** (current behaviour) and rely on the duplicate scan / the owner reading the
  warning — the reviewer's concern is precisely that this is too easy to miss.

## Comments

Filed 2026-09-05 from the first `/code-review high` pass during plan f1 (the review targeted the
wrong range — the committed e4 slice — so this is an e4-era finding, not an f1 regression). e4 is
owner-confirmed complete; this was a conscious e4 design choice ("neither can be excluded
automatically, so it never blocks the gate; the owner resolves it by hand") that the review flags
as unsafe once f2 exists.
