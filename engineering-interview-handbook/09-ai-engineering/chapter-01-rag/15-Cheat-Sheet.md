# 15 – Cheat Sheet

## Executive Summary

One dense page. No prose beyond what's needed. For the 30 minutes before
an interview, not for learning the material the first time.

## The Pipeline, One Line

```
Ingest: Source → Connector → Kafka → Parse → Chunk → Embed → Vector DB Upsert
Query:  Query → Embed → Vector Search (ACL pre-filtered) → Rerank → Prompt → Stream Generate → Cite
```

## Chunking — Quick Pick

| Content shape | Strategy |
|---|---|
| Plain unstructured text | Fixed-size + 10–20% overlap |
| Headings/sections (wiki, docs) | Structure-aware (split on headings) — **default for enterprise docs** |
| Long prose, no markup | Semantic (embedding-similarity breakpoints) |
| Source code | Syntax-aware (function/class boundaries) |
| Need precision AND context | Parent-document (small chunk indexed, large chunk served) — **production default once maturing past simple strategies** |

Typical size: 200–500 tokens, 10–20% overlap. Tune against a labeled
eval set (query → correct chunk), not by convention.

## Embedding Models — Quick Pick

| Need | Pick |
|---|---|
| General-purpose default | `text-embedding-3-small` / `bge-large-en` |
| Highest quality, cost tolerant | `text-embedding-3-large` |
| Self-hosted, fast, small footprint | `all-MiniLM-L6-v2` |
| Specialized domain (legal/medical/code) | Domain-tuned model — beats general-purpose |

**Changing embedding models = full corpus re-embed migration.** Old and
new vectors are never comparable. Parallel collection → eval-gated
cutover → decommission old.

## Similarity

- **Cosine** = default, magnitude-invariant.
- **Dot product** = cheaper, equals cosine if vectors pre-normalized.
- Normalize at write time → use dot product at query time (common perf win).

## ANN Index — Quick Pick

| Index | Query knob | Notes |
|---|---|---|
| **HNSW** | `ef_search` (↑ = better recall, ↑ latency) | Production default |
| **IVF** | `nprobe` | Faster build, generally lower recall than HNSW at same latency |
| **PQ** | — | Compresses vectors 10–30x; adds recall cost; use when index size is the bottleneck |

## ACL Rule (memorize verbatim)

> **Pre-filter, never post-filter. Fail closed, never fail open.**
> ACL/tenant metadata must be a predicate *inside* the ANN query, not a
> filter applied after fetching results.

## Hybrid Search

```
RRF_score(doc) = Σ 1 / (k + rank_in_list_i)     // k ≈ 60
```
Merges vector-search rank list + BM25/keyword rank list. Catches
exact-match terms (SKUs, codes) that pure vector search misses.

## Retrieval → Rerank

```
Retrieve top-50 (cheap, bi-encoder ANN)  →  Rerank to top-5 (cross-encoder)
```
Bi-encoder: query & doc embedded separately (fast, pre-computable).
Cross-encoder: query & doc seen together (accurate, can't pre-compute).

## Prompt Structure (fixed order)

```
[SYSTEM: role, grounding rule, "say I don't know", citation format]
[CONTEXT: reranked chunks, delimited, treated as DATA not instructions]
[HISTORY: recent turns, truncated/summarized]
[QUERY: current user message, last]
```

## Generation Defaults

| Param | RAG default |
|---|---|
| Temperature | 0–0.3 |
| max_tokens | explicit cap, never unbounded |
| Similarity threshold gate | skip LLM call entirely if top match is weak |

## SSE Event Types

```
event: token      — incremental answer text
event: citation   — structured source reference (server-resolved metadata)
event: done       — terminal, success, {tokensUsed}
event: error      — terminal, failure, {code}
```
Explicit terminal events > inferring completion from connection close.
Disable proxy/ingress buffering on this route.

## Latency Budget (streamed, p95, illustrative)

| Stage | Budget |
|---|---|
| History load + query embed | ~60ms |
| Vector search (ACL filtered) | ~80ms |
| Rerank | ~150ms |
| **Time to first token** | **~400ms → ~700ms perceived** |
| Full completion (non-blocking, streamed) | 2–4s |

## Caching Layers

| Layer | Key includes | Invalidation |
|---|---|---|
| Query embedding | query text hash | short TTL |
| Retrieval result | query cluster + **ACL scope** | event-driven (doc-events) + TTL backstop |
| Semantic answer | query similarity + **ACL scope** | event-driven + TTL backstop |
| Provider prompt cache | static prefix | provider-managed |
| Embedding cache | content hash | invalidate wholesale on model migration |

**Never omit ACL scope from a shared cache key** — cross-user leak.

## Idempotency & Delivery

- Kafka default = **at-least-once** → consumers must be idempotent.
- Upsert key: `(sourceSystem, sourceId, contentHash)`.
- Outbox pattern solves dual-write (DB write + event publish atomicity);
  does **not** give exactly-once — still pair with idempotent consumers.

## Common Failure → Likely Cause → Chapter

| Symptom | Check first | Chapter |
|---|---|---|
| Irrelevant/no context retrieved | Chunking quality, then embedding model mismatch | 04, 05 |
| Right doc never retrieved | Recall@k on eval set, ANN `ef_search`/`nprobe` too low | 06 |
| Confidently wrong answer | Similarity threshold gate missing, or grounding prompt weak | 07, 08 |
| Slow "feels slow" complaints | Streaming not actually working (check proxy buffering) | 08, 11 |
| Duplicate/corrupted index entries | Missing idempotent upsert key | 03 |
| Cross-user data appears | ACL post-filter instead of pre-filter, or cache key missing ACL scope | 06, 12, 13 |
| Answer references stale info | Cache invalidation is TTL-only, not event-driven | 12 |

## OWASP LLM Top 10 — Rapid Recall

```
LLM01 Prompt Injection            → delimit context, least-priv tools
LLM02 Insecure Output Handling    → never render/eval model output raw
LLM03 Training Data Poisoning     → N/A for pure RAG (no fine-tuning)
LLM04 Model DoS                   → bound tokens, context, agent loops
LLM05 Supply Chain                → vet providers/deps
LLM06 Sensitive Info Disclosure   → PII scan + ACL pre-filter
LLM07 Insecure Plugin/Tool Design → narrow, least-privilege @Tool methods
LLM08 Excessive Agency            → human confirm for irreversible actions
LLM09 Overreliance                → citations + faithfulness monitoring
LLM10 Model Theft                 → mainly self-hosted proprietary models
```

## Spring AI — Key Classes

```
ChatClient            — fluent chat API, provider-agnostic
ChatModel              — provider binding (OpenAI/Bedrock/Anthropic/Ollama)
VectorStore             — pluggable embedding store (pgvector/Redis/Pinecone)
Advisor                 — interceptor: QuestionAnswerAdvisor, MessageChatMemoryAdvisor
DocumentReader/Transformer — ingestion ETL (TikaDocumentReader, TokenTextSplitter)
@Tool                    — model-callable Java method
.entity(Record.class)    — structured output binding
```

## If You Remember Nothing Else

Retrieval quality bounds answer quality more than model choice.
Enforce ACL inside retrieval, fail closed. Stream for perceived latency.
Ground every answer, permit "I don't know," cite from server-resolved
metadata — never trust the model's own citations. Treat prompts,
chunking config, and embedding models as versioned, migration-aware
production artifacts, not casual edits.

---

*This completes Chapter 01 — Retrieval-Augmented Generation.*
