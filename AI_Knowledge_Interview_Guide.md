# AI Knowledge — Interview Deep Dive

> Companion guide to `Engineering_Leadership_Interview_Preparation_Guide.md`.
> Covers the **AI Knowledge** checklist in full: LLM basics, embeddings, vector
> databases, RAG, prompt engineering, semantic search, Spring AI, AI agent
> architecture, hallucination mitigation, latency optimization, caching, and
> streaming responses.
>
> Framing: you are a Java/Spring engineering leader being asked to reason
> about AI systems the way you already reason about distributed systems —
> latency budgets, failure modes, consistency, cost, and operability. Every
> section below leads with the concept, then the trade-offs, then the
> questions an interviewer is likely to ask, with model answers.

---

## Table of Contents

1. [LLM Basics](#1-llm-basics)
2. [Embeddings](#2-embeddings)
3. [Vector Databases](#3-vector-databases)
4. [RAG (Retrieval-Augmented Generation)](#4-rag-retrieval-augmented-generation)
5. [Prompt Engineering](#5-prompt-engineering)
6. [Semantic Search](#6-semantic-search)
7. [Spring AI](#7-spring-ai)
8. [AI Agent Architecture](#8-ai-agent-architecture)
9. [Hallucination Mitigation](#9-hallucination-mitigation)
10. [Latency Optimization](#10-latency-optimization)
11. [Caching](#11-caching)
12. [Streaming Responses](#12-streaming-responses)

---

## 1. LLM Basics

**What it is:** A Large Language Model is a transformer network trained to
predict the next token given prior tokens. Everything downstream —
chat, RAG, agents — is built on this single primitive: autoregressive
token prediction over a fixed context window.

**Concepts to actually know, not just name:**

- **Tokens, not words.** Cost, context limits, and latency are all measured
  in tokens (~4 chars/token for English). A 128K context window is a
  budget you manage like memory or connection pools.
- **Context window** is the model's entire working memory — no external
  state persists between calls unless *you* re-send it. This is the single
  most important mental model shift for backend engineers: LLM calls are
  **stateless**, like a pure function over the prompt.
- **Temperature / top-p** control sampling randomness. Low temperature
  (0–0.3) for deterministic tasks (extraction, classification, code);
  higher (0.7+) for creative/varied output.
- **Inference vs. training** — as an engineering leader you almost always
  operate at inference time. Training/fine-tuning is a separate, far more
  expensive lifecycle (data curation, GPU clusters, eval harnesses).
- **Context vs. parametric knowledge.** The model "knows" things baked into
  its weights (parametric) and things you feed it at request time
  (in-context). RAG exists because parametric knowledge goes stale and
  can't be audited or updated cheaply.

**Trade-offs to be ready to discuss:**

| Decision | Trade-off |
|---|---|
| Bigger model | Higher quality, higher latency + cost |
| Longer context | More grounding, quadratic-ish cost/latency growth, "lost in the middle" recall degradation |
| Fine-tuning vs. RAG vs. prompting | Fine-tuning = behavior/style change, expensive, goes stale; RAG = fresh facts, cheap to update, doesn't change behavior; prompting = fastest iteration, weakest guarantees |

### Interview Q&A

**Q: When would you fine-tune a model instead of using RAG?**
A: Fine-tune when you need to change *how* the model behaves — tone, output
format, domain-specific reasoning style, or compressing a long instruction
set into weights. Use RAG when you need the model to know *facts* that
change over time or must be auditable/traceable to a source. In practice
these are complementary, not exclusive: fine-tune for behavior, RAG for
knowledge. I'd default to RAG first because it's cheaper to iterate on and
doesn't require retraining every time the underlying data changes.

**Q: Why can't the model just "remember" previous conversations?**
A: Because inference is stateless — each call is a fresh forward pass over
whatever tokens are in the prompt. "Memory" is an application-layer concern:
you re-inject prior turns (short-term) or retrieved summaries/facts
(long-term) into the prompt on every call. This is exactly analogous to a
stateless microservice behind a load balancer — state lives in the client
or an external store, not the compute node.

**Q: How do you reason about cost for an LLM-backed feature?**
A: Cost ≈ (input tokens + output tokens) × price per token × request volume.
Input tokens dominate in RAG-heavy systems because you're stuffing retrieved
context into every call. I'd model it like any other per-request cost line:
p50/p95 token counts, multiply by traffic, and set a budget alert — same
discipline as tracking DB read units or egress cost.

---

## 2. Embeddings

**What it is:** A function that maps text (or image/audio) into a
fixed-length dense vector such that semantically similar inputs land close
together in vector space, measured by cosine similarity or dot product.

**Concepts to know:**

- Embeddings are produced by a separate, smaller model than your
  generation LLM (e.g., `text-embedding-3-small`, `bge-large`,
  `all-MiniLM-L6-v2`). Dimensionality is typically 384–3072.
- **Similarity metrics**: cosine similarity (most common, scale-invariant),
  dot product (faster, assumes normalized vectors), Euclidean distance.
- Embeddings are **domain-sensitive** — a general-purpose embedding model
  underperforms on legal/medical/code text vs. a domain-tuned one.
- **Chunking strategy directly determines embedding quality.** Embed a
  whole document and you get a diluted, unusable vector; embed a single
  sentence and you lose context. This is the single highest-leverage
  design decision in a RAG pipeline (see §4).

**Trade-offs:**

- Higher-dimensional embeddings → better recall, more storage, slower ANN
  search.
- Re-embedding cost: if you change embedding models, you must re-embed
  your entire corpus — this is a migration, not a config change, and
  should be planned like a schema migration.

### Interview Q&A

**Q: How do you decide chunk size when generating embeddings?**
A: Chunk to the smallest unit that's still self-contained and answers a
plausible question on its own — typically 200–500 tokens with 10–20%
overlap between chunks to avoid severing context at boundaries. I'd tune
this empirically against a retrieval eval set (does the right chunk get
retrieved for known Q/A pairs?) rather than picking a number up front. For
structured content (docs with headers, code), I'd chunk along semantic
boundaries — sections, functions — instead of a fixed token window.

**Q: What happens if you switch embedding models on a live system?**
A: Old and new vectors are not comparable — cosine similarity between
embeddings from two different models is meaningless. It has to be treated
as a full corpus migration: re-embed everything, write to a new
index/collection, validate retrieval quality against a golden set, then
cut over — ideally with a shadow/parallel-run period, same pattern as a
database migration with a dual-write phase.

**Q: Dot product vs. cosine similarity — when does it matter?**
A: Cosine similarity normalizes for vector magnitude, so it measures pure
direction/semantic similarity — safer default. Dot product is cheaper to
compute and equivalent to cosine *if* vectors are pre-normalized, which is
why high-throughput ANN indexes often normalize at write time and use dot
product at query time to save the runtime division.

---

## 3. Vector Databases

**What it is:** A datastore optimized for storing high-dimensional vectors
and answering approximate nearest neighbor (ANN) queries at scale —
"find the k most similar vectors to this query vector" in sub-linear time.

**Concepts to know:**

- **Why not brute force?** Exact k-NN is O(n) per query. At millions of
  vectors that's not viable at request latency, so vector DBs use ANN
  index structures: **HNSW** (graph-based, most common — Pinecone,
  Weaviate, Milvus, pgvector), **IVF** (cluster-then-search, used in FAISS),
  **product quantization** (compresses vectors for memory savings at some
  recall cost).
- **Recall vs. latency knob**: `ef_search` (HNSW) or `nprobe` (IVF) trade
  query latency for recall. This is the vector-DB equivalent of an index
  scan vs. seek trade-off.
- **Hybrid search**: combining vector similarity with keyword/BM25 and
  metadata filters (e.g., `tenant_id = X AND date > Y`) is standard in
  production — pure vector search alone under-performs on exact-match
  terms like product codes or names.
- **Options**: purpose-built (Pinecone, Milvus, Weaviate, Qdrant) vs.
  vector-capable extensions on existing stores (pgvector on Postgres,
  Redis Vector, OpenSearch/Elasticsearch kNN, MongoDB Atlas Vector Search).

**Trade-offs:**

| Choice | When |
|---|---|
| pgvector (Postgres extension) | Already on Postgres, moderate scale (<10M vectors), want transactional consistency with your relational data, avoid new infra |
| Purpose-built vector DB | Tens of millions+ vectors, need best-in-class ANN performance, multi-tenant filtering at scale, managed ops |
| Redis Vector | Already using Redis, need very low latency, smaller working sets |

### Interview Q&A

**Q: How would you design multi-tenant isolation in a vector database?**
A: Two options: (1) metadata filtering — store a `tenant_id` field on every
vector and filter at query time (simplest, but a noisy-neighbor risk if one
tenant's index gets huge); (2) separate namespaces/collections per tenant
(stronger isolation, more operational overhead at thousands of tenants). I'd
default to metadata filtering for most SaaS scale, and graduate to
namespace-per-tenant for large/enterprise customers with strict data
isolation requirements — the same pool-vs-silo decision you'd make for a
multi-tenant relational DB.

**Q: How do you keep a vector index consistent with the source-of-truth data store?**
A: Same problem as keeping a search index in sync with a primary DB —
CDC (change data capture) off the primary store, or an outbox pattern
publishing "document changed" events that a consumer re-embeds and
upserts. I would not do synchronous dual-writes (embedding calls are slow
and can fail independently), so eventual consistency with a bounded lag SLO
is the right model, with a reconciliation job as the backstop.

**Q: What causes poor recall in a vector search system, and how do you debug it?**
A: In order I'd check: (1) chunking — is retrieved context actually
relevant text, or noise; (2) embedding model mismatch between what indexed
the corpus vs. what embeds the query; (3) ANN recall knob set too
aggressively for latency (low `ef_search`/`nprobe`); (4) missing hybrid/
keyword fallback for exact-match queries; (5) stale index vs. source data.
I'd build a small labeled eval set (query → expected doc) and measure
recall@k as a regression gate, same as you'd unit-test a search ranking
change.

---

## 4. RAG (Retrieval-Augmented Generation)

**What it is:** Instead of relying on the model's parametric knowledge,
retrieve relevant context at query time and inject it into the prompt so
the model generates answers grounded in your actual data.

**Core pipeline:**

```
User Query
   │
   ▼
Query Embedding ──► Vector Search (+ metadata filter / hybrid keyword) ──► Top-K Chunks
                                                                              │
                                                                              ▼
                                        Rerank (optional, cross-encoder) ──► Context
                                                                              │
                                                                              ▼
                          Prompt Template (system + context + query) ──► LLM ──► Answer (+ citations)
```

**Concepts to know:**

- **Naive RAG vs. advanced RAG.** Naive: embed query → top-k → stuff into
  prompt. Advanced adds: query rewriting/expansion, hybrid search,
  reranking (cross-encoder models are much more accurate than bi-encoder
  similarity but too slow to run over the whole corpus — so you retrieve
  broad with vectors, then rerank a small top-N), multi-hop retrieval for
  questions that need chaining facts, and citation/source attribution.
- **Reranking**: retrieve top-50 cheaply via ANN, rerank to top-5 with a
  cross-encoder (e.g., Cohere rerank, `bge-reranker`) before sending to the
  LLM — meaningfully improves precision at a small latency cost.
- **Context window budgeting**: you're competing for space between system
  prompt, retrieved chunks, conversation history, and the user query — this
  is a real capacity-planning problem, not an afterthought.
- **Evaluation**: retrieval metrics (recall@k, MRR) are separate from
  generation metrics (faithfulness — is the answer grounded in the
  retrieved context; answer relevance). Frameworks: RAGAS, TruLens.

**Trade-offs:**

- More retrieved chunks → better recall, more prompt cost/latency, more
  noise diluting the model's attention (the "lost in the middle" effect —
  models attend less to content in the middle of a long context).
- Reranking → better precision, extra network hop and latency (~50–200ms).
- Query rewriting (LLM rewrites vague user query before retrieval) →
  better retrieval, extra LLM round-trip.

### Interview Q&A

**Q: Design a RAG system for internal company documentation search. Walk me through it.**
A: I'd frame it exactly like any search system design:
- **Functional**: ingest docs (Confluence, PDFs, wikis), answer natural-
  language questions with citations, support access control per document.
- **Ingestion pipeline**: doc → parse/clean → chunk (semantic boundaries,
  ~300 tokens, overlap) → embed → upsert to vector DB with metadata
  (source, ACL, last-updated) — triggered by webhook/CDC from the source
  system, not a nightly batch, so freshness stays bounded.
- **Query path**: user query → (optional) query rewrite → embed → hybrid
  vector+keyword search filtered by the requesting user's ACL →
  rerank top-50 to top-5 → build prompt with citations → stream response.
- **Non-functional**: p95 latency budget (retrieval ~100ms, rerank ~150ms,
  generation dominates at 1–3s — so I'd stream), access control enforced
  at the retrieval filter (never rely on the LLM to "not mention"
  something it was fed), and an eval harness (golden Q/A set) gating any
  change to chunking, embedding model, or prompt.
- **Failure handling**: if retrieval returns nothing above a similarity
  threshold, say so explicitly rather than letting the model guess —
  this is the primary hallucination lever.

**Q: Why does adding more retrieved chunks sometimes make answers worse?**
A: Two mechanisms: (1) "lost in the middle" — transformer attention is
empirically stronger for content near the start/end of the context window,
so relevant facts buried in chunk #30 get under-weighted; (2) noise
dilution — irrelevant chunks give the model more surface area to latch
onto tangential or contradictory information. The fix isn't "retrieve
more," it's "retrieve better" — tighter chunking, reranking, and a
relevance threshold that excludes low-similarity results rather than
always padding to top-k.

**Q: How do you handle a RAG answer that turns out to be wrong in production?**
A: Same instinct as a production incident: is it a retrieval failure (wrong
or missing context) or a generation failure (model ignored/misread correct
context)? I'd want tracing that logs the exact chunks retrieved per
request so this is diagnosable after the fact — without that, RAG failures
are undebuggable. Then I'd add the failing case to the eval set so it's a
regression test going forward, not just a one-off fix.

---

## 5. Prompt Engineering

**What it is:** Structuring the input to an LLM to reliably produce the
desired output — the "API contract" layer between your application and a
non-deterministic function.

**Concepts to know:**

- **Structure**: system prompt (role, constraints, output format) +
  context (RAG chunks, tool results) + few-shot examples (optional) + user
  query. Treat the system prompt like an API spec — versioned, tested,
  reviewed in PRs.
- **Techniques**: zero-shot, few-shot (2–5 examples materially improves
  format consistency), chain-of-thought ("think step by step" — improves
  reasoning tasks, costs output tokens), structured output (JSON mode /
  function-calling schemas — the most important technique for backend
  integration, because free-text output is not parseable reliably).
- **Prompt injection** is the OWASP-relevant risk: untrusted content
  (retrieved docs, user input) can contain instructions that hijack the
  model. Mitigation: clearly delimit untrusted content, never let retrieved
  text override system instructions, validate/sanitize tool outputs before
  re-feeding them to the model.
- **Prompts are code**: version them, test them against a golden set,
  review changes — a prompt tweak is a behavior change and should go
  through the same rigor as a code change, not be edited ad hoc in
  production.

### Interview Q&A

**Q: How do you make LLM output reliably parseable by downstream code?**
A: Use structured output / function-calling (JSON schema-constrained
generation) rather than asking for JSON in free text and hoping — most
model providers now support schema-constrained decoding, which
guarantees valid JSON matching your schema. As a defense-in-depth measure
I'd still validate the parsed object against the schema before using it,
and have a fallback (retry with a stricter reminder, or a default) for the
rare malformed response.

**Q: How do you protect a system from prompt injection via retrieved documents?**
A: Treat retrieved/external content as untrusted input, same as you would
user-supplied HTML or SQL. Concretely: wrap retrieved context in explicit
delimiters and instruct the system prompt that content inside those
delimiters is data, not instructions; never grant the model the ability to
take irreversible actions based purely on retrieved text without a
confirmation step; and if the system has tool-calling, scope each tool's
permissions to the minimum needed (least privilege), so even a successful
injection has a small blast radius.

**Q: How do you test and version prompts like code?**
A: Prompts live in source control, not hardcoded strings scattered through
the app — a dedicated prompt/template module. Changes go through PR review
and run against a golden eval set (a fixed set of inputs with expected
properties — not exact-match, since output is non-deterministic, but
scored on format validity, key-fact presence, or an LLM-as-judge rubric).
I'd gate deploys on that eval suite the same way I'd gate on unit tests.

---

## 6. Semantic Search

**What it is:** Search based on meaning/similarity rather than exact
keyword match — the retrieval half of RAG, but also a standalone feature
(e.g., "find similar tickets," product recommendation, duplicate
detection).

**Concepts to know:**

- Semantic search alone loses on exact-match precision (SKUs, error codes,
  proper nouns) — this is why **hybrid search** (vector + BM25/keyword,
  merged via reciprocal rank fusion or a weighted score) is the production
  default, not pure vector search.
- **Bi-encoder vs. cross-encoder**: bi-encoders embed query and documents
  independently (fast, enables pre-indexing, used for the initial
  retrieval pass); cross-encoders jointly encode query+document pairs
  (much more accurate, can't be pre-computed, used only for reranking a
  small candidate set).
- Semantic search quality depends on the same embedding/chunking
  fundamentals as RAG (§2), plus **query understanding** — short, vague
  user queries embed poorly compared to well-formed sentences, so query
  expansion/rewriting matters more here than in RAG where the LLM
  compensates downstream.

### Interview Q&A

**Q: Why would you combine keyword and vector search instead of just using vector search?**
A: Vector search captures meaning but can miss exact-match signals — a
user searching an error code or product SKU wants that literal string
matched, and embeddings can rate a semantically "close but wrong" result
higher than the exact match. Hybrid search runs both, merges results
(reciprocal rank fusion is a simple, effective default), and gets the
precision of keyword matching with the recall of semantic matching. Most
production search systems — Elasticsearch, OpenSearch, Weaviate — support
this natively now.

**Q: How would you evaluate a semantic search feature before shipping it?**
A: Build a labeled eval set of (query, relevant document) pairs from real
usage or support tickets, then measure recall@k and MRR (mean reciprocal
rank) against it — same rigor as evaluating a ranking algorithm change. I'd
also run an offline A/B against the current keyword-only search on a
sample of historical queries before exposing it live, and watch
click-through/zero-result rate once it ships.

---

## 7. Spring AI

**What it is:** Spring's abstraction layer for building AI-powered
applications in the Java/Spring ecosystem — the Spring Data/Spring Cloud
equivalent for LLM integration. Directly relevant given your Spring Boot
background.

**Concepts to know:**

- **`ChatClient`** — the core abstraction, a fluent API over any supported
  model provider (OpenAI, Azure OpenAI, Anthropic, Ollama, Bedrock,
  Vertex AI) — same "swap the implementation, keep the interface" pattern
  as `JdbcTemplate`/`RestTemplate`.
- **`VectorStore`** abstraction — pluggable backend (pgvector, Redis,
  Pinecone, Milvus, Elasticsearch) behind a common `similaritySearch()`
  API, matching Spring Data's repository pattern.
- **`Advisor` chain** — interceptor pattern (like servlet filters / AOP
  advice) for cross-cutting concerns on chat calls: `QuestionAnswerAdvisor`
  auto-injects RAG context, `MessageChatMemoryAdvisor` handles
  conversation history, custom advisors for logging/PII redaction/guardrails.
- **`@Tool`-annotated methods** — Spring AI's function-calling integration:
  annotate a Java method, the model can invoke it as a tool call, Spring
  handles the JSON schema generation and marshaling — directly analogous
  to how `@RequestMapping` maps HTTP to methods.
- **Structured output** — `.entity(MyRecord.class)` binds LLM JSON output
  directly to a Java record/POJO, using Jackson under the hood.
- **ETL pipeline for RAG ingestion** — `DocumentReader` → `Transformer`
  (chunking/splitting) → `Writer` (to a `VectorStore`) — a clean,
  Spring-idiomatic pipeline abstraction (`TokenTextSplitter`, etc.).
- Integrates naturally with Spring Boot's auto-configuration, Actuator
  (observability), and Spring Cloud (resilience patterns like retry/
  circuit breaker apply directly to model calls, which are just another
  outbound HTTP dependency).

### Interview Q&A

**Q: How would you integrate an LLM call into a Spring Boot service without coupling business logic to a specific provider?**
A: Use Spring AI's `ChatClient` abstraction — code against the interface,
configure the concrete provider (OpenAI, Bedrock, Azure) via
auto-configuration properties, same pattern as `JdbcTemplate` decoupling
you from a specific JDBC driver. If I needed multi-provider fallback
(primary provider down → secondary), I'd wrap that in a `ChatClient`
decorator or a resilience4j circuit breaker around the call, exactly like
any other outbound dependency.

**Q: How do you handle conversation memory in a Spring AI chat application?**
A: `MessageChatMemoryAdvisor` backed by a `ChatMemory` implementation —
in-memory for a single instance/dev, or a persisted store (JDBC, Redis)
for a stateless, horizontally-scaled service, since conversation state
can't live in-process if any request can hit any pod. This is the same
"externalize session state" pattern as moving HTTP sessions to Redis in a
load-balanced web app.

**Q: What does a Spring AI RAG ingestion pipeline look like end-to-end?**
A: `DocumentReader` (PDF, JSON, Tika for arbitrary formats) reads source
docs → `TokenTextSplitter` (a `DocumentTransformer`) chunks them with
configurable size/overlap → optionally a metadata-enrichment transformer
tags source/ACL/timestamp → `VectorStore.add()` embeds and writes to
pgvector/Redis/etc. At query time, `QuestionAnswerAdvisor` wraps the
`ChatClient` call, automatically running `similaritySearch()` against the
`VectorStore` and injecting results into the prompt before calling the
model — so the RAG pattern from §4 becomes a few lines of Spring
configuration rather than hand-rolled orchestration.

---

## 8. AI Agent Architecture

**What it is:** An LLM that doesn't just answer once, but **plans, calls
tools, observes results, and iterates** toward a goal — turning the model
from a pure function into something closer to a control loop.

**Core loop (ReAct-style):**

```
Goal ─► LLM reasons ─► decides: Tool call or Final answer?
                            │
                 ┌──────────┴──────────┐
                 ▼                     ▼
          Execute tool           Return answer
          (search, DB query,
           API call, code exec)
                 │
                 ▼
        Observation fed back
        into context ─────────► (loop until done or max iterations)
```

**Concepts to know:**

- **Tool/function calling** is the mechanism — the model outputs a
  structured call (`{"tool": "searchOrders", "args": {...}}`), your code
  executes it (the model never executes anything itself), and the result
  is fed back into context for the next reasoning step.
- **Single-agent vs. multi-agent**: a single agent with several tools vs.
  orchestrating multiple specialized agents (planner, retriever, executor)
  that hand off subtasks — multi-agent adds coordination complexity, only
  justified when the task genuinely decomposes into independent
  specialties (this maps directly onto the monolith-vs-microservices
  decision you already know how to make).
- **Guardrails are the load-bearing engineering work**: max iteration
  limits (prevent infinite loops), tool permission scoping (least
  privilege — a "read order status" tool should not also be able to
  issue refunds), human-in-the-loop confirmation for irreversible actions,
  timeouts per step, and full tracing of every tool call for auditability.
- **Determinism trade-off**: agents are powerful because they can handle
  open-ended tasks, but that's precisely why they're hard to test and
  reason about — treat agent output like output from an untrusted,
  probabilistic upstream service, not like a deterministic function call.

### Interview Q&A

**Q: Design an AI agent that can look up and modify customer orders. What are the risks and how do you mitigate them?**
A: I'd scope tools tightly and asymmetrically: read tools (`getOrder`,
`getOrderHistory`) can be invoked freely; write/irreversible tools
(`cancelOrder`, `issueRefund`) require either a confirmation step back to
the human or hard business-rule guards enforced in code — not in the
prompt — before execution (e.g., refund amount can't exceed order total,
regardless of what the model "decides"). Every tool call gets logged with
inputs/outputs for audit. I'd cap iteration count and add a timeout so a
confused agent can't loop indefinitely or run up cost. Fundamentally: the
LLM proposes, deterministic code disposes — I never let model output
directly trigger a side effect without a validation layer, same principle
as never trusting client input in a web API.

**Q: How is an AI agent different from a traditional workflow engine (e.g., a saga orchestrator)?**
A: A workflow engine executes a predefined, deterministic sequence of
steps — the control flow is fixed at design time. An agent's control flow
is decided at *runtime* by the model reasoning over the current state —
it can choose which tool to call next, retry differently, or take a path
you didn't explicitly program. That flexibility is the whole value
proposition for open-ended tasks, but it also means agents need the same
safety nets sagas get for free from being deterministic: compensating
actions, idempotency on every tool (the model may call the same tool
twice), and strict step timeouts, because you can't statically prove the
agent's path terminates or stays within bounds.

**Q: How do you keep agent costs and latency under control?**
A: Bound the loop explicitly (max steps), use a smaller/cheaper model for
routing/tool-selection and reserve the expensive model for the final
synthesis step, cache tool results within a session so the same lookup
isn't repeated, and stream intermediate progress to the user so perceived
latency stays low even if the total loop takes several seconds — same
instinct as showing a progress indicator during a long-running batch job
rather than a blank screen.

---

## 9. Hallucination Mitigation

**What it is:** Hallucination = the model generating fluent, confident,
but factually wrong or unsupported output. It's not a bug you patch — it's
an inherent property of next-token prediction that you engineer around at
the system level.

**Mitigation techniques (defense in depth):**

1. **Grounding via RAG** — the single biggest lever. Answers should be
   derived from retrieved context, not parametric memory.
2. **Explicit "I don't know" instructions** — system prompt explicitly
   permits refusal when retrieved context doesn't support an answer;
   models default to guessing unless told not to.
3. **Similarity threshold gating** — if the top retrieved chunk's
   similarity score is below a threshold, don't call the LLM with weak
   context at all — return "no relevant information found."
4. **Citations / source attribution** — force the model to cite which
   chunk supports each claim; makes hallucination visible and checkable
   rather than silently plausible, and lets users verify.
5. **Structured output constraints** — narrowing the output space (schema,
   enum of allowed values) reduces room for invented specifics.
6. **Self-consistency / verification pass** — a second LLM call (or the
   same model) checks the draft answer against the retrieved context
   before returning it — more latency/cost for higher-stakes use cases.
7. **Lower temperature** for factual/extraction tasks.
8. **Human-in-the-loop** for high-stakes outputs (financial, medical,
   legal) rather than fully automated.

**The engineering leadership framing:** hallucination isn't solved, it's
*managed to an acceptable risk level* for the use case — the same way you
don't eliminate all production incidents, you reduce blast radius and
improve detection. Calibrate mitigation investment to the cost of being
wrong.

### Interview Q&A

**Q: How do you reduce hallucinations in a customer-facing RAG assistant?**
A: Layered: ground every answer in retrieved context (never let the model
answer from parametric memory alone), gate on a similarity threshold so
weak retrieval returns "I don't have information on that" instead of a
guess, require inline citations so claims are traceable to a source chunk,
and keep temperature low for factual responses. For the highest-stakes
answers I'd add a verification pass — a second cheap model call checking
"is this answer actually supported by this context?" — before returning
to the user. None of these get hallucination to zero; the goal is making
failures rare, visible, and cheap to catch rather than eliminating an
inherent property of the model.

**Q: How would you measure hallucination rate in production?**
A: Faithfulness evaluation — for a sample of production responses (or an
offline eval set), check whether every claim in the answer is entailed by
the retrieved context, either via an LLM-as-judge rubric (RAGAS-style
faithfulness score) or human review for a sampled subset. I'd track this
as a first-class metric alongside latency and cost, with alerting if it
regresses after a prompt, model, or retrieval change — treated as a
quality SLO, not a one-time eval.

**Q: A user reports the assistant gave a confidently wrong answer. How do you debug it?**
A: Pull the trace for that request: what was retrieved, what was the
final prompt, what did the model output. Usually it's one of: retrieval
returned nothing relevant but no threshold gate caught it, the retrieved
context was itself stale/wrong (source data problem, not a model
problem), or the model had strong-enough context but still diverged
(genuine generation failure, rarer). Fix at the layer where it actually
broke, then add the case to the regression eval set — same postmortem
discipline as a production incident.

---

## 10. Latency Optimization

**What it is:** LLM calls are slow (hundreds of ms to several seconds) and
sit directly in the user-facing critical path — this is a first-class
performance problem, not an afterthought.

**Where the latency comes from:**

- **Time-to-first-token (TTFT)**: prompt processing before generation
  starts — grows with input context size.
- **Inter-token latency**: generation is sequential, one token at a time
  — grows with output length. Total latency ≈ TTFT + (output tokens ×
  per-token time).
- Retrieval, reranking, and tool calls in a RAG/agent pipeline are
  additional sequential hops before the model even starts generating.

**Optimization levers:**

1. **Streaming** (see §12) — doesn't reduce total latency, but reduces
   *perceived* latency to near-zero by showing the first token
   immediately instead of waiting for the full response.
2. **Smaller/faster models for simpler sub-tasks** — use a cheap, fast
   model for classification/routing/tool-selection, reserve the large
   model for the step that needs its full reasoning capability.
3. **Reduce input context** — tighter chunking, reranking to fewer but
   more relevant chunks, prompt compression — smaller input directly
   reduces TTFT.
4. **Parallelize independent steps** — e.g., run retrieval and a cheap
   query-classification call concurrently rather than sequentially.
5. **Prompt caching** (provider-level) — providers like Anthropic/OpenAI
   cache the processed representation of a repeated prompt prefix (e.g., a
   long system prompt or few-shot examples), so repeated calls skip
   reprocessing that prefix — large TTFT win for high-reuse prompts.
6. **Speculative/draft decoding** (provider/infra-level, good to know
   conceptually) — a small draft model proposes several tokens, the large
   model verifies them in parallel instead of generating serially.
7. **Set explicit `max_tokens`** — an unbounded generation is an unbounded
   latency tail, same as an unbounded DB query.
8. **Timeout + fallback** — treat the LLM call like any other external
   dependency: set a timeout, define a fallback/degraded path (cached
   answer, simpler heuristic, "try again" UX) rather than letting a slow
   provider stall the whole request.

### Interview Q&A

**Q: A RAG feature has a p95 latency of 6 seconds. How do you approach reducing it?**
A: First instrument to find where the time actually goes — retrieval,
reranking, prompt build, model TTFT, model generation — don't optimize
blind. In most RAG pipelines the model generation step dominates, so I'd
start there: stream the response so perceived latency drops immediately,
cap `max_tokens` to what's actually needed, and check if a smaller model
is sufficient for the task. On the retrieval side, make sure reranking
isn't over-fetching (reranking 200 candidates instead of 50 adds latency
for little precision gain), and parallelize any independent steps (e.g.,
query rewriting and a cache lookup) instead of running them sequentially.
I'd set a latency SLO and treat this like any other performance
regression — profile, fix the largest contributor, re-measure.

**Q: What's the difference between reducing latency and reducing perceived latency, and when do you use each?**
A: True latency reduction (smaller model, less context, fewer hops) lowers
total time-to-completion. Perceived latency reduction (streaming, showing
retrieved sources before the full answer, progressive UI) doesn't change
total time but changes how long it *feels*. For a chat-style UX, streaming
alone often solves the "feels slow" complaint even if total generation
time is unchanged — same principle as showing a skeleton screen while data
loads. I'd reach for streaming first since it's cheap to add, then
pursue true latency work if the absolute time is still a problem (e.g.,
for a synchronous API integration that can't stream).

---

## 11. Caching

**What it is:** LLM calls are expensive and slow enough that caching isn't
an optimization, it's often a requirement — but naive caching breaks
because LLM inputs/outputs are non-deterministic and free-text, unlike a
typical cache key.

**Caching layers in an AI system:**

1. **Exact-match response cache** — hash the full prompt (or
   normalized user query), cache the response. Works well for FAQ-style
   or highly repeated queries; near-useless for open-ended chat where
   inputs rarely repeat verbatim.
2. **Semantic cache** — instead of exact key match, embed the incoming
   query and check similarity against cached query embeddings; if
   similarity exceeds a threshold, serve the cached answer. Catches
   paraphrases ("What's your refund policy?" vs. "How do refunds work?")
   that exact-match caching misses. Trade-off: risk of serving a
   *slightly* wrong cached answer for a query that's similar-but-not-
   identical — needs a conservative similarity threshold, tuned like any
   precision/recall trade-off.
3. **Provider-side prompt caching** — caching the *processed* KV-cache
   state for a repeated prompt prefix (e.g., long system prompt, few-shot
   examples, or a large RAG context reused across a session) — reduces
   cost and TTFT on the provider side without changing correctness, since
   it's still generating fresh output, just skipping reprocessing of
   unchanged input.
4. **Embedding cache** — cache the embedding vector for a given text so
   repeated ingestion or repeated identical queries don't re-call the
   embedding model.
5. **Retrieval result cache** — cache vector search results for popular
   queries, separate from caching the final LLM answer — useful when the
   generation step varies (e.g., personalized phrasing) but retrieval is
   stable.

**Invalidation is the hard part**, as always: if underlying documents
change, cached answers referencing them go stale — tie cache invalidation
to the same event stream that drives vector index updates (§3), not a
blind TTL. A TTL is a reasonable backstop, not a substitute for
event-driven invalidation.

### Interview Q&A

**Q: How would you design caching for an AI chat assistant to reduce cost without serving stale or wrong answers?**
A: Layer it: exact-match cache for genuinely repeated queries (cheap,
zero risk), semantic cache with a conservative similarity threshold for
paraphrased-but-equivalent queries (tunable risk, needs monitoring for
false-positive cache hits), and provider-level prompt caching for the
static parts of every prompt (system prompt, few-shot examples) since
that's pure cost/latency win with no correctness risk. For invalidation, I
wouldn't rely on TTL alone — tie the semantic and retrieval caches to the
same change-event stream that updates the vector index, so a document
update invalidates related cache entries immediately; TTL is just the
backstop for anything that slips through.

**Q: What's the risk of a semantic cache, and how do you bound it?**
A: The risk is a false-positive hit — two queries are similar enough to
exceed your threshold but actually need different answers ("cancel my
subscription" vs. "downgrade my subscription"), and the user silently gets
the wrong cached response. I'd bound this with a high similarity threshold
tuned against a labeled set of near-duplicate vs. distinct query pairs,
scope the cache to lower-stakes query types first (FAQ, general info) and
exclude account-specific or transactional queries from semantic caching
entirely, and monitor cache-hit responses for user correction/complaint
signals as a feedback loop.

---

## 12. Streaming Responses

**What it is:** Returning model output token-by-token (or chunk-by-chunk)
as it's generated, instead of waiting for the complete response — the
single highest-leverage UX lever for LLM-backed features, because
generation is inherently slow and sequential.

**Concepts to know:**

- **Transport**: Server-Sent Events (SSE) is the standard for LLM
  streaming (simple, HTTP-native, one-directional, auto-reconnect
  support) — WebSockets are used when you need bidirectional
  communication (e.g., voice, live agent tool-call visibility) but are
  overkill for simple text streaming.
- **Backpressure**: the client must be able to consume as fast as tokens
  arrive; on the server side, streaming a response ties up a connection
  for the full generation duration — this affects connection pool sizing
  and load balancer idle-timeout configuration differently than typical
  short-lived request/response traffic.
- **Partial-output handling**: if you need structured output (JSON) *and*
  streaming, you either stream raw tokens and parse incrementally
  (fragile — the JSON isn't valid until complete) or stream at a coarser
  granularity (e.g., stream free-text reasoning, then emit the final
  structured object as one chunk at the end) — this is a real design
  tension to be ready to discuss.
- **Cancellation**: user navigates away or cancels — the request should
  propagate cancellation upstream to stop the (billed) generation, not
  just stop rendering client-side. Wasted generation after client
  disconnect is a real cost leak at scale.
- **Failure mid-stream**: unlike a request/response call that either
  succeeds or fails cleanly, a stream can fail *after* partial output has
  already been shown to the user — the client needs to handle a
  truncated/errored stream gracefully (retry, show partial + error
  indicator) rather than assuming atomicity.

### Interview Q&A

**Q: How would you design streaming for a chat API in a Spring Boot backend?**
A: Expose the endpoint as SSE — Spring WebFlux's `Flux<String>`/
`ServerSentEvent` maps naturally onto a streaming provider response (Spring
AI's `ChatClient.stream()` already returns a `Flux`). On the client, an
`EventSource`-based consumer renders tokens as they arrive. I'd make sure
the reverse proxy/load balancer in front of it (e.g., Nginx, ALB) has
buffering disabled and an idle timeout longer than typical generation
time, since a buffering proxy silently defeats streaming by holding the
full response before forwarding it — a classic case of the infra layer
undoing an application-layer design decision. I'd also wire cancellation:
if the client disconnects, the `Flux` subscription should cancel, which
should propagate to cancel the upstream provider call so we're not paying
for tokens nobody sees.

**Q: How do you stream a response that ultimately needs to be structured JSON for downstream consumption?**
A: Streaming and strict structured output are in tension, because partial
JSON isn't parseable. Two practical patterns: (1) stream a human-readable
"thinking"/narration channel for UX while generating a separate, final
structured object emitted whole at the end for the downstream consumer —
often the cleanest split; (2) if the structured object itself must stream
incrementally (e.g., a long list being built up), use a schema designed
for incremental parsing (JSON Lines — one complete JSON object per line —
rather than one large JSON document) so each line is independently valid
as it arrives. I'd pick based on whether the *consumer* is a human (favor
narration streaming) or a system (favor JSON Lines / event-based partial
objects).

**Q: What breaks if you don't handle mid-stream failures explicitly?**
A: The client can be left showing a truncated, incomplete answer with no
indication it's incomplete — worse than an outright error, because it
looks finished and could be silently wrong or misleading (e.g., a
half-written instruction or a cut-off safety caveat). I'd design the
stream protocol with an explicit terminal event (`done` / `error`) rather
than inferring completion from the connection closing, so the client can
distinguish "finished successfully," "provider error mid-stream," and
"connection dropped" and render each state differently — same discipline
as not treating a TCP FIN as proof a distributed transaction committed.

---

## Quick-Reference Summary Table

| Concept | One-line takeaway | Production lever |
|---|---|---|
| LLM basics | Stateless, token-metered inference | Model context/state externally, budget by tokens |
| Embeddings | Text → vector; chunking quality dominates | Tune chunk size/overlap against eval set |
| Vector DB | ANN search trades recall for latency | HNSW `ef_search`, hybrid + metadata filtering |
| RAG | Ground generation in retrieved facts | Rerank, threshold-gate, cite sources |
| Prompt engineering | Prompt = API contract, version it | Structured/schema output, PR-reviewed prompts |
| Semantic search | Meaning match, weak on exact terms | Hybrid (vector + keyword) by default |
| Spring AI | `ChatClient`/`VectorStore`/`Advisor` abstractions | Provider-agnostic code, Advisor chain for RAG/memory |
| AI agents | Runtime-decided tool-call loop | Least-privilege tools, iteration caps, human confirmation |
| Hallucination | Inherent, not eliminable | Layered mitigation, faithfulness eval as an SLO |
| Latency | TTFT + sequential token generation | Streaming, smaller sub-task models, prompt caching |
| Caching | Non-deterministic input breaks naive caching | Exact + semantic cache layers, event-driven invalidation |
| Streaming | SSE, perceived-latency lever | Cancellation propagation, explicit terminal events |

---

*Next up (on request): AI/RAG Assistant system design deep dive, Kafka, or
Distributed Systems.*
