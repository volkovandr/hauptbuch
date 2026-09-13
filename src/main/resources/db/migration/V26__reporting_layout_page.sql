-- V26 — the reporting page's own Layout (reporting.md §11, plan stage c part 2). V25's Layout 1 was
-- assumed to be the only one; this identifies a Layout by the page it belongs to instead, and seeds
-- a second, empty Layout for the reporting page (starting 1x1, its one Frame unconfigured).

alter table layout drop constraint layout_layout_id_check;
alter table layout add column page text;
update layout set page = 'main' where layout_id = 1;
alter table layout alter column page set not null;
alter table layout add constraint layout_page_check check (page in ('main', 'reports'));
alter table layout add constraint layout_page_key unique (page);

insert into layout (layout_id, page, row_count, column_count) values (2, 'reports', 1, 1);

insert into layout_frame (layout_id, row_position, col_position, preset_slug)
values (2, 0, 0, null);
