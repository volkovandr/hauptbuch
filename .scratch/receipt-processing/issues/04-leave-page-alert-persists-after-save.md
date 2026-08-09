# Browser "leave page?" warning fires after Save, even though the edit was already saved

Status: needs-info
Category: bug
Severity: medium
Area: Receipts — post-process editor dirty guard (`receipt-process.html`, plan §9f)

Found by the owner testing the post-process (line) editor: pressed **Save** (not Confirm/commit to
register), then pressed **Back** to return to the receipts list, and got the browser's native
"Are you sure you want to leave this page? Changes you made may not be saved." confirmation —
even though Save had already persisted the edit. The owner plans to retest and confirm exactly
when/where it reproduces before this goes further; left at `needs-info` rather than
`ready-for-agent`.

## The guard as it exists today

`receipt-process.html:206-235` — an inline, page-scoped dirty-guard script (comment at
`:201-205` explains the intent): sets `dirty = true` on any `input` event inside `#receipt-editor`
(`:211-213`), and on `htmx:afterSwap` re-derives `dirty` by checking whether the swapped content
carries a `data-receipt-editor-saved` marker (`:214-218`):

```js
document.body.addEventListener("htmx:afterSwap", function (e) {
  if (e.target && e.target.id === "receipt-editor") {
    dirty = !e.target.querySelector("[data-receipt-editor-saved]");
  }
});
```

`window.addEventListener("beforeunload", ...)` (`:228-233`) blocks unload while `dirty` is true.

Save posts via htmx to `/receipts/{id}/lines/save` with `hx-target="#receipt-editor"
hx-swap="outerHTML"` on the form itself (`:482-496`). `ReceiptProcessingController.save()` sets
`editorSaved = true` in the model on a successful save (`ReceiptProcessingController.java:344`),
and the `editor` fragment renders the marker when that flag is set:
`th:if="${editorSaved}" data-receipt-editor-saved` (`:697`).

## Leading suspicion — needs a browser check, not just code reading

The form being swapped (`#receipt-editor`) is swapped with `hx-swap="outerHTML"` — the *whole*
element, including its `id`, is replaced by a new DOM node, not patched in place. With an
`outerHTML` swap it's a known htmx sharp edge that `htmx:afterSwap`'s `event.target` does not
reliably reference the *new* element with the marker already in its subtree — it can still point
at (or otherwise fail to see into) the outgoing node. If that's what's happening here, `dirty`
never gets cleared to `false` on a successful Save, and any subsequent Back/unload trips the guard
regardless of Save having worked. If confirmed, the fix is likely small — re-querying via
`document.getElementById("receipt-editor")` after the swap instead of trusting `e.target`, or
moving the check to `htmx:afterSettle` — but this needs verifying live in the browser first
(CLAUDE.md's existing lesson: htmx swap-timing bugs have burned this codebase before, see the
`htmx-oob-only-error-wipes-target` case; verify in the browser, not by reasoning about the code
alone).

## Open questions for the owner's retest

- Does it reproduce on *every* Save, or only sometimes (e.g. only after also using add/remove/
  redistribute-line actions, which the code comment says deliberately leave `dirty` set)?
- The owner's note says "I think it appears in several places" — a repo-wide search found only one
  `beforeunload` listener (this one, in `receipt-process.html`) and no duplicated dirty-guard
  pattern elsewhere. Does "several places" mean several *code* locations, or several *situations*
  within this one editor (e.g. after Save, after a line add/remove, after Confirm)? Worth pinning
  down during the retest.

## Comments

Filed 2026-08-07 from an owner note in `docs/potential-feature-ideas.md`. The owner is going to
retest before this is handed to an agent, so it's deliberately left at `needs-info`.
