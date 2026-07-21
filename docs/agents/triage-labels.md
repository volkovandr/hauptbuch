# Triage labels: Local Markdown

The triage skill works in canonical role names; this file maps them to how this repo records
them. There is **no** GitHub label surface here — roles are plain text lines in the issue file's
header block (see `issue-tracker.md`), one per axis.

Every triaged issue carries **exactly one category** and **exactly one state**.

## State — the `Status:` line

Recorded verbatim in the `Status:` line near the top of the file. The strings are the canonical
role names, so the mapping is 1:1:

| Canonical role | `Status:` value |
|----------------|-----------------|
| `needs-triage` | `needs-triage` |
| `needs-info` | `needs-info` |
| `ready-for-agent` | `ready-for-agent` |
| `ready-for-human` | `ready-for-human` |
| `wontfix` | `wontfix` |

## Category — the `Category:` line

Recorded in a `Category:` line directly under `Status:`. Add it when triaging an issue that lacks
one:

| Canonical role | `Category:` value |
|----------------|-------------------|
| `bug` | `bug` |
| `enhancement` | `enhancement` |

## Example header block

```markdown
# Category selector allows non-leaf categories, then Save silently fails

Status: ready-for-agent
Category: bug
Severity: medium
Area: Transaction register
```

`Severity:` and `Area:` are pre-existing repo conventions, not triage roles — leave them as they
are.

## wontfix

There is no separate "closed" axis: `Status: wontfix` **is** the closed state. A rejected
enhancement also gets a `.out-of-scope/` entry linked from the issue's `## Comments`; an
already-implemented request does not.
