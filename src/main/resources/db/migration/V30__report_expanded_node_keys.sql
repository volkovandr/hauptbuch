-- V30 — remembers a saved Report's row-tree expansion state (reporting.md §9.1/§9.2, plan stage
-- e2). A promoted column, not part of the `spec` document (like `renderer`/`trend_line`): null
-- means "no explicit state yet, compute auto on every render" (§9.2); a non-null jsonb array (even
-- empty) means the owner has hand-toggled at least one node, and that literal set of expanded
-- top-level keys takes over from auto for good, per Report. Presets and unsaved drafts have no row
-- here to store it in and so always fall back to auto (§9.1 — expansion state is deliberately not
-- in the URL either).
alter table report
  add column expanded_node_keys jsonb;
