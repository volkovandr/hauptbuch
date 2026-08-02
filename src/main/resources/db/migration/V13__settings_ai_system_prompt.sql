-- Stage 9e (owner feedback 2026-08-02) — the operator-editable receipt-parser system prompt.
-- Stored on the single settings row (data-model §3.8); NULL means "use the built-in default the
-- ReceiptPromptBuilder ships". Still parsing instructions only (ARCH-08): the operator curates the
-- text, the category list is injected from the AI Vocabulary at parse time, never ledger content.
--
-- Separate migration from V12 (not an edit of it): V12 has already applied on the running book, and
-- migrations are forward-only.
alter table settings add column ai_system_prompt text;
