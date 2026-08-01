# 14 – Interview Questions

## Executive Summary

A consolidated, organized question bank pulling the highest-signal
questions from every chapter, plus a rapid-fire drilling section and a
set of full whiteboard system-design prompts for mock-interview
practice. Use this chapter for spaced-repetition review in the days
before an interview — each question links back to the chapter with the
full model answer.

## How to Use This Chapter

1. First pass: read a question, answer it out loud unaided, then check
   the linked chapter — note gaps.
2. Second pass: the Rapid-Fire Round below, timed, one-line answers only
   — this trains recall speed, not depth.
3. Day-before-interview: the Whiteboard Prompts section, full mock runs,
   using the Chapter 09 narration structure.

## By Category

### Fundamentals & Architecture (Ch 01–02)
- Why does RAG exist instead of just fine-tuning or relying on a
  larger context window?
- Walk through the high-level RAG architecture, component by component.
- Why might the retriever matter more to answer quality than the choice
  of LLM?

### Ingestion & Chunking (Ch 03–04)
- How do you keep an ingestion pipeline idempotent under at-least-once
  delivery?
- How do you decide chunk size, and how would you validate the choice
  empirically rather than by convention?
- Explain parent-document/hierarchical chunking and when it's worth the
  added complexity over a single-tier strategy.
- What breaks if you change chunking strategy on a live, already-indexed
  system?

### Embeddings & Vector Search (Ch 05–06)
- What does an embedding vector represent, and what does it explicitly
  not capture?
- Walk through migrating a production system from one embedding model
  to another without downtime.
- Explain how HNSW achieves sub-linear search and what `ef_search`
  trades off.
- Why must ACL filtering happen inside the vector search rather than as
  a post-filter?
- Design a hybrid search system — how do you merge keyword and vector
  result lists?
- Bi-encoder vs. cross-encoder — why can't you use a cross-encoder for
  the initial retrieval pass?

### Prompt & Generation (Ch 07–08)
- How do you structure a RAG prompt, and why does component ordering
  matter?
- How do you defend against a retrieved document containing a prompt
  injection attempt?
- Why is low temperature the right default for RAG generation?
- Design the SSE event protocol for a streaming RAG endpoint.
- How do you reconcile streaming with a requirement for structured JSON
  output?
- Why should citation metadata be resolved server-side, never trusted
  from model output?

### End-to-End & Spring AI (Ch 09–10)
- Walk through the full flow from a document being edited to a user
  receiving an answer reflecting that edit.
- At which exact point is access control enforced, and why there
  specifically?
- How does Spring AI let you swap LLM providers without touching
  business logic?
- How do you enforce per-request ACL filtering with a shared, singleton
  `ChatClient` bean serving concurrent users?
- Design a custom Spring AI `Advisor` for a cross-cutting concern not
  built in (e.g., cost tracking).

### Production, Performance & Security (Ch 11–13)
- Why should ingestion workers and the query-serving assistant service
  be separate, independently-scaled deployments?
- What breaks if a load balancer buffers responses on a streaming
  endpoint?
- Walk through a per-stage latency budget for a streamed RAG response.
- Design the cache key for a semantic answer cache in a multi-tenant
  system — what happens if you get it wrong?
- What's the difference between direct and indirect prompt injection?
- Map this pipeline against the OWASP Top 10 for LLM Applications.

## Rapid-Fire Round

One-line answers, timed — aim for under 20 seconds each.

| Question | One-line answer |
|---|---|
| Why RAG over fine-tuning for factual knowledge? | RAG updates knowledge without retraining; fine-tuning changes behavior, not facts, and goes stale |
| Cosine similarity vs. dot product? | Equivalent if vectors are normalized; dot product is cheaper to compute at query time |
| Pre-filter vs. post-filter ACL? | Pre-filter — unauthorized vectors must never be candidates, not fetched-then-discarded |
| HNSW's main tuning knob at query time? | `ef_search` — trades recall for latency |
| Why rerank after vector search? | Cross-encoders are far more accurate than bi-encoders but too slow to run over the full corpus |
| Biggest lever against hallucination? | Grounding via RAG + explicit "don't know" permission + similarity threshold gate |
| Why stream responses? | Reduces perceived latency (TTFT), not total latency |
| What does the outbox pattern solve? | The dual-write problem — atomic DB write + reliable event publish |
| At-least-once delivery implies what for consumers? | They must be idempotent |
| Why keep ingestion and query as separate Kafka-mediated vs. synchronous paths? | Query needs a live answer now; ingestion can tolerate async, eventual freshness |
| Biggest RAG-specific OWASP risk? | LLM01 Prompt Injection, specifically the indirect variant via retrieved content |
| Why is a semantic cache key required to include ACL scope? | Otherwise two users with different permissions can share a cached answer — a data leak |
| What's model tiering? | Route cheap/simple sub-tasks to a smaller model, reserve the large model for final synthesis |
| Why version and PR-review prompts? | A prompt is a behavioral contract — treat changes like a code change, gated on an eval set |

## Whiteboard System-Design Prompts

Full mock-interview prompts. Practice each in 35–40 minutes using the
FR → NFR → API → HLD → DB → Caching → Messaging → Scaling → Failure →
Monitoring framework, narrating per Chapter 09's structure.

**1. "Design an AI assistant that answers questions over internal
company documentation, with citations and per-user access control."**
*(The scenario used throughout this handbook — see the companion
`AI_RAG_Assistant_System_Design_Guide.md` at the repo root for a full
worked answer.)*

**2. "Design a customer-support RAG bot for an e-commerce company that
can also look up and modify order status."**
Hints: this adds the AI Agent Architecture pattern (AI Knowledge guide)
on top of RAG — tool-calling with least-privilege scoping (Ch 10, Ch 13),
human-confirmation or hard business-rule guards for irreversible actions,
and an escalation-to-human path as a first-class flow, not just "I don't
know."

**3. "Design a codebase-assistant RAG system that answers questions
about a large monorepo."**
Hints: syntax-aware chunking (Ch 04), embedding model choice tuned for
code, incremental re-ingestion on every merge (near-real-time freshness
requirement, tighter than a wiki), and access control scoped to
repository/branch permissions rather than document ACLs.

**4. "A RAG system is live in production and users report the assistant
sometimes gives wrong, confidently-stated answers. Diagnose and fix."**
Hints: this is a debugging narrative, not a fresh design — walk the
Chapter 09 cross-reference table (idempotency, chunking/retrieval,
grounding, citation trust) to localize the failure, propose the eval-set
regression-test discipline from Chapters 04/06/13 as the long-term fix.

**5. "Your RAG system's embedding provider is deprecating the model
you're using in 90 days. Plan the migration."**
Hints: this is Chapter 05's re-embedding migration section, applied
under a deadline — parallel collection, eval-gated cutover, rollback
plan, and how you'd communicate/schedule this against the 90-day window.

## Principal Engineer Notes

Interviewers at this level are listening for *why*, not just *what* — an
answer that states the mechanism and immediately names the trade-off or
failure mode it protects against (the pattern used throughout this
handbook) reads as significantly more senior than a correct but flat
definition. Practice answering in that shape specifically, not just
practicing the content.

## Next Chapter

[15 – Cheat Sheet](15-Cheat-Sheet.md)
