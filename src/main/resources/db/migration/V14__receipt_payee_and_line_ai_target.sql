-- Stage 9f — Post-process: the header payee the operator picks or creates on Save, and the AI's raw
-- target term kept per draft line for the ghost hint / provenance tooltip (data-model §13.1/§13.2).
-- Slice-local, landing with the sub-stage that consumes it (the stage-7 precedent). V13 was taken by
-- the 9e follow-up (settings.ai_system_prompt), so this is V14.

-- The header payee: null until Save (merchant_text stays the parse fact); created-on-Save via the
-- picker's create-new, exactly like the register dock's payee (register §3.4).
alter table receipt add column payee_id bigint references payee(payee_id);

-- The AI's raw target term for a draft line: the unresolved category echo, or the transfer signal
-- stored as `transfer: cash` / `transfer: card •1234`. Populated by the 9e seeder; rendered as a
-- grey hint on an unresolved line and a provenance tooltip on a resolved one (post-process, 9f).
alter table receipt_line add column ai_target_text text;
