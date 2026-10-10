# Statements e2: findings from the first real PDF parse

Status: resolved

## Symptom
First real parse (owner, 2026-10-10):
1. The parse failed with "Could not decode the parser response", and nothing was logged.
2. The page gave no sign that the (synchronous, slow) call was running.
3. After a successful parse the big PDF text area stays in the way.
4. A credit-card statement prints its signs the other way round (positive = the debt grows), so
   every line created from it books with the wrong sign — visible only in the register.

## Root cause / decisions
1. The model left a comma-bearing value unquoted (`USD 7,84 (KURS 1,1313)`), so the row was wider
   than its header and strict TOON decoding rejected the body. Owner fixed it through the prompt
   editor (always double-quote counterparty/description; dot decimals). The built-in default prompt
   is unchanged here. An undecodable body now logs a WARN (statement id + reason only).
2. The Parse with AI button disables itself and reads "Processing…" while the request runs
   (htmx `hx-disable-elt` + CSS on `.htmx-request`; no new JS).
3. The PDF text panel is a `<details>`, open until the statement is `processed`, then collapsed.
4. A "Reverse the sign" button above the matching table flips the amount (and the original amount)
   of every line. Enabled only while no line is matched. Tooltip (`.help`): "The negative sign
   indicates expenses or outgoing payments, positive sign indicates income or inbound payments.
   Your bank might report that differently. Use this button to fix the issue. Only possible when
   no transactions are matched." The opening/closing balances are not touched.
