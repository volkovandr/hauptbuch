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
 *   5. Highlight the row under edit: on load and after each htmx swap, mirror the dock's edit state
 *      onto the register — the row matching the dock's editing transactionId gets a marker class
 *      (register §3.1). Pure reflection of server markup; nothing to clear by hand.
 *   6. Split panel live readout: while the split panel is open, sum the lines' signed contributions
 *      as the user types (income +, expense −, by each line's resolved type) and write the
 *      `remaining` readout, the pay/receive direction cue, and the Save-button label (register
 *      §3.10). Display convenience only — the server re-derives all three authoritatively at Save.
 *      Pressing "s" (when not typing) opens the split panel via [data-kbd-split].
 *   7. Split focus: after the panel opens, focus the first line's amount (its category is already
 *      chosen); after a line is added, focus the new line's category (its amount is pre-filled). So
 *      the cursor lands where the user types next instead of nowhere (register §3.10).
 *   8. Select-all on first focus of a [.num] field with a value, so typing replaces it; a later
 *      click in the same field positions the caret normally (register entry UX).
 *   9. Nothing else. New shortcuts are added here, by markup convention — never scattered into
 *      page scripts.
 *
 * It re-scans after htmx swaps so freshly-inserted rows are navigable.
 */
(function () {
  "use strict";

  const ROW = "[data-kbd-row]";
  const SELECTED = "row--selected";
  const EDITING = "register__row--editing";

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

    // "s": open the split panel (register §3.9), if the dock offers one.
    if (event.key === "s" || event.key === "S") {
      const split = document.querySelector("[data-kbd-split]");
      if (split) {
        event.preventDefault();
        split.click();
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

  // Mirror the entry dock's edit state onto the register (register §3.1): mark the row whose
  // transaction the dock is editing, clear it everywhere else. The dock only renders a
  // transactionId field in edit mode, so this is driven entirely by server markup — save and cancel
  // reset the dock, leaving no editing id, which clears the highlight for free. No-op off the
  // register (no such rows / dock).
  function syncEditingRow() {
    const dockId = document.querySelector('#entry-dock input[name="transactionId"]');
    const editing = dockId ? dockId.value : null;
    document.querySelectorAll("[data-transaction-id]").forEach((row) => {
      row.classList.toggle(EDITING, editing !== null && row.dataset.transactionId === editing);
    });
  }

  // ── Split panel live readout (register §3.10) ────────────────────────────────
  // Parse a user-entered amount ("1.234,56", optional leading +/− or Unicode minus) to a float,
  // leniently: the LAST separator (either "." or ",") is the decimal point and any earlier ones are
  // grouping and dropped. This mirrors the server's MoneyFormat.parse (owner decision, 2026-07-09),
  // so the live readout reads "15.50" as 15,5 like the commit does — not 1550.
  function parseGerman(text) {
    if (!text) return 0;
    let t = text.trim().replace(/−/g, "-");
    const negative = t.charAt(0) === "-";
    if (negative || t.charAt(0) === "+") t = t.slice(1).trim();
    const decimalPos = Math.max(t.lastIndexOf("."), t.lastIndexOf(","));
    if (decimalPos < 0) {
      t = t.replace(/[.,]/g, "");
    } else {
      const intPart = t.slice(0, decimalPos).replace(/[.,]/g, "");
      const fracPart = t.slice(decimalPos + 1);
      t = fracPart === "" ? intPart : intPart + "." + fracPart;
    }
    const n = parseFloat(t);
    if (isNaN(n)) return 0;
    return negative ? -n : n;
  }

  function formatGerman(n) {
    return n.toLocaleString("de-DE", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
  }

  // Sum the split lines' signed contributions (income +, expense − by each line's resolved type)
  // and write the remaining readout(s), the pay/receive direction cue, and the Save-button label.
  // For a cross-currency split (register §3.8a) the lines are in the spending currency; the funding
  // and base equivalents (per line and remaining) are derived live from the header's shared rate,
  // exposed as data attributes. Display-only; the server re-derives everything at Save. No-op when
  // no panel is open.
  function updateSplitReadout() {
    const panel = document.querySelector("[data-split-panel]");
    if (!panel) return;
    const totalEl = panel.querySelector("[data-split-total-input]");
    const total = totalEl ? parseGerman(totalEl.value) : 0;
    const cross = panel.dataset.splitCross === "true";
    const rateFunding = parseFloat(panel.dataset.splitRateFunding || "0") || 0;
    const rateBase = parseFloat(panel.dataset.splitRateBase || "0") || 0;

    let net = 0;
    panel.querySelectorAll("[data-split-line]").forEach(function (line) {
      const amountEl = line.querySelector("[data-split-amount]");
      const typeEl = line.querySelector('input[name="lineCategoryType"]');
      const magnitude = amountEl ? Math.abs(parseGerman(amountEl.value)) : 0;
      // Per-line derived equivalents (read-only) — refreshed as the user types the spending amount.
      if (cross) {
        const acctCell = line.querySelector("[data-split-account-cell]");
        if (acctCell) acctCell.textContent = magnitude ? formatGerman(magnitude * rateFunding) : "";
        const baseCell = line.querySelector("[data-split-base-cell]");
        if (baseCell) baseCell.textContent = magnitude ? formatGerman(magnitude * rateBase) : "";
      }
      if (!amountEl || !amountEl.value.trim()) return;
      // A transfer line (register §3.8, plan stage 7d.3) has no category type — its direction signs
      // it: FROM is an inflow (+, like income), TO an outflow (−, like expense).
      const dirEl = line.querySelector('input[name="lineTransferDirection"]');
      const dir = dirEl ? dirEl.value : "";
      if (dir) {
        net += dir === "FROM" ? parseGerman(amountEl.value) : -parseGerman(amountEl.value);
        return;
      }
      const type = typeEl ? typeEl.value : "";
      if (!type) return;
      net += type === "income" ? parseGerman(amountEl.value) : -parseGerman(amountEl.value);
    });

    const magnitude = Math.abs(net);
    const remaining = total - magnitude;
    const balanced = Math.abs(remaining) < 0.005;

    const remainingEl = panel.querySelector("[data-split-remaining]");
    if (remainingEl) remainingEl.classList.toggle("is-balanced", balanced);
    setText(panel, "[data-split-remaining-value]", formatGerman(remaining));
    const checkEl = panel.querySelector("[data-split-remaining-check]");
    if (checkEl) checkEl.style.display = balanced ? "" : "none";
    if (cross) {
      setText(panel, "[data-split-remaining-funding]", formatGerman(fundingTotalOf(panel) - magnitude * rateFunding));
      setText(panel, "[data-split-remaining-base]", formatGerman(baseTotalOf(panel) - magnitude * rateBase));
    }

    const directionEl = panel.querySelector("[data-split-direction]");
    if (directionEl) {
      const shown = cross ? magnitude * rateFunding : magnitude;
      const currency = panel.dataset.splitFundingCurrency;
      const code = currency ? " " + currency : "";
      if (net < -0.005) directionEl.textContent = "You will pay " + formatGerman(shown) + code;
      else if (net > 0.005)
        directionEl.textContent = "You will receive " + formatGerman(shown) + code;
      else directionEl.textContent = "No net payment";
    }
    const saveEl = panel.querySelector("[data-split-save]");
    if (saveEl) saveEl.textContent = balanced ? "Save" : "Save and update amount";
  }

  function setText(panel, selector, text) {
    const el = panel.querySelector(selector);
    if (el) el.textContent = text;
  }

  function fundingTotalOf(panel) {
    const el = panel.querySelector('input[name="fundingTotal"]');
    return el ? parseGerman(el.value) : 0;
  }

  function baseTotalOf(panel) {
    const el = panel.querySelector('input[name="baseTotal"]');
    return el ? parseGerman(el.value) : 0;
  }

  // ── Split focus management (register §3.10) ──────────────────────────────────
  // After the split panel opens or gains a line, put the cursor where the user will type next:
  // opening a split → the first line's amount (its category is already pre-selected); adding a line
  // → the new line's category (its amount is pre-filled from "the rest"). Keyed on the request path
  // so ordinary swaps (category resolve, commit, register repaint) never steal focus.
  function focusAfterSplitSwap(path) {
    const panel = document.querySelector("[data-split-panel]");
    if (!panel) return;
    if (path === "/register/split") {
      const amount = panel.querySelector("[data-split-amount]");
      if (amount) amount.focus();
    } else if (path === "/register/split/add-line") {
      const categories = panel.querySelectorAll('input[name="categoryText"]');
      const last = categories[categories.length - 1];
      if (last) last.focus();
    }
  }

  // ── Select-all on first focus of a numeric field (register UX) ───────────────
  // When focus first lands on a `.num` field that holds a value — via Tab or a click — select its
  // whole contents so typing replaces it (the common case). A further click in an already-focused
  // field positions the caret normally. Empty (placeholder) fields select nothing, so their
  // behaviour is unchanged. `pendingSelect` defers the mouse path to mouseup, because the browser's
  // own caret placement on mouseup would otherwise clear a selection made on focus.
  let pendingSelect = null;

  function isNumField(el) {
    return Boolean(el) && el.tagName === "INPUT" && el.classList.contains("num");
  }

  function onMouseDownSelect(event) {
    const el = event.target;
    pendingSelect = isNumField(el) && document.activeElement !== el ? el : null;
  }

  function onFocusInSelect(event) {
    const el = event.target;
    if (!isNumField(el)) return;
    // Keyboard focus (Tab) has no mousedown on this field — select now. The mouse path selects on
    // mouseup instead so the selection is not dropped by caret placement.
    if (pendingSelect !== el) el.select();
  }

  function onMouseUpSelect(event) {
    if (pendingSelect && pendingSelect === event.target) {
      event.preventDefault();
      pendingSelect.select();
    }
    pendingSelect = null;
  }

  document.addEventListener("keydown", onKeydown);
  document.addEventListener("mousedown", onMouseDownSelect);
  document.addEventListener("focusin", onFocusInSelect);
  document.addEventListener("mouseup", onMouseUpSelect);
  // Recompute the split readout whenever a field inside the panel changes.
  document.addEventListener("input", function (event) {
    if (event.target.closest && event.target.closest("[data-split-panel]")) {
      updateSplitReadout();
    }
  });
  document.addEventListener("DOMContentLoaded", function () {
    scrollToBottom();
    syncEditingRow();
    updateSplitReadout();
  });

  // After an htmx swap, a selected row may have been replaced; drop a stale selection so the next
  // arrow keypress re-selects from a clean state, re-anchor the newest-at-bottom scroll, and
  // re-mirror the dock's edit state onto the rows (all no-ops when htmx is absent).
  document.body &&
    document.body.addEventListener("htmx:afterSwap", function (event) {
      rows().forEach((r) => r.classList.remove(SELECTED));
      scrollToBottom();
      syncEditingRow();
      updateSplitReadout();
      const config = event.detail && event.detail.requestConfig;
      focusAfterSplitSwap(config ? config.path : null);
    });
})();
