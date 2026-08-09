# `max_tokens` cap (4096) truncates AI receipt output well below any "big" receipt

Status: resolved
Category: bug
Severity: high
Area: Receipts — AI analyse, interactive path (stage 9e) and Batches API path (stage 9h)

Found while testing stage 9h (batch + prompt caching). The receipt-analysis request caps output at
4096 tokens, and that ceiling turns out to bind at ordinary receipt sizes, not just unusually large
ones — with a silent-corruption risk still open and unchecked.

## Root cause

Both call sites share one hardcoded constant:

```java
// AnthropicPrompts.java:29
static final long MAX_TOKENS = 4096L;
```

used identically in `AnthropicReceiptParser.java:36` (interactive, stage 9e) and
`AnthropicReceiptBatchClient.java:52` (batch, stage 9h). Not configurable per request, not exposed
via `application.yml` or the settings table — a compile-time constant only.

The default model is `claude-sonnet-5` (`AiSettings.DEFAULT_MODEL`), and `AnthropicReceiptParser`
deliberately leaves `thinking` at the model default so the request stays valid across whatever
model the operator configures (see its class Javadoc). On Sonnet 5, omitting `thinking` runs
**adaptive thinking**, and thinking tokens are billed against the same `max_tokens` budget as the
visible response. So the 4096-token budget has to cover thinking *and* the full TOON item list
together — there's less headroom than the raw number suggests.

## Evidence (both observed this week)

1. **Today — 36-item receipt, sent as a batch: nothing came back at all.** Had to open the
   Anthropic Console to find out why — the batch result carried `stop_reason: "max_tokens"` at
   exactly 4096 output tokens. Nothing in the app surfaced this as a truncation; it just produced
   no usable result.
2. **Several days ago — 20-item receipt, sent interactively (no prompt caching): output stopped at
   exactly 4096 tokens**, mid-way through the last item's line. This one failed loudly — the TOON
   parser rejected it — and the operator had to manually edit the AI response to recover the
   receipt. 20 items was already enough to hit the wall.

## Recommendation (owner decision, 2026-08-06)

Bump `MAX_TOKENS` **4x, to 16384**. Reasoning: it's hard to imagine a real receipt with much more
than 50 items, and a receipt that large would be awkward to scan/photograph as a single document
anyway — so chasing more headroom than 4x isn't worth it right now. Sonnet 5 supports up to 128K
output tokens, so 16384 is well within model limits on both the interactive and batch paths; the
batch path has no latency/timeout concern either way.

**Worth checking during implementation:** the interactive path (`AnthropicReceiptParser`) is a
synchronous, non-streaming call. Raising `max_tokens` well above ~16K on a non-streaming request
risks SDK HTTP timeouts — confirm 16384 is still safely under that line, and switch to streaming if
not. The batch path is unaffected (async, no such timeout).

## Open follow-up — explicitly deferred, not part of this fix

Raising the cap makes truncation rarer, not impossible, and it doesn't address what happens when it
*does* happen. Both observed failures this week failed loudly (batch: no result at all; interactive:
TOON parse error) — but that's not guaranteed. Need to check how the TOON parser/validator behaves
under two specific truncation shapes:

1. **An item line cut mid-line** (mid-token truncation, as seen in the interactive case above) —
   confirm this reliably fails parsing rather than silently dropping or mangling the partial item.
2. **A structurally well-formed TOON whose declared item count doesn't match the number of item
   rows actually present** — confirm the parser/validator cross-checks declared-count against
   actual-row-count and rejects the mismatch. This is the scarier case: if a truncation happens to
   land cleanly between two items (structurally valid, just short), the parser could accept it as a
   normal result with **no error at all**, silently producing a receipt with fewer items than the
   real one — understating an expense with nothing to catch it.

This investigation and any resulting parser hardening is **out of scope for this issue** — filed
here as a known gap to pick up separately.

## Comments

Filed 2026-08-06 after the owner hit both failure modes independently while testing stage 9h (batch
+ prompt caching, implemented 6678aef, not yet owner-confirmed). Diagnosis and the 4x recommendation
were worked out in conversation with Claude; the TOON-parser follow-up was raised in the same
conversation and deliberately deferred rather than investigated immediately.

Resolved 2026-08-09: rather than hardcoding 16384, made the cap a config value — new
`AnthropicProperties` record (`@ConfigurationProperties("hauptbuch.receipts.ai")`, same pattern as
`ReceiptStorageProperties`), `hauptbuch.receipts.ai.max-tokens: 16384` in `application.yaml`,
injected into both `AnthropicReceiptParser` and `AnthropicReceiptBatchClient` in place of the old
`AnthropicPrompts.MAX_TOKENS` constant. Checked the deferred SDK-timeout question directly instead
of leaving it open: the Anthropic Java SDK's default request timeout is 10 minutes
(`Timeout.request()`, unconfigured in `AnthropicClients`), far above what generating 16384 tokens
takes even at worst case — no streaming switch needed on the interactive path. `./gradlew check`
green. The TOON-truncation-hardening follow-up remains explicitly out of scope, unchanged.
