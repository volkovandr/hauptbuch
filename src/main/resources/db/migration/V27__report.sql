-- V27 — saved Reports (reporting.md §14, plan stage d). A Report is a document, not a relation: the
-- spec is stored as jsonb, with a few promoted columns for what is actually queried on — name and
-- renderer (Renderer.java's own doc already anticipated both promoted columns). No normalised
-- report_dimension/report_filter/report_measure tables — nothing ever asks "which reports filter on
-- Food", and every v2 addition to ReportSpec would otherwise be a migration.
--
-- Presets (PresetCatalog) stay code-defined and are never rows here, so "a Preset cannot be
-- deleted" holds by construction — there is no report_id to delete one by.
create table report (
  report_id  bigint generated always as identity primary key,
  name       text not null,
  renderer   text not null,
  trend_line boolean not null default false,
  spec       jsonb not null
);
