# Receipt analysis spends unpredictable output tokens; thinking can be neither controlled nor seen

Status: needs-triage
Category: enhancement
Severity: high
Area: Receipts — AI analyse, interactive (stage 9e) and batch (stage 9h) paths; Settings → AI

Found 2026-09-13 by the owner. Some receipts use **about 10× more tokens** than others with roughly
the same size and number of items. The cost can't be predicted, and nothing in the app shows why.

## Why it happens (probable cause)

Neither call site sets `thinking` or `effort`. `AnthropicReceiptParser.java:39-50` and
`AnthropicReceiptBatchClient.java:52-60` leave both at the model default. This is on purpose, per
the parser's Javadoc ("stays valid across whatever model the operator configures").

On the default model `claude-sonnet-5` (`AiSettings.DEFAULT_MODEL`), omitting `thinking` means
**adaptive thinking at effort `high`**. With adaptive thinking the model decides for each request
how much to think. A blurry photo, a faded line, an unusual layout, or an item that matches no
category can each set off long reasoning. The visible TOON output stays about the same size.

This is a probable cause, not a verified one. Today there is no data to confirm it:

- **Thinking can't be seen.** On Sonnet 5 the thinking display defaults to `omitted`, so thinking
  blocks come back with empty text. `AnthropicPrompts.concatText` reads text blocks only.
- **Thinking tokens aren't counted separately.** `usage.output_tokens` is thinking plus visible
  text, and `receipt.tokens_out` records that combined number.

What the defaults do on other models, when `thinking` is omitted:

| Model | Thinking by default | Notes |
|---|---|---|
| `claude-sonnet-5` | on (adaptive), effort `high` | `disabled` is accepted |
| `claude-opus-5` | on (adaptive), effort `high` | `disabled` is accepted only at effort ≤ `high`; with `xhigh`/`max` it returns 400 |
| `claude-fable-5` / `claude-fable-5-1` | always on | `disabled` returns 400 |
| `claude-opus-4-8` / `4-7`, Sonnet 4.6 | **off** | adaptive must be requested explicitly |
| `claude-haiku-4-5` | off | no adaptive mode and no `effort` (both return an error) |

## What to build

### 1. Two new AI settings

New typed columns on `settings` (data-model §3.8), edited in the AI section of the Settings page:

- **`ai_effort`** — `low` / `medium` / `high` / `xhigh` / `max`. `NULL` means the parameter is not
  sent (the model default, `high`). Sent as `output_config.effort`.
- **`ai_thinking`** — on/off toggle.
  - **On** sends `thinking: {type: "adaptive", display: "summarized"}`. `summarized` is required
    for step 2: without it the thinking text comes back empty.
  - **Off** sends `thinking: {type: "disabled"}`.
  - Suggested default: on. That matches today's behaviour on Sonnet 5 and gives us the reasoning to
    look at.

Some model/setting combinations are invalid (see the table above). Settings accepts any model id
string, so full validation on save isn't possible. Recommended approach:

- Send what is configured.
- Let a 400 surface through the existing `parse_error` path. It already fails the receipt with the
  API message.
- Add a one-line hint under the fields: "thinking off is rejected by Fable models, and by Opus 5
  above `high`; Haiku supports neither setting".

> **Prefer lowering effort over turning thinking off.** Anthropic's migration guidance for the 5
> family: with thinking disabled, the model can leak `<thinking>`/internal XML tags into the
> visible response. For us, that would land inside the TOON and break decoding. Keeping thinking
> on at `low`/`medium` gets most of the savings. The toggle is still worth having for measurement,
> but the hint should point here first.

Both call sites must send the same settings. Single and batch mode already share one prefix via
`AnthropicPrompts`; put the thinking/effort builder there too so the two modes can't drift apart.

Prompt caching: our breakpoint is on the system blocks. Per Anthropic's docs, changing thinking
parameters invalidates cached *message* content but not system/tools, so the "Analyse (cached)"
button should keep working. Confirm this with `tokens_cache_read` after the change.

### 2. Store the reasoning and let the operator read it

- **`receipt.parse_thinking text`** — the summarized thinking from every `thinking` block,
  concatenated in order. Stored verbatim once and never changed, like `parse_raw`. `NULL` when
  thinking was off or nothing came back. `redacted_thinking` blocks (encrypted, not readable) are
  stored as a short placeholder line, so the operator can see that reasoning was withheld.
- **Freeze the configuration used for each parse**, the same way `parse_cost` is frozen. Without
  this, receipts can't be compared after the settings change:
  - `parse_model text` — the model isn't recorded per receipt today
  - `parse_effort text`
  - `parse_thinking_enabled boolean`
- **UI** — on the processing screen, next to the existing telemetry line
  (`receipt-process.html:412-422`), add a collapsed `<details>` named "Model reasoning" that shows
  `parse_thinking` as preformatted text. Plain `<details>`, no JS, as in the re-seed block.
  - Show it on **`failed`** receipts too. Reasoning is most useful there, especially when a parse
    was truncated at `max_tokens` (issue 02).
  - Label it as a *summary*: it is not the full reasoning that was billed.
- **Batch path** — read the thinking blocks from each `MessageBatchIndividualResponse` the same way
  as in single mode.
- **ARCH-08 / logging** — nothing new goes *to* the AI. The reasoning is model output about the
  receipt, stored locally. Log it at **DEBUG** only, next to the existing "Analyse response" line,
  never at INFO.

What the operator can do with it: see that the model struggled to read a line (retake the photo,
crop tighter), or hesitated between two categories (add an alias or guidance note to the AI
Vocabulary, or adjust `ai_system_prompt` / the receipt's `ai_note`).

### 3. Count thinking tokens separately from visible output

**The API does not return a separate thinking count.** Checked against SDK 2.34.0:
`com.anthropic.models.messages.Usage` has `inputTokens`, `outputTokens`, the two cache counts,
`serverToolUse`, `serviceTier` and `inferenceGeo`, and nothing else. `output_tokens` is billed
thinking plus visible text, combined.

It can be **derived** instead:

```
tokens_thinking ≈ tokens_out − countTokens(visible TOON text)
```

- **How:** after a successful parse, call `client.messages().countTokens(...)` with the same model
  and the concatenated visible text as the only message. Subtract a small fixed amount for the
  message wrapper, measured once by counting a minimal message. Token counting is free but has its
  own rate limit. It adds one short network call per parse; for batches, do it when results are
  collected.
- **Stored as** `receipt.tokens_thinking int`, nullable.
  - `0` when thinking was off.
  - `NULL` when the count call failed. Log a WARN; the parse still succeeds.
  - `tokens_out` stays the billed total, so `parse_cost` doesn't change.
- **Displayed** as e.g. `300 out (≈ 240 thinking)`. The `≈` is deliberate: it's derived, not
  reported by the API.
- **Rejected alternatives:**
  - Counting tokens in the *summary* — wrong: the summary is much shorter than the billed thinking.
  - A characters-per-token estimate — inaccurate, especially on German receipt text.

## Done when

- Settings has an effort select and a thinking toggle. Both are stored in `settings`, and both
  analyse paths (single and batch) send them.
- A parsed receipt stores `parse_thinking`, `parse_model`, `parse_effort`,
  `parse_thinking_enabled` and `tokens_thinking`, and the processing screen shows the reasoning in a
  collapsed panel (processed and failed receipts).
- The telemetry line shows thinking tokens separately from visible output.
- An invalid combination (e.g. Fable + thinking off) fails the receipt with a readable
  `parse_error`, not a 500.
- data-model §3.8 (settings) and the `receipt` table section record the new columns.
- `./gradlew check` is green.

## Tests (per CLAUDE.md §6)

- **Unit:** the request builder in `AnthropicPrompts` for each combination (effort unset/set ×
  thinking on/off); reading the response (text vs thinking vs redacted_thinking blocks); the
  thinking-token arithmetic, including the count-failure → `NULL` case.
- **Integration:** migration applies; the receipt repository round-trips the new columns; the
  settings repository round-trips `ai_effort` / `ai_thinking`; the processing screen renders the
  reasoning panel for processed and failed receipts.

## Open questions for the owner

1. Should thinking default to on (today's Sonnet 5 behaviour, and gives visibility), or off?
2. Is the extra `countTokens` call per parse acceptable, or should it be a setting?
3. Would it help to later add a list column or sort by `tokens_thinking`, so expensive receipts
   are easy to find? (Not in this issue; issue 11 covers list sorting.)

## Comments
