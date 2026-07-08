/*
 * Keyboard layer — the one bespoke-JS leaf for keyboard-first navigation (tech-stack §4.3,
 * FR-UX-05, NFR-01).
 *
 * DESIGN CONTRACT (read before touching): this is an ISOLATED LEAF, not a framework. It is a few
 * hundred lines of dependency-free vanilla JS that the rest of the htmx UI never imports or threads
 * through. It attaches to plain DOM via data-attributes; remove the script and the app still
 * renders and submits — keyboard nav is the only thing lost. Keep it that way.
 *
 * It does these things, all driven by markup:
 *   1. List navigation: within [data-kbd-list], ArrowUp/ArrowDown move a "selected" [data-kbd-row];
 *      Enter activates the selected row (clicks it / its first link or button).
 *   2. Command palette: Ctrl/Cmd-K toggles [data-kbd-palette] (a stub here; real commands land with
 *      the screens that need them).
 *   3. Scroll-to-bottom: any [data-scroll-bottom] container is scrolled to its bottom on load and
 *      after an htmx swap — the register is newest-at-bottom (register §2.1), so "now" and the
 *      entry point sit where the eye lands.
 *   4. Focus the entry point: pressing "n" (when not already typing) focuses [data-kbd-new] — the
 *      register entry dock's first field — so entry is reachable without the mouse (register §3.2,
 *      Q-UI-2 keyboard-first). Within the dock, Tab walks the fields and Enter on a field submits
 *      (native form behaviour, which htmx drives); the "n" jump is the one thing the browser lacks.
 *   5. Nothing else. New shortcuts are added here, by markup convention — never scattered into
 *      page scripts.
 *
 * It re-scans after htmx swaps so freshly-inserted rows are navigable.
 */
(function () {
  "use strict";

  const ROW = "[data-kbd-row]";
  const SELECTED = "row--selected";

  /** All navigable rows across all lists, in document order. */
  function rows() {
    return Array.from(document.querySelectorAll(ROW));
  }

  /** Index of the currently-selected row, or -1 if none. */
  function selectedIndex(all) {
    return all.findIndex((r) => r.classList.contains(SELECTED));
  }

  /** Select the row at `index`, clamped to the list, and move focus to it. */
  function select(all, index) {
    if (all.length === 0) return;
    const clamped = Math.max(0, Math.min(index, all.length - 1));
    all.forEach((r) => r.classList.remove(SELECTED));
    const row = all[clamped];
    row.classList.add(SELECTED);
    row.focus();
  }

  /** Activate a row: prefer an inner link/button, else click the row itself. */
  function activate(row) {
    if (!row) return;
    const target = row.querySelector("a, button") || row;
    target.click();
  }

  /** Toggle the command-palette stub, if one is present on the page. */
  function togglePalette() {
    const palette = document.querySelector("[data-kbd-palette]");
    if (!palette) return;
    const open = palette.getAttribute("data-open") === "true";
    palette.setAttribute("data-open", open ? "false" : "true");
    if (!open) {
      const input = palette.querySelector("input");
      if (input) input.focus();
    }
  }

  function onKeydown(event) {
    // Ctrl/Cmd-K: command palette, regardless of focus.
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
      event.preventDefault();
      togglePalette();
      return;
    }

    // Escape closes an open palette.
    if (event.key === "Escape") {
      const palette = document.querySelector('[data-kbd-palette][data-open="true"]');
      if (palette) {
        event.preventDefault();
        palette.setAttribute("data-open", "false");
      }
      return;
    }

    // List navigation and single-key shortcuts only apply when not typing into a field.
    const tag = (event.target.tagName || "").toLowerCase();
    if (tag === "input" || tag === "textarea" || tag === "select") return;

    // "n": jump to the entry dock's first field (register §3.2), if the page has one.
    if (event.key === "n") {
      const entry = document.querySelector("[data-kbd-new]");
      if (entry) {
        event.preventDefault();
        entry.focus();
        return;
      }
    }

    const all = rows();
    if (all.length === 0) return;
    const current = selectedIndex(all);

    if (event.key === "ArrowDown") {
      event.preventDefault();
      select(all, current < 0 ? 0 : current + 1);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      select(all, current < 0 ? all.length - 1 : current - 1);
    } else if (event.key === "Enter" && current >= 0) {
      event.preventDefault();
      activate(all[current]);
    }
  }

  /** Scroll every [data-scroll-bottom] container to its bottom (register newest-at-bottom, §2.1). */
  function scrollToBottom() {
    document
      .querySelectorAll("[data-scroll-bottom]")
      .forEach((el) => (el.scrollTop = el.scrollHeight));
  }

  document.addEventListener("keydown", onKeydown);
  document.addEventListener("DOMContentLoaded", scrollToBottom);

  // After an htmx swap, a selected row may have been replaced; drop a stale selection so the next
  // arrow keypress re-selects from a clean state, and re-anchor the newest-at-bottom scroll (both
  // no-ops when htmx is absent).
  document.body &&
    document.body.addEventListener("htmx:afterSwap", function () {
      rows().forEach((r) => r.classList.remove(SELECTED));
      scrollToBottom();
    });
})();
