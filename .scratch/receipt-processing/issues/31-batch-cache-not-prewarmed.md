# Batch submit fires all members in parallel with no cache pre-warm, so a batch writes the cache many times instead of once

Status: resolved
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

**Production evidence, 2026-09-20 — pre-warm helps but does not eliminate the problem.** First
real batch through the fix, 6 members:

```
12:42:25  Batch cache pre-warm: tokensIn=6 tokensCacheWrite=1478 cost=0.003707
12:42:27  Batch msgbatch_...5naM submitted: receipts=6 model=claude-sonnet-5 cached=true
12:44:23  Batch msgbatch_...5naM finished: receipts=6 succeeded=4 failed=2 tokensIn=10892
          tokensOut=9283 tokensCacheWrite=7390 tokensCacheRead=1478 cost=0.066695
```

`tokensCacheRead=1478` is exactly one prefix's worth — only **1 of the 6 members** read the
pre-warmed entry. `tokensCacheWrite=7390` is exactly `5 × 1478` — the other **5 each wrote their
own fresh entry**, the same failure the fix targets, just less often (5 writes instead of 6).

Checked against Anthropic's own docs
(`platform.claude.com/docs/en/build-with-claude/batch-processing.md`, "Using prompt caching with
Message Batches"): *"because batch requests are processed asynchronously and concurrently, cache
hits are provided on a best-effort basis. Users typically experience cache hit rates ranging from
30% to 98%."* This is not a bug in the pre-warm implementation — the mechanics (standalone,
synchronous `max_tokens: 0` call, identical `AnthropicPrompts.systemBlocks`-built system block,
completed before `batches().create`) match Anthropic's documented pre-warm pattern exactly. But
Anthropic does not document pre-warming as a fix for Batches-API cache-hit variance specifically —
best-effort/concurrent-dispatch is stated as inherent to the Batches API, not something pre-warming
is claimed to eliminate. Corrected the class Javadoc, which had overclaimed "every member finds the
cache already populated" — fixed to describe the real, partial effect.

The one lever Anthropic's docs *do* document specifically for batches: **"Because batches can take
longer than 5 minutes to process, consider using the 1-hour cache duration... for better cache hit
rates when processing batches with shared context."** Not yet applied here — doing so isn't a
one-line change in this codebase: `AiSettings.priceCacheWrite` (data-model §3.8, the Settings
screen's rate fields) is a single flat rate calibrated for Anthropic's 5-minute-TTL write price
(1.25×); Anthropic prices a 1-hour-TTL write differently (2×), so switching the batch path's
`cache_control` to `ttl: "1h"` without a second rate field would make every batch member's frozen
`parse_cost` (and the pre-warm's logged cost) under-report the real charge whenever a member writes
instead of reads. Raised with the owner rather than pushed unilaterally, since it touches the
pricing/settings data model — pending direction on whether to add a second cache-write rate (and
Settings-screen field) for the 1-hour tier, or accept the partial improvement as-is and only fix the
misleading claim in comments (done).

**Owner decision, 2026-09-20:** leave the 5-minute TTL and accept the best-effort hit rate — do not
add a second cache-write rate field or switch the batch path to the 1-hour tier. The pre-warm call
(and its cost logging) stays as implemented; it demonstrably helps (production: 1 of 6 members hit
it rather than 0) without touching the pricing/settings data model. Resolved with this as the final
shape.
