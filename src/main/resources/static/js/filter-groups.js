/*
 * Filter group toggles — the third isolated bespoke-JS leaf, after the keyboard layer (keyboard.js)
 * and the Cropper image component (receipt-editor.js). Sanctioned by issue
 * transaction-register-ui/22; CLAUDE.md §1.6 and tech-stack §4.3 note it. Generalised (plan stage
 * d3-4, implementation-plan-reporting.md's cross-cutting note) so the same file drives both the
 * register's account filter (group mode) and the Report page's hierarchy filter sections (node
 * mode) — it is explicitly not a fourth leaf.
 *
 * DESIGN CONTRACT (read before touching): this is an ISOLATED LEAF, not a framework. Dependency-free
 * vanilla JS, driven entirely by data-attributes, that the rest of the htmx UI never imports. Every
 * checkbox is a real, named form field either way — remove the script and every filter still submits
 * correctly (register: every account still ticks, Apply still works; Report: every hierarchy node
 * still ticks and submits its own id) — only the live group/cascade feedback and the register's
 * all-ticked URL tidy-up are lost, both restored on the next full-page Apply round trip.
 *
 * A panel opts into ONE of two modes via its own [data-filter-groups] container:
 *
 * GROUP MODE (the register's account filter, issue transaction-register-ui/22) — default, no
 * data-filter-mode attribute:
 *
 *   [data-filter-groups]                       the panel container
 *     input[data-filter-group="KEY"]           a tri-state group toggle (unnamed — never submitted)
 *     input[data-filter-member="KEY …"]        a member; may belong to several groups (a real,
 *                                               named checkbox iff it carries no data-filter-group
 *                                               of its own — a nested group toggle carries both)
 *     [data-filter-all] / [data-filter-none]   bulk links
 *
 * 1. Group state: each toggle reflects its members — checked if all are, indeterminate if some.
 * 2. Group click: ticks / unticks every member carrying that group's key.
 * 3. Bulk links: tick / untick every named member in the panel.
 * 4. On submit of a [data-filter-tidy-up-all] form: if EVERY named member in one of its panels is
 *    ticked, disable them so the query string carries none of them at all — the server then
 *    re-resolves the whole picker (and the entry dock will not serialise a defaulted filter). With
 *    JS off every id is sent and the server collapses them the same way; the URL is just longer.
 *
 * NODE MODE (the Report page's Category/Account/Tag filter sections, reporting.md §11a.5) —
 * [data-filter-groups][data-filter-mode="node"]:
 *
 *   input[data-filter-node="ID"][data-filter-ancestors="ID1 ID2 …"]   one real, always-named
 *     checkbox per tree node (outermost ancestor first); ticking a node stores that node alone
 *     (§6.3's subtree rule) rather than its leaves, so a future child is covered automatically.
 *
 * Ticking a node forces every row whose ancestor list contains it to render ticked and disabled —
 * fixed, non-submitting, since the ticked ancestor already covers them — mirroring exactly what the
 * server itself renders on first load (ReportFilterView§nodeRows); untangling a tick restores its
 * former descendants to their own, independent state. Checkbox groups wait for Apply (reporting.md
 * §11a.3), so this is a live preview only — a bookmarked or JS-off submission still round-trips
 * correctly because the server recomputes the same ticked/disabled shape from whichever ids actually
 * arrive.
 *
 * Re-runs after every htmx swap, since a tab click / Apply replaces the whole control.
 */
(function () {
  "use strict";

  function panels() {
    return document.querySelectorAll("[data-filter-groups]");
  }

  function isNodeMode(panel) {
    return panel.getAttribute("data-filter-mode") === "node";
  }

  // ── Group mode (the register) ───────────────────────────────────────────

  function namedMembers(panel) {
    return Array.prototype.filter.call(panel.querySelectorAll("[data-filter-member]"), function (
      input,
    ) {
      return !input.hasAttribute("data-filter-group");
    });
  }

  function membersOf(panel, key) {
    return namedMembers(panel).filter(function (input) {
      return input.getAttribute("data-filter-member").split(/\s+/).indexOf(key) !== -1;
    });
  }

  function refreshGroups(panel) {
    panel.querySelectorAll("[data-filter-group]").forEach(function (toggle) {
      var members = membersOf(panel, toggle.getAttribute("data-filter-group"));
      var checked = members.filter(function (m) {
        return m.checked;
      }).length;
      toggle.checked = members.length > 0 && checked === members.length;
      toggle.indeterminate = checked > 0 && checked < members.length;
    });
  }

  function onToggleGroup(panel, toggle) {
    var members = membersOf(panel, toggle.getAttribute("data-filter-group"));
    members.forEach(function (m) {
      m.checked = toggle.checked;
    });
    refreshGroups(panel);
  }

  function setAll(panel, checked) {
    namedMembers(panel).forEach(function (m) {
      m.checked = checked;
    });
    refreshGroups(panel);
  }

  function onSubmit(event) {
    var form = event.target;
    if (!form.hasAttribute || !form.hasAttribute("data-filter-tidy-up-all")) {
      return;
    }
    form.querySelectorAll("[data-filter-groups]").forEach(function (panel) {
      var members = namedMembers(panel);
      if (
        members.length > 0 &&
        members.every(function (m) {
          return m.checked;
        })
      ) {
        members.forEach(function (m) {
          m.disabled = true;
        });
      }
    });
  }

  // ── Node mode (the Report page's hierarchy filter sections) ────────────

  function nodesOf(panel) {
    return panel.querySelectorAll("[data-filter-node]");
  }

  function refreshNodes(panel) {
    var rows = nodesOf(panel);
    // Un-force every previously-forced row, restoring it to its own last real tick (not
    // unconditionally unticking it) — a row can have been explicitly ticked before an ancestor
    // covered it, and un-ticking that ancestor must give it back, not discard it.
    rows.forEach(function (row) {
      if (row.dataset.filterForced === "true") {
        row.checked = row.dataset.filterOwnChecked === "true";
        row.disabled = false;
        delete row.dataset.filterForced;
      }
    });
    var ticked = [];
    rows.forEach(function (row) {
      // Every row not currently forced is either the user's own direct action or a value just
      // restored above — either way, this is its own real state, snapshotted before the forcing
      // pass below can overwrite it again.
      if (!row.disabled) {
        row.dataset.filterOwnChecked = String(row.checked);
      }
      if (row.checked) {
        ticked.push(row.getAttribute("data-filter-node"));
      }
    });
    if (ticked.length === 0) {
      return;
    }
    rows.forEach(function (row) {
      var ancestors = (row.getAttribute("data-filter-ancestors") || "").split(/\s+/);
      var covered = ticked.some(function (id) {
        return ancestors.indexOf(id) !== -1;
      });
      if (covered) {
        row.checked = true;
        row.disabled = true;
        row.dataset.filterForced = "true";
      }
    });
  }

  // ── Shared wiring ────────────────────────────────────────────────────────

  function wire() {
    panels().forEach(function (panel) {
      if (isNodeMode(panel)) {
        refreshNodes(panel);
      } else {
        refreshGroups(panel);
      }
    });
  }

  document.addEventListener("change", function (event) {
    var target = event.target;
    var panel = target.closest("[data-filter-groups]");
    if (!panel) {
      return;
    }
    if (isNodeMode(panel)) {
      if (target.matches("[data-filter-node]")) {
        refreshNodes(panel);
      }
      return;
    }
    if (target.matches("[data-filter-group]")) {
      onToggleGroup(panel, target);
    } else if (target.hasAttribute("data-filter-member")) {
      refreshGroups(panel);
    }
  });

  document.addEventListener("click", function (event) {
    var target = event.target;
    var panel = target.closest("[data-filter-groups]");
    if (!panel || isNodeMode(panel)) {
      return;
    }
    if (target.matches("[data-filter-all]")) {
      event.preventDefault();
      setAll(panel, true);
    } else if (target.matches("[data-filter-none]")) {
      event.preventDefault();
      setAll(panel, false);
    }
  });

  document.addEventListener("submit", onSubmit);

  document.addEventListener("DOMContentLoaded", wire);
  document.body.addEventListener("htmx:afterSwap", wire);
})();
