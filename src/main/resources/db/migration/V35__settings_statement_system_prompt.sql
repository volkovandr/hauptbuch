-- V35 — the operator-editable statement-parser system prompt (statements.md §3.2, slice e2).
-- Stored on the single settings row beside ai_system_prompt (V13); NULL means "use the built-in
-- default the StatementPromptBuilder ships". Parsing instructions only (ARCH-08): no ledger content.
alter table settings add column statement_system_prompt text;
