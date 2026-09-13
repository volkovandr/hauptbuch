-- V28 — a Layout Frame may show a saved Report as well as a Preset (reporting.md §11, plan stage d
-- follow-up). `layout_frame.preset_slug`'s own comment (V25) already anticipated this: "a saved
-- Report's own reference arrives in stage d" — a sibling nullable column, not an overload of that
-- one. `on delete set null` so deleting a Report degrades its Frame to empty rather than failing
-- the delete — the same graceful-degrade behaviour a stale/renamed preset_slug already gets
-- (PresetCatalog.find returns empty; there is no FK to violate there).
alter table layout_frame
  add column report_id bigint references report (report_id) on delete set null;

alter table layout_frame
  add constraint layout_frame_one_reference_check
  check (preset_slug is null or report_id is null);
