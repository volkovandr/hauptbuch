-- ── Per-currency leaf marker (data-model §6.5, plan stage 7d.1 follow-up) ────────────────────
-- Distinguishes a leaf CurrencyLeafService auto-provisions under a category (one per currency in
-- use) from a real, user-created category. Auto-managed leaves are hidden from every picker and
-- the categories screen, and are carried along automatically when their parent is subdivided,
-- renamed, or deleted — the flag, not the name, is what marks them (a currency leaf is simply
-- named after its own currency code, e.g. "EUR").
alter table account add column currency_leaf boolean not null default false;
