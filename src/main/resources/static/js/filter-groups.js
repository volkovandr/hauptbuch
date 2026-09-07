/*
 * Filter group toggles — the third isolated bespoke-JS leaf, after the keyboard layer (keyboard.js)
 * and the Cropper image component (receipt-editor.js). Sanctioned by issue
 * transaction-register-ui/22; CLAUDE.md §1.6 and tech-stack §4.3 note it.
 *
 * DESIGN CONTRACT (read before touching): this is an ISOLATED LEAF, not a framework. Dependency-free
 * vanilla JS, driven entirely by data-attributes, that the rest of the htmx UI never imports. Remove
 * the script and the register's account filter still works: every real checkbox still ticks and
 * Apply still submits — only the group toggles and the all-ticked URL tidy-up are lost.
 *
 * It serves the register's account-filter panel (issue transaction-register-ui/22), and is agnostic
 * to where the grouping came from — account hierarchy (parent_id) or person ownership (the person↔
 * account junction) both render the same markup:
 *
 *   [data-filter-groups]                     the panel container
 *     input[data-filter-group="KEY"]         a tri-state group toggle (unnamed — never submitted)
 *     input[name="accountId"][data-filter-member="KEY …"]   a member; may belong to several groups
 *     [data-filter-all] / [data-filter-none] bulk links
 *
 * 1. Group state: each toggle reflects its members — checked if all are, indeterminate if some.
 * 2. Group click: ticks / unticks every member carrying that group's key.
 * 3. Bulk links: tick / untick every member in the panel.
 * 4. On submit of the filter form: if EVERY member is ticked, disable them so the query string
 *    carries no accountId at all — the server then re-resolves the whole picker (and the entry dock
 *    will not serialise a defaulted filter). With JS off the ids are all sent and the server
 *    collapses them the same way; the URL is just longer.
 *
 * Re-runs after every htmx swap, since a tab click replaces the whole control.
 */
(function () {
  "use strict";

  function panels() {
    return document.querySelectorAll("[data-filter-groups]");
  }

  function membersOf(panel, key) {
    return Array.prototype.filter.call(
      panel.querySelectorAll('input[name="accountId"][data-filter-member]'),
      function (input) {
        return input
          .getAttribute("data-filter-member")
          .split(/\s+/)
          .indexOf(key) !== -1;
      },
    );
  }

  function allMembers(panel) {
    return panel.querySelectorAll('input[name="accountId"]');
  }

  function refreshGroups(panel) {
    panel.querySelectorAll("input[data-filter-group]").forEach(function (toggle) {
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
    allMembers(panel).forEach(function (m) {
      m.checked = checked;
    });
    refreshGroups(panel);
  }

  function onSubmit(event) {
    var form = event.target;
    form.querySelectorAll("[data-filter-groups]").forEach(function (panel) {
      var members = Array.prototype.slice.call(allMembers(panel));
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

  function wire() {
    panels().forEach(refreshGroups);
  }

  document.addEventListener("change", function (event) {
    var target = event.target;
    var panel = target.closest("[data-filter-groups]");
    if (!panel) {
      return;
    }
    if (target.matches("input[data-filter-group]")) {
      onToggleGroup(panel, target);
    } else if (target.matches('input[name="accountId"]')) {
      refreshGroups(panel);
    }
  });

  document.addEventListener("click", function (event) {
    var target = event.target;
    var panel = target.closest("[data-filter-groups]");
    if (!panel) {
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

  document.addEventListener("submit", function (event) {
    if (event.target.classList.contains("register-filter")) {
      onSubmit(event);
    }
  });

  document.addEventListener("DOMContentLoaded", wire);
  document.body.addEventListener("htmx:afterSwap", wire);
})();
