# Category selector allows non-leaf categories, then Save silently fails

Status: needs-triage
Severity: medium
Area: Transaction register

The Category selector allows selecting a non-leaf category without any warning. When you press
Save, nothing happens — as if the button is broken — but the logs show an error about a
non-leaf account.

The Category selector should not allow selecting non-leaf categories, or at least make the user
aware of the problem instead of failing silently.

## Comments
