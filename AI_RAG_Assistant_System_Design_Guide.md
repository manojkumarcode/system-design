# AI/RAG Assistant — System Design Deep Dive

> Companion guide to `Engineering_Leadership_Interview_Preparation_Guide.md`
> and `AI_Knowledge_Interview_Guide.md`. This is the **System Design**
> checklist item "AI/RAG Assistant" worked end-to-end using the standard
> 10-point framework: Functional Requirements → NFRs → APIs → High-Level
> Design → Database → Caching → Messaging → Scaling → Failure Handling →
> Monitoring.
>
> Scenario used throughout: **"Design an AI assistant that answers
> employee questions over internal company documentation (wiki, Confluence,
> PDFs, Slack history), with source citations and per-user access
> control."** This is the most commonly asked variant and it exercises
> every interesting sub-problem (ingestion, retrieval, generation, ACLs,
> freshness, cost). The same skeleton adapts directly to a customer-support
> bot, a codebase assistant, or a product-docs assistant — callouts note
> where those variants diverge.

---

## Table of Contents

1. [Functional Requirements](#1-functional-requirements)
2. [Non-Functional Requirements](#2-non-functional-requirements)
3. [APIs](#3-apis)
4. [High-Level Design](#4-high-level-design)
5. [Database](#5-database)
6. [Caching](#6-caching)
7. [Messaging](#7-messaging)
8. [Scaling](#8-scaling)
9. [Failure Handling](#9-failure-handling)
10. [Monitoring](#10-monitoring)
11. [Capacity Estimation](#11-capacity-estimation)
12. [Interview Q&A](#12-interview-qa)

---

## 1. Functional Requirements

Nail these down out loud before designing anything — interviewers are
grading whether you scope before you architect.

- Users ask natural-language questions and get a synthesized answer, not a
  list of links.
- Every answer includes **citations** back to source documents (users must
  be able to verify).
- Answers respect the **requesting user's access control** — never surface
  content the user isn't authorized to see, even indirectly through a
  generated summary.
- Supports **multi-turn conversation** (follow-up questions reference prior
  context: "what about for the EU region?").
- Ingests from multiple heterogeneous sources (Confluence, PDFs uploaded by
  admins, Slack export, wiki) and stays reasonably fresh as source content
  changes.
- Handles the "I don't know" case explicitly — no answer found ⇒ say so,
  don't guess.
- (Stretch, mention if time allows) Feedback loop — thumbs up/down on
  answers to drive quality improvement.

**Explicitly out of scope** (say this out loud — it shows judgment):
model training/fine-tuning, real-time voice, and building the source
systems themselves (Confluence, Slack) — we integrate with them.

---

## 2. Non-Functional Requirements

| Dimension | Target | Why it matters here |
|---|---|---|
| **Latency** | p95 time-to-first-token < 1.5s; full answer streamed, perceived completion < 5s | LLM generation dominates latency (§10 in AI Knowledge guide) — streaming is not optional, it's load-bearing for this NFR |
| **Freshness** | New/updated docs searchable within minutes, not hours | Stale answers on internal docs (e.g., an outdated policy) are worse than no answer |
| **Consistency** | Retrieval index is eventually consistent with source docs; ACL checks must be **strongly consistent** (never stale-permissive) | Freshness of *content* can lag; freshness of *permissions* cannot — a revoked user must lose access immediately |
| **Availability** | 99.9% for the query path | Internal productivity tool, not payments-grade, but still a dependency people build workflows around |
| **Cost** | Bounded $/query, alertable | Token cost scales with traffic and context size — needs the same guardrails as any pay-per-use dependency |
| **Auditability** | Every answer traceable to exact retrieved chunks + prompt version | Required to debug wrong answers and for compliance in regulated orgs |
| **Scale** | O(10K–100K) documents, O(1K) concurrent users, O(10) QPS typical / O(100) QPS burst | Sets the vector DB and infra sizing conversation (§8, §11) |

**Call out the key tension explicitly:** freshness vs. cost/complexity
(real-time ingestion is more infra than nightly batch) and latency vs.
answer quality (more retrieval + reranking = better answers, slower
responses). State your default, then say what you'd change under
different constraints.

---

## 3. APIs

Keep it small and boring — this is not where the design complexity lives.

```
POST /v1/conversations
  → { conversationId }                         # start a new session

POST /v1/conversations/{id}/messages
  Body: { "query": "What's our PTO policy for contractors?" }
  → SSE stream:
      event: token       data: {"text": "Contractors "}
      event: token       data: {"text": "are not "}
      ...
      event: citation    data: {"chunkId": "...", "source": "HR Wiki > PTO", "url": "..."}
      event: done        data: {"messageId": "...", "tokensUsed": 842}
      event: error       data: {"code": "RETRIEVAL_TIMEOUT", "message": "..."}

GET  /v1/conversations/{id}
  → full message history (for reload/continuity)

POST /v1/messages/{id}/feedback
  Body: { "rating": "down", "reason": "incorrect" }
  → 204                                         # feeds the eval/quality loop

# Admin/ingestion-side (separate service boundary, separate authz)
POST /v1/admin/sources                          # register a source connector
POST /v1/admin/documents                        # manual upload (PDFs)
GET  /v1/admin/documents/{id}/status             # ingestion status
```

**Design decisions to narrate:**

- **SSE over WebSocket** — unidirectional streaming is all that's needed;
  SSE is simpler, HTTP-native, plays well with standard load balancers and
  auth (see §12 in AI Knowledge guide for the general trade-off).
- **Citations as a distinct event type**, not embedded as text in the
  answer — lets the client render them as structured, clickable references
  rather than parsing them out of prose.
- **Conversation as a first-class resource** (not stuffing history into
  every request body) — keeps the API RESTful and lets the server own
  context-window management (truncation/summarization of long histories)
  rather than pushing that complexity to every client.
- **Separate admin/ingestion API surface** with separate authz — content
  management is a distinct privilege level from asking questions.

---

## 4. High-Level Design

Two independent pipelines sharing a data layer: **ingestion** (async,
write path) and **query** (sync/streaming, read path). Draw this
distinction explicitly — it's the single most important architectural
decision, and interviewers look for it.

```
                              ┌───────────────────────────────────────────┐
                              │              INGESTION PIPELINE            │
                              │              (async, event-driven)         │
                              └───────────────────────────────────────────┘

 Confluence/Slack/PDF ──► Source Connectors ──► Kafka topic: "doc-events" ──► Ingestion Workers
  (webhook / poll)          (CDC-ish)             (created/updated/deleted)     │
                                                                                  ▼
                                                                    Parse → Chunk → Enrich (ACL, metadata)
                                                                                  │
                                                                                  ▼
                                                                    Embed (batched calls to embedding model)
                                                                                  │
                                                                  ┌───────────────┴───────────────┐
                                                                  ▼                                ▼
                                                          Vector DB (upsert)              Metadata Store (Postgres)
                                                          (pgvector / Pinecone)             (doc registry, ACLs,
                                                                                              ingestion status)

                              ┌───────────────────────────────────────────┐
                              │                QUERY PIPELINE               │
                              │              (sync, streaming response)     │
                              └───────────────────────────────────────────┘

 User ──► API Gateway/Auth ──► Assistant Service
                                     │
                                     ├─► Conversation Store (Redis/Postgres) — load recent turns
                                     │
                                     ├─► Query Embedding (embedding model)
                                     │
                                     ├─► Vector Search (top-K) + ACL filter (user's group/tenant claims)
                                     │         │
                                     │         ▼
                                     │   Reranker (cross-encoder, top-K → top-N)
                                     │
                                     ├─► Prompt Assembly (system + citations-instructions + context + history + query)
                                     │
                                     ├─► LLM Call (streamed) ──► SSE back to user
                                     │
                                     └─► Persist message + citations + token usage (async, off critical path)
```

**Component responsibilities:**

| Component | Responsibility |
|---|---|
| Source connectors | Detect create/update/delete in source systems (webhook where available, poll otherwise); publish a normalized event |
| Kafka `doc-events` topic | Decouples connectors from ingestion workers; buffers bursts (e.g., bulk Confluence import); enables replay |
| Ingestion workers | Parse (Tika/PDF libs), chunk, tag with ACL metadata, call embedding model, upsert to vector DB — horizontally scalable, idempotent consumers |
| Metadata store | Source of truth for document registry, ACLs, ingestion status — vector DB is a derived index, not the source of truth |
| Assistant service | Orchestrates the query pipeline: auth, retrieval, reranking, prompt assembly, streamed generation |
| Vector DB | ANN search filtered by ACL/tenant metadata (§3 in AI Knowledge guide) |
| Conversation store | Recent turn history for multi-turn context; short TTL working set (Redis) with durable backing (Postgres) |

---

## 5. Database

Three distinct data stores, each doing the job it's actually good at —
don't try to force everything into one.

### 5.1 Metadata store (Postgres) — source of truth

```sql
documents (
  id UUID PRIMARY KEY,
  source_system   TEXT,        -- 'confluence', 'slack', 'pdf-upload'
  source_id       TEXT,        -- external ID, for idempotent upserts
  title           TEXT,
  acl_groups      TEXT[],      -- which groups/roles can access this doc
  content_hash    TEXT,        -- detect no-op updates, skip re-embedding
  status          TEXT,        -- 'pending' | 'indexed' | 'failed'
  updated_at      TIMESTAMPTZ,
  UNIQUE (source_system, source_id)
)

chunks (
  id UUID PRIMARY KEY,
  document_id UUID REFERENCES documents(id),
  chunk_index INT,
  text        TEXT,
  vector_id   TEXT             -- pointer into vector DB, if stored separately
)

conversations (
  id UUID PRIMARY KEY,
  user_id UUID,
  created_at TIMESTAMPTZ
)

messages (
  id UUID PRIMARY KEY,
  conversation_id UUID REFERENCES conversations(id),
  role TEXT,                   -- 'user' | 'assistant'
  content TEXT,
  citations JSONB,             -- [{chunkId, source, url}]
  tokens_used INT,
  prompt_version TEXT,         -- for auditability / eval
  created_at TIMESTAMPTZ
)
```

- **Why Postgres for this**: relational integrity for ACLs and ingestion
  status, transactional guarantees for the document registry, and it's
  the system everything else reconciles against if the vector index drifts.
- **`content_hash`** avoids re-embedding unchanged documents on every
  ingestion event — cheap, high-value optimization.

### 5.2 Vector store (pgvector or a dedicated vector DB)

- One row per **chunk**, not per document — `(chunk_id, embedding,
  document_id, acl_groups, source, updated_at)`.
- **ACL metadata denormalized onto the vector record** so ACL filtering
  happens *inside* the ANN query (pre-filter), not as a post-query
  application-layer filter — critical for both correctness (never return
  top-K, then discover half are unauthorized and now you're under-filled)
  and performance.
- Choice: **pgvector if already on Postgres and scale is moderate**
  (<10M chunks) — keeps ACL joins and vector search in one transactional
  system, operationally simpler. **Dedicated vector DB (Pinecone/Milvus/
  Weaviate)** if scale or query performance demands it. For this
  scenario's stated scale (10K–100K documents ≈ low millions of chunks),
  pgvector is a defensible default — say so, and say what would push you
  off it.

### 5.3 Conversation/session store (Redis, backed by Postgres)

- Hot path: last N turns of a conversation, read on every query — Redis,
  keyed by `conversationId`, short TTL with sliding expiry.
- Durable copy in Postgres `messages` table for history, audit, and
  analytics — Redis is a cache in front of it, not the source of truth.

---

## 6. Caching

Layered, matching §11 of the AI Knowledge guide, applied to this specific
system:

| Layer | What's cached | Why |
|---|---|---|
| **Embedding cache** | Query embedding for identical/near-identical queries | Skip redundant embedding-model calls for common questions ("what's the PTO policy") |
| **Retrieval cache** | Top-K chunk IDs for a given query embedding cluster | Retrieval is deterministic given the same index state — cache with invalidation tied to index updates |
| **Semantic answer cache** | Full answer for semantically similar queries, threshold-gated | Biggest cost/latency win for FAQ-shaped traffic; **excluded for anything ACL-sensitive across users** — cache key must include the requesting user's ACL scope, or better, only semantic-cache content that's universally accessible to avoid cross-user leakage |
| **Provider prompt caching** | Static system prompt + few-shot examples prefix | Every query pays for the same boilerplate prefix — provider-side caching (Anthropic/OpenAI) cuts this reprocessing cost/latency |
| **Conversation history** | Redis, per-conversation | Avoid a Postgres round-trip on every turn of an active conversation |

**Critical correctness note to state out loud in the interview:** a
semantic cache keyed only on query similarity is a **security bug** in a
multi-tenant/ACL system if it ignores who's asking — two users with
different permissions asking similarly-worded questions must not share a
cache entry unless the underlying content is accessible to both. Scope
cache keys by ACL group, not just by query.

**Invalidation:** cache entries tied to a document are invalidated by the
same `doc-events` Kafka topic that drives re-ingestion (§7) — a document
update publishes an event, a cache-invalidation consumer evicts affected
retrieval/answer cache entries. TTL (e.g., 1 hour) is the backstop for
anything missed, not the primary mechanism.

---

## 7. Messaging

Kafka is the backbone of the **ingestion pipeline** — this is the natural
place to bring in event-driven architecture and tie back to the Kafka
checklist topic.

- **Topic: `doc-events`** — key by `document_id` so all events for the
  same document land on the same partition and are processed **in
  order** (an update must never be processed before the create it depends
  on, and a delete must be processed after all prior updates — ordering
  matters here, unlike many event streams).
- **Producers**: source connectors (webhook receivers, poll-and-diff jobs).
- **Consumers**: ingestion worker consumer group — parse → chunk → embed →
  upsert, horizontally scaled by partition count.
- **Idempotency**: consumers upsert keyed by `(source_system, source_id)`
  with a `content_hash` check — replaying the same event (at-least-once
  delivery) is a no-op if content hasn't changed. This is the standard
  Kafka consumer idempotency pattern (see the companion Kafka guide).
- **DLQ**: a document that repeatedly fails parsing/embedding (corrupt
  PDF, embedding API error) goes to a dead-letter topic after N retries,
  surfaced on an admin dashboard rather than blocking the partition or
  silently dropping.
- **Why not synchronous ingestion** (parse+embed inline on webhook
  receipt)? Embedding calls are slow and can fail independently of the
  source webhook; decoupling via Kafka means a slow/failing embedding
  provider doesn't back up or drop webhook deliveries, and lets you
  replay/reprocess the entire corpus (e.g., after an embedding model
  upgrade — see the re-embedding migration discussion in the AI
  Knowledge guide §2) by replaying the topic from an offset.
- **Second topic, optional but worth mentioning: `cache-invalidation`** —
  or reuse `doc-events` with a dedicated consumer group — so cache
  eviction (§6) and index updates both react to the same event, staying
  consistent with each other without direct coupling.

The **query path is not Kafka-mediated** — it's synchronous
request/response (streamed) because the user is waiting live. Messaging
belongs on the write/ingestion side, and it's worth stating that
distinction explicitly rather than reaching for Kafka everywhere out of
habit.

---

## 8. Scaling

| Bottleneck | Scaling approach |
|---|---|
| **Assistant service (query orchestration)** | Stateless, horizontally scaled behind a load balancer — conversation state lives in Redis/Postgres, not in-process, so any instance can serve any request |
| **Vector search** | Read replicas / sharded vector index for pgvector at scale; purpose-built vector DBs handle this natively. Shard by tenant if multi-tenant scale demands it |
| **Ingestion workers** | Scale consumer instances with Kafka partition count on `doc-events`; embedding-model calls are the likely throughput ceiling — batch requests to the embedding API rather than one call per chunk |
| **LLM provider calls** | The real bottleneck at scale — rate limits and cost, not compute you control. Mitigate with request queuing/backpressure, multiple provider accounts or a fallback provider, and aggressive caching (§6) to reduce call volume |
| **Reranker** | Cross-encoder rerank is CPU/GPU-bound if self-hosted — batch and bound top-K fed into it (rerank 50, not 500) |
| **Hot conversations** | Redis handles this natively; for very high fan-out (e.g., a company-wide announcement triggering a spike of similar questions), the semantic cache (§6) absorbs repeat load rather than hitting the LLM per request |

**Multi-tenant/multi-region consideration** (mention if the interviewer
pushes): partition vector index and metadata store by tenant/region for
data residency, and keep the assistant service stateless so it can be
deployed close to users — the LLM provider call is usually the fixed
latency floor regardless of your own region placement, so don't
over-invest in multi-region compute before checking that it actually
moves the needle.

---

## 9. Failure Handling

Walk through each dependency and its failure mode — this is what
separates a senior answer from a superficial one.

| Failure | Handling |
|---|---|
| **LLM provider down/slow** | Timeout with a sane bound (e.g., 10s), circuit breaker (resilience4j) opens after repeated failures, fallback to a secondary provider if configured, otherwise a clear "assistant temporarily unavailable" — never hang the request indefinitely |
| **Vector DB unavailable** | Circuit breaker; degrade to keyword-only search if a hybrid setup exists, or fail the request with a clear error — do **not** silently fall back to answering from parametric memory with no retrieval, since that reintroduces unguarded hallucination and bypasses ACL filtering entirely |
| **Retrieval returns nothing above similarity threshold** | Not a failure — explicit product behavior: respond "I couldn't find relevant information," don't call the LLM to guess (see Hallucination Mitigation in the AI Knowledge guide) |
| **Embedding call fails during ingestion** | Retry with backoff at the consumer level; after N attempts, DLQ (§7) — document stays in `pending`/`failed` status, visible on an admin dashboard, doesn't silently vanish |
| **Partial stream failure (LLM disconnects mid-generation)** | Explicit `error` SSE event (see §12 in the AI Knowledge guide) rather than an ambiguous silent close; client shows partial answer marked incomplete, offers retry |
| **ACL data stale/inconsistent** | This is the one place to be conservative, not available — if ACL lookup fails or is ambiguous, **fail closed** (deny/omit), never fail open. State this trade-off explicitly: availability loses to correctness for authorization |
| **Kafka consumer lag spikes (ingestion backlog)** | Freshness NFR degrades gracefully (documents take longer to become searchable) rather than the query path being affected at all — this is exactly why ingestion and query are decoupled pipelines |
| **Duplicate events / at-least-once delivery** | Idempotent upserts keyed by `(source_system, source_id)` + `content_hash`, as in §7 |

---

## 10. Monitoring

Split into the two categories interviewers expect: **systems metrics**
(the ones you'd track for any service) and **AI-specific quality
metrics** (the ones that show you understand this isn't just a normal
CRUD service).

**Systems / operational:**
- Latency: TTFT, full-response time, p50/p95/p99, broken down by pipeline
  stage (retrieval, rerank, generation) — so a regression is
  diagnosable, not just visible.
- Error rates per dependency (LLM provider, vector DB, embedding API).
- Kafka consumer lag on `doc-events` (freshness SLO proxy).
- Cost: tokens consumed per request/day, cost per conversation — alert on
  budget thresholds.
- Cache hit rates (exact, semantic, provider-prompt) — validates the
  caching investment is paying off.

**AI/quality-specific:**
- **Faithfulness / groundedness score** — sampled evaluation of whether
  answers are actually supported by retrieved context (RAGAS-style or
  LLM-as-judge), tracked as a trend, alerting on regression after any
  prompt/model/retrieval change.
- **Retrieval recall@k** against a maintained golden Q/A eval set — gates
  deploys that touch chunking, embedding model, or the vector index.
- **"No answer found" rate** — a rising trend can mean either genuinely
  missing content (an ingestion gap) or a broken retrieval threshold —
  worth its own dashboard.
- **User feedback signal** (thumbs up/down from §3's API) — the real
  ground truth, correlated against the above proxy metrics.
- **Full request tracing**: for any answer, the retrieved chunks, prompt
  version, and model response should be reconstructable — this is what
  makes hallucination/wrong-answer debugging (§9 in the AI Knowledge
  guide) possible at all instead of guesswork.

**Alerting philosophy to state:** treat faithfulness/recall regressions
with the same severity as a latency or error-rate regression — a system
that's fast and available but confidently wrong has *failed*, even though
every traditional health check is green. This is the single point that
most differentiates an AI-system design answer from a generic backend
answer, and worth saying explicitly.

---

## 11. Capacity Estimation

Interviewers often want to see you do rough math out loud. Numbers here
are illustrative — the method matters more than precision.

**Traffic:**
- 1,000 employees, 20% daily active → 200 DAU
- ~3 questions/user/day → 600 queries/day
- Peak hour ≈ 20% of daily traffic → ~120 queries/hour ≈ 0.03 QPS average,
  bursty to maybe 1–2 QPS at peak (all-hands, policy announcement) —
  this is a low-QPS system; the design challenge is quality/cost, not
  raw throughput.

**Token cost (rough):**
- Average prompt: system prompt (~300 tokens) + 5 reranked chunks
  (~400 tokens each = 2,000) + conversation history (~500 tokens) +
  query (~50 tokens) ≈ **2,850 input tokens**
- Average output: ~300 tokens
- Per query ≈ 3,150 tokens. At 600 queries/day ≈ ~1.9M tokens/day.
- At illustrative pricing (~$3/M input, $15/M output tokens): 
  input ≈ 600 × 2,850 × $3/1M ≈ **$5.13/day**; 
  output ≈ 600 × 300 × $15/1M ≈ **$2.70/day** 
  → roughly **$8/day, ~$240/month** — cheap enough that this is not the
  binding constraint at this scale, but the calculation is what
  matters: you'd redo this math with real traffic numbers before
  committing to an architecture, and revisit if the org scales 100x.

**Storage:**
- 50,000 documents × ~10 chunks/doc average = 500,000 chunks.
- Embedding dimension 1536 × 4 bytes (float32) ≈ 6KB/vector →
  500,000 × 6KB ≈ **3GB** of raw vector data — trivially fits in a
  single pgvector instance; reinforces the earlier call that a dedicated
  vector DB isn't justified at this scale.

**The point of walking through this:** it justifies architectural
choices (pgvector over a dedicated vector DB, no need for aggressive
sharding) with numbers instead of assertion — exactly the muscle an
interviewer is checking for.

---

## 12. Interview Q&A

**Q: Walk me through what happens end-to-end when a user asks a question.**
A: Request hits the assistant service, authenticated and carrying the
user's ACL claims. It loads recent conversation turns from Redis for
context, embeds the query, runs a vector search against the chunk index
pre-filtered by the user's ACL groups, reranks the top candidates with a
cross-encoder, assembles a prompt (system instructions + reranked chunks
with source metadata + conversation history + the query), and streams the
LLM's response back over SSE, emitting citation events alongside the
answer tokens. After the stream completes, the message and citations are
persisted asynchronously — off the critical path, so persistence latency
never adds to what the user waits for.

**Q: How do you make sure the assistant never leaks information a user isn't authorized to see?**
A: ACL enforcement happens at retrieval, not at generation — the vector
search itself is pre-filtered by the requesting user's ACL groups, so
unauthorized chunks are never fetched, never enter the prompt, and
therefore cannot appear in or influence the answer. I'd never rely on
instructing the model "don't mention X" as the security boundary — that's
a prompt-injection-fragile approach; the enforcement has to happen in
deterministic retrieval-layer code. I'd also fail closed on any ACL
lookup ambiguity or failure — deny by default, never default-allow (§9).

**Q: How do you keep the assistant's knowledge fresh as source documents change?**
A: Source connectors publish change events (webhook where the source
supports it, poll-and-diff otherwise) to a Kafka topic. Ingestion workers
consume, re-parse, re-chunk, and re-embed only what changed (a
content-hash check skips no-op updates), then upsert into the vector
index and invalidate any cached answers tied to that document. This keeps
freshness to minutes rather than requiring a full nightly re-index, and
because it's event-driven and decoupled via Kafka, an ingestion backlog
degrades freshness gracefully without ever affecting the live query path.

**Q: Your reviewer says the assistant is "too slow." How do you investigate?**
A: Break the p95 down by pipeline stage — retrieval, rerank, prompt
assembly, generation — using the tracing described in §10, rather than
guessing. In most RAG systems generation dominates, so I'd check
streaming is actually working end-to-end (a buffering proxy in front of
the SSE endpoint is a common silent culprit), verify `max_tokens` and
reranker candidate count aren't unnecessarily large, and check whether a
smaller/faster model is viable for this workload. I'd fix the largest
measured contributor first and re-measure rather than optimizing
speculatively.

**Q: How would this design change for a customer-support bot instead of an internal docs assistant?**
A: The skeleton is identical — ingestion/query split, RAG, streaming,
citations — but a few things shift: ACL becomes tenant isolation rather
than internal-group permissions (see the multi-tenant vector DB discussion
in the AI Knowledge guide §3); availability/latency NFRs get stricter
since it's customer-facing and revenue-adjacent; you'd likely add an
escalation path (hand off to a human agent) as a first-class flow rather
than just "I don't know"; and the feedback loop (§3 API, §10 monitoring)
matters even more since customer-facing wrong answers have higher
real-world cost, which would push me toward adding the verification pass
mentioned in the Hallucination Mitigation section for at least the
higher-risk answer categories (billing, cancellations).

**Q: Where would you cut scope if you had to ship an MVP in two weeks?**
A: Keep RAG + citations + ACL filtering — those are non-negotiable for
trust and correctness. Cut: multi-turn conversation memory (ship
single-turn Q&A first, it's most of the value with far less complexity),
semantic caching (start with exact-match only), and real-time ingestion
(nightly batch re-index is fine for an MVP, upgrade to event-driven Kafka
ingestion once freshness complaints justify the investment). I'd state
that explicitly as a sequencing decision, not a scope cut driven by not
knowing how to build it — showing you can phase complexity is itself part
of what's being evaluated.

---

*Next up (on request): Kafka, or Distributed Systems.*
