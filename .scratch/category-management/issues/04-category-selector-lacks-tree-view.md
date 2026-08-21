# The category picker doesn't show the category tree — no indentation or cue for non-leaf items

Status: needs-triage
Severity: medium
Area: Category picker (transaction entry dock / split panel)

The category selector used when entering a transaction (and in the split panel) lists categories
without indicating the hierarchy — it isn't clear which subcategory belongs to which parent.
Non-leaf (parent) categories can't actually be posted to (see the already-fixed
`transaction-register-ui` issue 03 for the crash that used to cause), but the picker still lists
them indistinguishably from leaves.

Request: render the picker as a tree (indented by depth), and visually de-emphasize (grey out, or
omit entirely) non-leaf categories so it's obvious they aren't valid picks before the user tries
one.

## Comments

Filed 2026-08-21 from the `potential-feature-ideas.md` idea list. The original note bundled this
with a backend crash on picking a non-leaf category — that half was already fixed as
`transaction-register-ui` issue 03; only the display/UX half remains here.
