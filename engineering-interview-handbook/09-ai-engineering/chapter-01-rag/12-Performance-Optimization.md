# 12 – Performance Optimization

## Executive Summary

Performance in a RAG system is a per-stage budgeting problem, not a
single number to chase. This chapter gives a concrete latency budget
across the pipeline, the specific levers for each stage, caching layer
design with real invalidation mechanics, and cost optimization —
synthesizing and making concrete what earlier chapters flagged in
passing.

## Latency Budget

A realistic p95 target for a streamed RAG response, broken down by
stage (numbers are illustrative — the discipline of assigning and
measuring a budget per stage matters more than these exact figures):

| Stage | Budget (p95) | Chapter |
|---|---|---|
| Auth + request routing | 10ms | — |
| Load conversation history (Redis) | 10ms | 07 |
| Query embedding | 50ms | 05 |
| Vector search (ACL pre-filtered) | 80ms | 06 |
| Reranking (cross-encoder, top-50→top-5) | 150ms | 06 |
| Prompt assembly | 5ms | 07 |
| **Time to first generated token (TTFT)** | 400ms | 08 |
| Full generation (streamed, perceived) | — | 08 |
| **Perceived time-to-first-content** | **~700ms** | — |
| Full answer completion (not blocking UX) | 2–4s | 08 |

The key insight to state explicitly: **because generation is streamed,
the number that matters for perceived UX is time-to-first-token
(~700ms), not full completion (~3s)** — this is why streaming is
characterized elsewhere in this handbook as close to mandatory rather
than a nice-to-have.

## Per-Stage Optimization Levers

### Retrieval (Chapters 05, 06)
- Cache query embeddings for repeated/common queries.
- Bound reranker input (top-50, not top-500) — reranking cost scales
  with candidate count for diminishing accuracy return past a point.
- Tune `ef_search`/`nprobe` to the minimum that still meets the recall
  bar on the eval set — don't leave ANN search parameters at a
  conservative default that trades away latency for unneeded recall.

### Generation (Chapter 08)
- Set `max_tokens` to the smallest value that comfortably covers real
  answers — an unbounded cap is an unbounded latency tail.
- Route to a smaller/faster model for sub-tasks that don't need the
  largest model's full reasoning capability — query classification,
  simple extraction, tool-call routing — and reserve the largest model
  for final synthesis (this "model tiering" pattern is one of the
  highest-leverage cost *and* latency levers available).
- Provider-side prompt caching for the static prefix (system prompt,
  few-shot examples) — the same boilerplate tokens get reprocessed on
  every request unless the provider caches the processed representation
  of that prefix.

### Parallelization
- Run independent steps concurrently rather than sequentially — e.g.,
  loading conversation history (Redis) and running query embedding don't
  depend on each other and shouldn't be awaited in series.
- In Java/Spring: `CompletableFuture`/reactive composition (`Mono.zip`)
  for independent I/O-bound calls, or virtual threads (Java 21+) for
  straightforward blocking-style code that still parallelizes cheaply
  across many concurrent requests without the thread-pool sizing
  concerns of platform threads — a natural fit here since most of this
  pipeline's latency is I/O wait (network calls to the embedding API,
  vector DB, reranker, and LLM provider), not CPU work.

## Caching Layers — Implementation Detail

Building on the caching taxonomy introduced in the AI Knowledge guide,
concretely for this pipeline:

```
Cache key design (critical correctness detail):

  Semantic answer cache key = hash(queryEmbeddingCluster, aclScopeHash)
                                                            ^^^^^^^^^^^^
                                            NEVER omit this — two users
                                            with different ACL scopes
                                            asking similar questions
                                            must never share a cache
                                            entry (Ch 06's ACL chapter)
```

| Layer | Store | TTL / invalidation |
|---|---|---|
| Query embedding cache | Redis, keyed by query text hash | Short TTL (hours) — cheap to recompute, low risk |
| Retrieval result cache | Redis, keyed by (query embedding cluster, ACL scope) | Invalidated by the same `doc-events` topic that drives re-ingestion (Ch 03/09) — event-driven, not TTL-only |
| Semantic answer cache | Redis/vector-indexed cache, keyed by (query similarity, ACL scope) | Conservative similarity threshold; event-driven invalidation + short TTL backstop |
| Provider prompt cache | Provider-managed (Anthropic/OpenAI) | Managed by provider, keyed on exact prefix match — no invalidation logic needed on your side |
| Embedding cache | Keyed by content hash | Effectively permanent per embedding-model version — invalidate wholesale on model migration (Ch 05) |

**Invalidation is event-driven, not TTL-primary**: a document-changed
event (Chapter 03's `doc-events`) should trigger a targeted cache
eviction for anything derived from that document, with TTL only as the
backstop for whatever slips through — relying on TTL alone means users
can see stale answers for up to the full TTL window after a real update.

## Batching

- **Ingestion-side embedding batching** (Chapter 05) — the highest-value
  batching opportunity, since bulk ingestion jobs process many chunks
  with no per-chunk latency requirement, unlike the query path.
- **Query-side batching is rarely applicable** — a live user request
  can't wait to be batched with other users' unrelated requests without
  adding latency, so this lever is specific to the async ingestion path.

## Cost Optimization

Cost and latency levers substantially overlap in a RAG system — most of
the list above (model tiering, `max_tokens` bounding, caching, reranker
input bounding) reduces both simultaneously, which is why performance
and cost tuning are usually done as one pass, not two:

- **Token budget monitoring**: track input/output tokens per request as
  a first-class metric (Chapter 09's cross-reference table, extended)
  with an alert threshold — a silent prompt-bloat regression (e.g.,
  someone raises `topK` from 5 to 15 "to improve quality") shows up here
  before it shows up as a budget overrun.
- **Model tiering** (above) is usually the single biggest cost lever —
  the largest model is rarely needed for every sub-task in the pipeline.
- **Cache hit rate as a cost metric**, not just a latency metric — a
  well-tuned semantic cache directly reduces LLM call volume.

## Benchmarking Methodology

- **Load test the query path** with realistic concurrency, not just
  single-request latency — connection pool exhaustion (to the vector DB,
  to the LLM provider) under concurrent load is a common gap between "it
  was fast in my manual test" and production behavior.
- **Separate the eval-quality suite from the load-test suite** — one
  measures correctness/faithfulness (Chapters 04, 06, 13), the other
  measures latency/throughput under load; conflating them makes neither
  signal clean.
- **Track p50/p95/p99 per stage** (per the budget table above), not just
  an aggregate end-to-end number — an aggregate p95 that's within budget
  can still hide one stage that's badly regressed and another that's
  unusually fast compensating for it.

## Common Interview Questions

1. Walk through a latency budget for a streamed RAG response, stage by
   stage.
2. Why does time-to-first-token matter more than total completion time
   for UX, and what does that imply about where to invest optimization
   effort?
3. Design the cache key for a semantic answer cache in a multi-tenant
   system — what happens if you get it wrong?
4. What's "model tiering" and why does it help both cost and latency
   simultaneously?
5. How would you load-test a RAG query pipeline, and what's different
   about testing it versus testing its answer quality?

## Principal Engineer Notes

Optimize the stage that's actually the bottleneck, measured, not the
one that's most interesting to optimize — instrument first (Chapter 09's
per-stage tracing), then act. In most RAG systems the LLM generation
step dominates total latency, which is exactly why streaming (Chapter
08) is the highest-leverage single intervention available before
reaching for any of the finer-grained levers in this chapter.

## Next Chapter

13 – Security
