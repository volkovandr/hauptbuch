-- Landing-page Balances panel (CONTEXT.md "Pinned account", issue landing-page/01): an opt-in,
-- per-account flag surfacing an asset/liability account and its balance on the landing page, so
-- opening the app answers "how much money do I have".
--
-- Default false; the account editor is the only writer. The flag persists through close/delete —
-- the panel filters those out at render time rather than clearing the flag here.

alter table account add column show_on_main_page boolean not null default false;
