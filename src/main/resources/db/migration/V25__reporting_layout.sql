-- V25 — Layouts (reporting.md §11, plan stage c). A Layout is a grid of Frames, each Frame showing
-- one Report — a Preset for now, since a saved Report's own persistence is stage d.
--
-- Only the main page's Layout is seeded here: a single-row table (the `settings` idiom) with one
-- Frame at (0, 0). The reporting page's own Layout, and general row x column configuration beyond
-- 1x1, are stage c's second work package and will extend this schema forward-only.

create table layout (
  layout_id    smallint primary key default 1 check (layout_id = 1),
  row_count    smallint not null,
  column_count smallint not null
);

insert into layout (layout_id, row_count, column_count) values (1, 1, 1);

create table layout_frame (
  layout_frame_id bigint generated always as identity primary key,
  layout_id       smallint not null references layout (layout_id),
  row_position    smallint not null,
  col_position    smallint not null,
  preset_slug     text,  -- null = an emptied Frame; a saved Report's own reference arrives in stage d
  unique (layout_id, row_position, col_position)
);

insert into layout_frame (layout_id, row_position, col_position, preset_slug)
values (1, 0, 0, 'net-worth-over-time');
