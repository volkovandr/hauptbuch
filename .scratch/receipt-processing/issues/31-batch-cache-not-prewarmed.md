# Batch submit fires all members in parallel with no cache pre-warm, so a batch writes the cache many times instead of once

Status: ready-for-agent
Category: bug
Severity: medium
Area: Receipts — AI Batches API path (stage 9h, prompt caching)

`AnthropicPrompts`'s class Javadoc states the whole economic premise of 9h's batch caching: "a batch
is precisely the case where the shared prefix is read many times inside the TTL, so the write pays
for itself." In practice that premise doesn't hold — observed in production, a batch of receipts
produces several tens of cache **writes** (25 % premium) instead of one write followed by many cache
**reads** (90 % discount).

## Root cause

`AnthropicReceiptBatchClient.submit` (`AnthropicReceiptBatchClient.java:44-75`) builds one
`BatchCreateParams` with every member carrying an identical, byte-for-byte system block — same
prefix, same `cache_control` breakpoint, built once via `AnthropicPrompts.systemBlocks(...)` per
item (`AnthropicReceiptBatchClient.java:55-56`) — and submits them all in a single
`batches().create(...)` call.

The assumption baked into that design is that the first member to run populates the cache and every
member after it reads the populated entry. That assumption doesn't match how the Batches API
actually executes: Anthropic dispatches a batch's members for parallel processing on their side, not
strictly one-at-a-time. With no cache entry yet written, a large fraction of the members start
before any of their siblings' cache writes have landed — each of those finds the cache empty and
performs its own write. The result is not "1 write + (N−1) reads" but closer to "N (mostly
independent) writes," which is worse than not caching this shared prefix at all: every write already
costs +25 % over reading fresh, uncached input, so a member that writes instead of reading pays a
premium for no benefit.

This is a known limitation of the Batches API, not a caller-side bug in the request shape — the
system blocks really are identical across members (that part of the 9h design is correct and should
stay as-is). The gap is a missing step: nothing populates the cache *before* the batch's members
start racing for it.

## Recommendation

Anthropic's documented pattern for this exact situation is **cache pre-warming**: before calling
`batches().create(...)`, issue one standalone, synchronous Messages API call carrying the identical
cached prefix, with `max_tokens: 0`. That call reads the (system prompt + AI Vocabulary) prefix into
the model, performs the single cache write, and returns immediately — empty content, zero output
tokens billed, nothing to discard. Once it completes, every member of the subsequent batch create
call finds the cache already populated and reads it instead of writing it.

Two things have to be exact for this to work:

1. **The pre-warm call's `cache_control` breakpoint must land on exactly the same block as the batch
   members' — same prefix text, same block structure.** `AnthropicPrompts.systemBlocks` already
   builds this block the same way for both the single-parse and batch paths, so the pre-warm call
   should build its system block through that same helper rather than a hand-rolled one, or it risks
   producing a prefix that hashes differently and warms a cache entry the batch never matches.
2. **The pre-warm call must complete (not just be sent) before `submit` calls `batches().create`.**
   A fire-and-forget or overlapping call defeats the fix — the ordering is the entire point.

Implementation-wise this likely means: add a `warm(...)` (or similarly named) method to
`AnthropicClients`/`AnthropicReceiptBatchClient` that issues a single, non-batch `messages().create`
with `maxTokens(0)`, the same `systemOfTextBlockParams` built from `AnthropicPrompts.systemBlocks`,
and a minimal (or no) user turn, called synchronously from `ReceiptBatchAnalyser.submit` (or from
`AnthropicReceiptBatchClient.submit` itself) immediately before building and sending
`BatchCreateParams`. Confirm during implementation whether the Messages API accepts `max_tokens: 0`
with a user-turn-less request, or whether a minimal placeholder user block is required — and if the
image block is part of the cached prefix in this design (it is not; only the system block carries
`cache_control`, so the pre-warm call needs no image).

## Comments

Filed 2026-09-19, reported by the owner from observed batch behavior: submitting a batch of receipts
produces on the order of several tens of cache writes rather than the single write the 9h design
assumes. Root cause and the pre-warm recommendation (including the correction that the pre-warm call
must be a purpose-built `max_tokens: 0` no-op, not a throwaway real request) came from the owner,
who identified this as Anthropic's documented pattern for the exact failure mode.

Implemented 2026-09-20 on branch `feat/reporting`: `AnthropicReceiptBatchClient.submit` now calls a
new private `warmCache` first — a synchronous, non-batch `messages().create` with `maxTokens(0)`,
the same `AnthropicPrompts.systemBlocks(systemPrompt, true)` system block the batch members send
(so the cache key matches exactly), and a minimal `"warmup"` user turn (confirmed the Java SDK's
`MessageCreateParams` accepts `max_tokens: 0` with a plain string user message; no image needed,
since only the system block carries `cache_control`). A failed pre-warm is caught and logged at
WARN rather than failing the batch — members simply fall back to the pre-fix behavior of racing for
a cold cache.

Code review (medium effort) flagged one follow-up gap: the pre-warm call is itself billed (a real
cache-write charge) but was going unrecorded anywhere. Fixed in the same change: `AiSettings` now
travels through a new `pricing` field on `ReceiptBatchSubmission` (populated from the same
`settingsService.aiConfig()` call `ReceiptBatchAnalyser.submit` already made), and `warmCache` costs
its own usage via `AiSettings.costOf(...)` and logs it at INFO (`"Batch cache pre-warm:
tokensIn=... tokensCacheWrite=... cost=..."`) — visibility only, not folded into any receipt's
`parse_cost`, since attributing a shared-prefix write to one receipt would misstate that receipt's
frozen cost.

`./gradlew check` green (unit, integration, and SQL-logic tiers, plus Checkstyle/PMD/SpotBugs/
Spotless/JaCoCo). No new unit test: `AnthropicReceiptBatchClient` is a thin SDK adapter with no
branching logic to test without the network, same as its untested single-parse sibling
`AnthropicReceiptParser` — consistent with the existing pattern for this pair of classes. Not yet
owner-confirmed.
