# Kafka — Interview Deep Dive

> Companion guide to `Engineering_Leadership_Interview_Preparation_Guide.md`,
> `AI_Knowledge_Interview_Guide.md`, and
> `AI_RAG_Assistant_System_Design_Guide.md`. Covers the **Kafka** checklist
> in full: partitions, consumer groups, exactly-once semantics,
> rebalancing, DLQ, ordering, and schema registry.
>
> Framing: interviewers use Kafka questions to test whether you actually
> operated it in production, not whether you can define terms. Every
> section below leads with the mechanism, then the operational failure
> mode, then the questions an interviewer is likely to ask — including the
> "what happens when it breaks" follow-ups that separate textbook
> knowledge from hands-on depth. Where relevant, sections link back to the
> `doc-events` ingestion pipeline from the AI/RAG Assistant design as a
> running concrete example.

---

## Table of Contents

1. [Partitions](#1-partitions)
2. [Consumer Groups](#2-consumer-groups)
3. [Exactly-Once Semantics](#3-exactly-once-semantics)
4. [Rebalancing](#4-rebalancing)
5. [DLQ (Dead Letter Queue)](#5-dlq-dead-letter-queue)
6. [Ordering](#6-ordering)
7. [Schema Registry](#7-schema-registry)
8. [Quick-Reference Summary Table](#8-quick-reference-summary-table)
9. [Interview Q&A](#9-interview-qa)

---

## 1. Partitions

**What it is:** A topic is split into ordered, append-only partitions —
the unit of parallelism, ordering, and storage in Kafka. Each partition
is a physically separate log, replicated across brokers.

**Concepts to know:**

- **Partition = the unit of parallelism.** A topic with 12 partitions can
  have at most 12 consumers in a group actively consuming in parallel —
  extra consumers beyond partition count sit idle. This single fact
  drives most partition-count decisions.
- **Ordering is only guaranteed within a partition**, never across
  partitions of a topic. This is why the **partition key** matters more
  than almost any other design decision in a Kafka-based system — it
  determines what ordering guarantee you actually get (see §6).
- **Replication**: each partition has a **leader** (handles all
  reads/writes) and **followers** (replicate the leader's log). `acks`
  and `min.insync.replicas` control the durability/latency trade-off:
  `acks=all` + `min.insync.replicas=2` (with replication factor 3)
  survives one broker failure with no data loss; `acks=1` is faster but
  can lose data if the leader fails before followers replicate.
- **Partition count is hard to change later** — increasing partitions on
  a live topic **changes the key→partition mapping** for all future
  messages (the hash-to-partition function depends on partition count),
  which breaks ordering guarantees for existing keys unless the consumer
  logic tolerates it. Decreasing isn't supported at all (delete/recreate
  only). This is why partition count is a capacity-planning decision made
  up front, not a knob you casually turn.
- **Sizing heuristic**: partitions ≥ target throughput ÷ per-partition
  throughput, and ≥ max expected consumer parallelism — but over-
  partitioning has real costs too: more open file handles per broker,
  slower leader elections/rebalances, more end-to-end latency overhead.
  A common guideline is starting in the tens, not hundreds, of partitions
  per topic unless you have a measured reason.

### Interview Q&A

**Q: How do you decide how many partitions a topic needs?**
A: Start from the throughput and parallelism requirements: partitions
need to be at least the number of consumer instances you want processing
in parallel, and enough that per-partition throughput stays under what a
single consumer can sustain. But I'd deliberately over-provision
moderately rather than exactly match current need, because increasing
partition count later changes the key-to-partition hash mapping and
breaks per-key ordering for any consumer relying on it — it's not a safe
live operation the way scaling consumers is. I'd also weigh the operational
cost: more partitions means more replication traffic, slower controller
operations, and longer rebalances, so I wouldn't over-provision by 10x
"just in case" — I'd size to a reasonable growth horizon with real
numbers, not guess big.

**Q: What's the trade-off between `acks=1` and `acks=all`?**
A: `acks=1` — the producer gets an ack once the partition leader has
written the message, before followers replicate it. Faster, but if the
leader fails before replication completes, that message is lost even
though the producer thinks it succeeded. `acks=all` (with
`min.insync.replicas` set appropriately) waits for the message to be
replicated to the minimum in-sync replica set before acking — higher
latency, but survives a broker failure without data loss. For the
`doc-events` ingestion topic in the AI/RAG design, I'd use `acks=all` —
losing a document-change event silently means that document never gets
re-indexed, which is a correctness bug, not just a performance
degradation, so it's worth the latency cost.

**Q: A topic is a hot spot — one partition is getting far more traffic than others. What's going on and how do you fix it?**
A: Almost always a **key skew** problem — the partition key isn't
distributing evenly, often because a small number of key values dominate
traffic (e.g., one very active tenant, or a default/null key routing
everything to one partition). I'd check the key distribution first. Fixes
depend on the cause: if it's a genuinely hot single entity, consider
salting the key (append a bounded random suffix) to spread its traffic
across multiple partitions, accepting weaker per-entity ordering as the
trade-off; if it's a bug (unintended constant key), fix the key selection
logic. I would not just add more partitions — that doesn't fix skew, it
just redistributes idle capacity around the same hot partition.

---

## 2. Consumer Groups

**What it is:** A consumer group is a set of consumers that cooperatively
consume a topic, with Kafka guaranteeing each partition is owned by
exactly one consumer within the group at a time — this is how Kafka
achieves both parallelism (across partitions) and safe load distribution
(no two consumers in the same group double-process the same partition).

**Concepts to know:**

- **Group = independent consumption stream.** Multiple consumer groups
  can read the same topic independently, each tracking its own offsets —
  this is how you fan a single event stream out to multiple, unrelated
  downstream systems (e.g., `doc-events` consumed independently by the
  ingestion-worker group *and* a cache-invalidation group, as in the
  AI/RAG design §7).
- **Offset tracking**: consumers commit their position (offset) per
  partition, stored in Kafka's internal `__consumer_offsets` topic. On
  restart or rebalance, a consumer resumes from its last committed
  offset — this is the mechanism that makes Kafka consumption
  crash-recoverable.
- **Auto-commit vs. manual commit**: auto-commit (periodic, e.g., every
  5s) is simple but risks committing an offset for a message that hasn't
  actually finished processing (crash between commit and processing
  completion ⇒ message loss) or reprocessing already-handled messages
  (crash before commit ⇒ at-least-once redelivery). **Manual commit after
  successful processing** is the standard production pattern when
  correctness matters — commit only once you're certain the message's
  side effects are durable.
- **Scaling a group**: adding consumers up to the partition count
  increases parallelism; beyond that, extra consumers sit idle (§1) —
  this is the direct lever for horizontally scaling the ingestion workers
  in the AI/RAG design.
- **Static membership** (`group.instance.id`): avoids triggering a full
  rebalance on a brief consumer restart (e.g., a rolling deploy) by
  letting the consumer rejoin with the same identity instead of being
  treated as a new member — meaningfully reduces rebalance churn during
  deploys.

### Interview Q&A

**Q: What happens if a consumer in a group crashes mid-processing?**
A: If it hasn't committed the offset for the message it was working on,
that message gets redelivered — to another consumer in the group after
the session timeout triggers a rebalance, or to the same consumer on
restart. This is exactly why consumer logic needs to be **idempotent**
(§3) — at-least-once delivery is the Kafka default and crashes are a
normal way for redelivery to happen, not an edge case. In the AI/RAG
ingestion pipeline, this is why the upsert is keyed by
`(source_system, source_id)` with a content-hash check — reprocessing the
same document-changed event twice is a safe no-op.

**Q: How would you fan out a single Kafka topic to multiple independent downstream systems?**
A: Multiple consumer groups on the same topic — each group maintains its
own offsets independently, so one group being slow, down, or reprocessing
from an earlier offset has zero effect on the others. In the AI/RAG
design, `doc-events` has (at least) two consumer groups: the ingestion
workers that re-embed and update the vector index, and a separate
cache-invalidation consumer that evicts stale cache entries — they read
the identical event stream but progress independently. That's the
Kafka-native alternative to something like re-publishing the same event
to multiple queues.

**Q: Manual vs. auto-commit — when would you choose each?**
A: Auto-commit is fine for use cases where occasional message loss or
duplicate processing is genuinely tolerable — e.g., a metrics/analytics
pipeline where losing a sample or double-counting one occasionally
doesn't matter. For anything with real correctness requirements — the
ingestion pipeline, financial events, order state changes — I'd use
manual commit, and specifically commit *after* the side effect
(embedding + vector upsert) is confirmed durable, not before. That gives
at-least-once semantics with idempotent handling, which is the reliable
default absent a specific need for exactly-once (§3).

---

## 3. Exactly-Once Semantics

**What it is:** Kafka's guarantee (EOS, via idempotent producers +
transactions) that a message is processed and its effects committed
**exactly once**, even across producer retries and consumer failures —
as opposed to Kafka's default **at-least-once** delivery.

**Concepts to know:**

- **The default is at-least-once**, not exactly-once — a producer retry
  after a network timeout (where the broker actually received the
  message but the ack was lost) can create a duplicate unless idempotency
  is enabled. Always assume at-least-once unless you've specifically
  configured and verified EOS.
- **Idempotent producer** (`enable.idempotence=true`, default in modern
  Kafka clients): the producer attaches a sequence number per partition;
  the broker deduplicates retries of the same message. This alone gives
  you **exactly-once produce**, but not exactly-once across a full
  read-process-write pipeline.
- **Transactions** (`transactional.id`, `read_committed` isolation level
  on consumers): extend exactly-once across a **consume-transform-produce**
  pattern — e.g., consume from topic A, produce to topic B, and commit
  the *consumer offset* and the *produced message* atomically in one
  transaction. This is what "exactly-once" typically means in a Kafka
  Streams / stream-processing context.
- **The critical limitation to know cold: EOS covers Kafka-to-Kafka.** It
  does **not** automatically give you exactly-once for a **Kafka
  consumer → external system** write (a DB upsert, an embedding API call,
  a vector index write) — that's two separate systems with no shared
  transaction. For that, you need **idempotent consumer-side logic**
  (dedupe key + upsert semantics), which is what actually protects the
  AI/RAG ingestion pipeline, not Kafka's EOS feature.
- **Cost**: transactions add latency and broker overhead — only enable
  where the correctness requirement genuinely justifies it, not by
  default on every topic.

### Interview Q&A

**Q: Does enabling Kafka's exactly-once semantics mean your database writes will never be duplicated?**
A: No — and this is the most commonly misunderstood part of EOS. Kafka's
transactional guarantees are scoped to Kafka itself: consume offset commit
+ produce-to-another-topic, atomically. The moment a consumer writes to an
external system — a database, an API call, a vector store upsert — that
write is outside Kafka's transaction boundary. If the consumer crashes
after writing to the DB but before committing its Kafka offset, the
message gets redelivered and reprocessed on restart, causing a duplicate
DB write regardless of EOS being enabled upstream. The actual fix is
making that external write idempotent — a unique constraint, an upsert
keyed by a natural/dedupe key, or a "processed message IDs" table checked
before applying effects.

**Q: How would you achieve effectively-exactly-once processing for the AI/RAG ingestion pipeline, given Kafka can't guarantee it end-to-end?**
A: I wouldn't rely on Kafka's EOS feature at all here, since the pipeline's
side effect (vector DB upsert) is external to Kafka. Instead: idempotent
consumer logic — each document event carries a stable
`(source_system, source_id)` and a `content_hash`; the upsert is keyed by
that identity, so reprocessing the same event (from a redelivery after a
crash, or a legitimate republish) is a safe no-op rather than a duplicate
or corrupt state. That's "effectively exactly-once" achieved through
idempotency at the write, which is a more portable and simpler pattern
than trying to extend Kafka transactions across a heterogeneous system
boundary.

**Q: When would you actually reach for Kafka transactions / EOS rather than just idempotent consumers?**
A: When the pipeline is genuinely Kafka-to-Kafka — e.g., a stream
processor that reads from topic A, aggregates or transforms, and writes
to topic B, where you need the produced output and the consumed offset to
move together atomically so a crash mid-processing can't produce a
partial or duplicated result on topic B. Kafka Streams uses this
internally. Outside of stream processing, I've found idempotent
consumer-side handling covers the vast majority of real pipelines with
less operational complexity than managing transactional producers.

---

## 4. Rebalancing

**What it is:** The process by which a consumer group redistributes
partition ownership among its members — triggered when a consumer joins,
leaves, crashes, or fails a liveness check. During a rebalance (in the
classic/eager protocol), **all consumers in the group stop processing**
until new assignments are settled — this is the operational pain point
worth knowing cold.

**Concepts to know:**

- **What triggers it**: a consumer crash or graceful shutdown, a new
  consumer joining (scaling up), a consumer failing to send heartbeats
  within `session.timeout.ms`, or a consumer taking longer than
  `max.poll.interval.ms` to process a batch (Kafka assumes it's dead and
  kicks it out — a common cause of unexpected rebalances when processing
  logic is slow, e.g., a slow embedding-API call per message).
- **Eager rebalancing** (classic protocol): stop-the-world — every
  consumer gives up all its partitions, then reassignment happens, then
  consumers resume. Simple but causes a full processing pause across the
  entire group even if only one consumer changed.
- **Cooperative rebalancing** (`CooperativeStickyAssignor`, and the newer
  KIP-848 consumer group protocol in recent Kafka versions): incremental
  — only the specific partitions that need to move are revoked and
  reassigned; unaffected consumers keep processing their existing
  partitions throughout. This is the production-standard choice today
  specifically to avoid group-wide pauses.
- **Static membership** (`group.instance.id`, see §2) avoids triggering a
  rebalance at all for transient restarts (rolling deploys) — the
  consumer rejoins with its prior identity within the session timeout
  and reclaims its partitions without the group reshuffling.
- **Operational impact**: during a rebalance, in-flight messages that
  weren't yet committed get reprocessed by whichever consumer picks up
  the partition — another reason idempotency (§2, §3) is foundational,
  not optional.

### Interview Q&A

**Q: Your team reports frequent, disruptive rebalances during normal operation, not just deploys. How do you debug it?**
A: First check if it correlates with slow processing — if per-message
handling occasionally exceeds `max.poll.interval.ms` (a slow downstream
call, a GC pause, a burst of large messages), Kafka assumes the consumer
is dead and kicks it out, triggering a rebalance even though the process
is healthy. I'd check consumer logs for `max.poll.interval.ms` exceeded
warnings first, then look at heartbeat/session timeout tuning as a
secondary lever. If it's happening on every rolling deploy, static
membership (`group.instance.id`) is the direct fix — it stops normal
restarts from being treated as membership changes. I'd also confirm the
group is on a cooperative assignor rather than the eager/classic one,
since that alone limits the blast radius of any rebalance that does occur.

**Q: Why does a rebalance affect the whole consumer group, even if only one instance restarted?**
A: With the classic eager protocol, rebalancing is stop-the-world by
design — Kafka has to revoke all partition assignments group-wide before
it can safely recompute a new assignment, because otherwise you'd risk
two consumers briefly believing they both own the same partition.
Cooperative rebalancing fixes this by only revoking the specific
partitions that actually need to move, letting consumers that aren't
affected keep processing uninterrupted — it's the difference between
"stop everyone, then reassign" and "surgically move just what changed."
I'd default to the cooperative assignor for any production consumer group
today; there's little reason to accept the eager protocol's group-wide
pause.

**Q: How does a slow consumer affect the rest of the ingestion pipeline in the AI/RAG design?**
A: If an ingestion worker gets stuck on a slow embedding-API call and
exceeds `max.poll.interval.ms`, Kafka evicts it from the group and
triggers a rebalance — its partitions get reassigned to other workers,
who resume from the last committed offset (so no data loss, but the
in-flight message may be reprocessed). With cooperative rebalancing, this
disruption is scoped to just that consumer's partitions rather than
pausing the whole ingestion-worker group, which matters because a single
slow document shouldn't stall ingestion for every other document in
flight.

---

## 5. DLQ (Dead Letter Queue)

**What it is:** A separate topic where messages that repeatedly fail
processing are routed after exhausting retries — so a single poison
message doesn't block the partition indefinitely or get silently
dropped.

**Concepts to know:**

- **Why it's necessary**: Kafka partitions are ordered logs — a consumer
  processes offsets in order and normally can't skip a failing message
  without either blocking the entire partition behind it (every message
  after the poison one waits) or silently skipping it (data loss with no
  visibility). A DLQ resolves this: after N retry attempts, move the
  message aside, commit past it, and keep the partition flowing.
- **What goes in the DLQ payload**: not just the original message —
  include the failure reason, retry count, timestamp of last attempt, and
  original topic/partition/offset, so it's actually debuggable later
  rather than an opaque blob.
- **Retry strategy before DLQ**: usually a bounded number of retries with
  backoff (immediate retry rarely helps for anything but a transient
  blip; exponential backoff for things like a rate-limited downstream
  API). Some architectures use a **retry topic** with increasing delay
  tiers (retry-topic-1m, retry-topic-5m, retry-topic-30m) before finally
  landing in the DLQ — avoids blocking the main topic on backoff waits.
- **DLQ needs an operational story, not just a destination**: alerting
  when messages land there, an admin view to inspect and understand
  failures (this is exactly the `ingestion_status: 'failed'` surfaced on
  the admin dashboard in the AI/RAG design §9), and either a manual or
  automated **replay** path once the root cause is fixed — a DLQ that
  nobody monitors is just a data-loss mechanism with extra steps.
- **Distinguish retryable vs. non-retryable failures** before retrying at
  all: a malformed/corrupt document will never succeed no matter how many
  times you retry it — fail fast to the DLQ. A transient embedding-API
  timeout is worth retrying. Retrying a permanently-broken message N
  times before DLQ-ing it just wastes time and delays visibility into a
  real problem.

### Interview Q&A

**Q: Design the retry/DLQ strategy for the ingestion pipeline's embedding step.**
A: First classify the failure: a malformed PDF that fails parsing is
non-retryable — route straight to DLQ with the parse error attached, no
point retrying. A timeout or rate-limit response from the embedding API
is transient — retry with exponential backoff, capped at a small number
of attempts (e.g., 3–5) to avoid a stuck consumer holding up the
partition for minutes. After exhausting retries, publish to a
`doc-events-dlq` topic carrying the original event, the failure reason,
and attempt count, and update the document's status to `failed` in the
metadata store so it's visible on the admin dashboard rather than just
vanishing from view. I'd alert on DLQ volume crossing a threshold, since
a spike usually means a systemic issue (embedding provider outage) not
isolated bad documents.

**Q: What's the risk of retrying too aggressively before sending to a DLQ?**
A: Two risks: first, if the consumer blocks on retries synchronously
before committing, it can exceed `max.poll.interval.ms` and trigger an
unnecessary rebalance (§4) — a self-inflicted problem from over-retrying
in place. Second, aggressive retries against a downstream that's actually
struggling (e.g., an embedding API that's rate-limiting because it's
overloaded) can worsen the underlying problem — a thundering-herd effect.
I'd keep retries bounded, use backoff, and prefer moving to a DLQ or delay
topic quickly for anything that isn't clearly transient, rather than
holding up partition progress hoping it resolves.

**Q: How do you close the loop on DLQ messages — what happens after something lands there?**
A: A DLQ without a replay/resolution process just defers data loss and
adds a debugging step. For the AI/RAG pipeline I'd want: an alert when
DLQ volume crosses a threshold, an admin view showing failed documents
with their failure reason (already part of the design via `status:
'failed'` in the metadata store), and a replay mechanism — once the root
cause is fixed (e.g., a parser bug patched, or the embedding provider
recovers), either an automated reprocessing job or a manual "retry" action
that republishes the DLQ'd event back onto the main topic. I'd treat a
non-empty, non-shrinking DLQ the same way I'd treat an open, unowned
production alert.

---

## 6. Ordering

**What it is:** Kafka guarantees message order **only within a single
partition**, in the order the producer sent them (assuming no
retries/reordering — see below) — there is no ordering guarantee across
partitions of a topic.

**Concepts to know:**

- **Key selection determines your ordering guarantee.** Messages with the
  same key always land on the same partition (via the default hash
  partitioner), so all events for that key are strictly ordered relative
  to each other. Messages with different keys have **no** relative
  ordering guarantee. This is why the AI/RAG design keys `doc-events` by
  `document_id` — it guarantees a document's create/update/delete events
  are processed in the order they happened, while different documents'
  events can process in any relative order (which is fine — they're
  independent).
- **Producer retries can reorder messages** unless `enable.idempotence`
  is on (default in modern clients) or `max.in.flight.requests.per.connection`
  is constrained — without this, a retried message can land after a
  later message that succeeded on the first attempt, breaking order even
  within a partition. Idempotent producers fix this as a side effect of
  their sequence-numbering.
- **Consumer-side ordering**: even with strictly ordered delivery, if a
  consumer processes messages **concurrently** (e.g., dispatching to a
  thread pool for throughput) it can process/complete them out of order.
  If order matters downstream, either process serially per partition or
  ensure concurrent processing still commits/applies effects in
  received order (e.g., a per-key sequential queue within the consumer).
- **Global ordering across an entire topic is not a Kafka feature** — if
  a use case genuinely needs it, the only way is a single partition for
  that topic, which caps throughput at what one partition can sustain.
  Recognize this as a real trade-off to name explicitly rather than
  something to route around cleverly — total order and partitioned
  parallelism are fundamentally in tension.

### Interview Q&A

**Q: How do you guarantee that a document's update events are processed in the order they occurred, given Kafka doesn't guarantee topic-wide ordering?**
A: Key the topic by `document_id`. Kafka's partitioner routes all
messages with the same key to the same partition deterministically, and
within a partition, order is preserved end-to-end (with idempotent
producers enabled to prevent retry-induced reordering). So every event
for a given document is strictly ordered relative to other events for
that *same* document, which is exactly the guarantee needed — I don't
need ordering across different documents' events since they're
independent of each other. This gets ordering "for free" from Kafka's
partitioning model rather than needing an application-level sequencing
mechanism.

**Q: If you need strict ordering, why not just use one partition for the whole topic?**
A: You can, but it caps the topic's throughput and consumer parallelism
at what a single partition — and a single consumer, since only one
consumer in a group can own a partition — can sustain, and it eliminates
the ability to scale ingestion horizontally. The better pattern is almost
always to identify the actual granularity that needs ordering (per-key,
not global) and key the topic so that only related events share a
partition, keeping unrelated events free to parallelize across many
partitions. True global ordering requirements are rare in practice; most
"needs to be ordered" requirements turn out to be "needs to be ordered
per entity" on closer inspection.

**Q: Your consumer processes messages using a thread pool for throughput, but downstream state is getting corrupted for a specific entity. Why?**
A: Classic order-violation bug: Kafka delivers messages from a partition
in order, but dispatching them to a thread pool for concurrent processing
means completion order isn't guaranteed to match delivery order — a
later message for the same entity can finish (and apply its effect)
before an earlier one if the earlier one's thread happens to be slower.
The fix is to preserve per-key ordering through the concurrency layer —
e.g., hash the message key to a fixed worker/queue so all messages for
the same entity are processed by the same thread in order, while
different entities still process in parallel across threads. Throughput
scaling and per-entity ordering aren't actually in conflict here — you
just have to shard concurrency by the same key Kafka partitioned by.

---

## 7. Schema Registry

**What it is:** A separate service (e.g., Confluent Schema Registry) that
stores and versions the schemas (Avro, Protobuf, or JSON Schema) for
messages on a topic, and enforces **compatibility rules** so producers and
consumers can evolve independently without breaking each other.

**Concepts to know:**

- **The problem it solves**: without a schema registry, a producer
  changing a message's shape (renaming a field, changing a type) silently
  breaks every consumer deserializing that topic, often not discovered
  until a consumer throws at runtime — the Kafka equivalent of a breaking
  API change shipped with no versioning or contract check.
- **How it works at the wire level**: the registry issues a schema ID;
  producers serialize messages with just that small ID prefix (not the
  full schema) referencing a schema stored centrally; consumers fetch the
  schema by ID (cached after first lookup) to deserialize. This keeps
  messages compact while giving every message an explicit, resolvable
  contract.
- **Compatibility modes** — this is the part interviewers actually probe:
  - **Backward compatible**: new schema can read data written with the
    old schema (a consumer upgraded to the new schema can still read old
    messages) — the default and most common choice, since it lets
    consumers upgrade independently of producers.
  - **Forward compatible**: old schema can read data written with the new
    schema — lets producers upgrade first, before consumers.
  - **Full compatible**: both directions — safest, most restrictive on
    what changes are allowed.
  - Practically: backward-compatible changes are things like adding an
    **optional field with a default**; removing a required field or
    changing a field's type are breaking changes the registry will reject
    if compatibility enforcement is on.
- **Why this matters for the AI/RAG ingestion pipeline specifically**:
  `doc-events` is consumed by multiple independent consumer groups
  (ingestion workers, cache invalidation, potentially future consumers) —
  a schema registry with enforced backward compatibility means the
  event producer (source connectors) can add fields for a new source type
  without coordinating a simultaneous deploy with every consumer, which
  is exactly the kind of cross-team coordination cost you want Kafka's
  decoupling to eliminate, not reintroduce.

### Interview Q&A

**Q: Why use a schema registry instead of just agreeing on a JSON shape via documentation?**
A: Documentation doesn't stop a producer from shipping a breaking change
— nothing enforces it at deploy time, so the failure surfaces as a
runtime deserialization error in some consumer, potentially one owned by
a different team, discovered after the fact. A schema registry makes
compatibility a build/deploy-time check: a producer trying to register a
schema that breaks the configured compatibility mode gets rejected before
the change ships, not after it's already corrupted a consumer in
production. It's the same value proposition as a typed API contract or a
DB migration linter versus "we all agreed not to do that."

**Q: What's the difference between backward and forward compatibility, and which do you default to?**
A: Backward compatible means a consumer running the *new* schema can
still correctly read messages produced under the *old* schema — this lets
you upgrade consumers independently and before producers, which matches
how most systems actually deploy (consumers are typically more numerous
and harder to coordinate than the producer side). Forward compatible is
the reverse — old consumers can read new-schema messages, letting
producers upgrade first. I'd default to backward compatibility for most
topics, since "add an optional field with a default" is the most common,
safe evolution and that's naturally backward-compatible; I'd only reach
for forward or full compatibility if I specifically know producers need
to deploy ahead of a slow-moving consumer fleet.

**Q: A producer needs to add a new required field to an event schema. How do you roll that out safely?**
A: A new *required* field without a default is a backward-incompatible
change by definition — old consumers reading it would either fail or
silently mishandle the missing semantics reversed (a new consumer reading
old data has no value for that field). The safe path: add it as
**optional with a sensible default** first, ship it, let consumers adopt
it at their own pace, and only consider making it required later (if
ever) once you've confirmed via the registry/monitoring that no consumer
is still on a schema version predating the field. This is the schema
equivalent of a multi-phase DB migration (add nullable column → backfill
→ make non-null) — you don't jump straight to the breaking state.

---

## 8. Quick-Reference Summary Table

| Concept | One-line takeaway | Production lever |
|---|---|---|
| Partitions | Unit of parallelism + ordering scope | Size for target parallelism + throughput; don't over-provision blindly |
| Consumer groups | One partition owned by one consumer per group | Manual commit + idempotent processing for correctness-sensitive pipelines |
| Exactly-once semantics | Kafka EOS covers Kafka-to-Kafka only | Idempotent consumer writes for any external side effect |
| Rebalancing | Stop-the-world (eager) unless mitigated | Cooperative assignor + static membership to avoid group-wide pauses |
| DLQ | Prevents one poison message from blocking a partition | Bounded retries with backoff, then DLQ with failure context, then a replay path |
| Ordering | Guaranteed only within a partition, by key | Key by the entity that needs order; preserve key-order through concurrent consumers |
| Schema Registry | Enforces producer/consumer contract compatibility | Default to backward-compatible evolution (optional fields + defaults) |

---

## 9. Interview Q&A (Cross-Cutting / System-Level)

**Q: Design the Kafka topic strategy for the AI/RAG assistant's ingestion pipeline from scratch. What are your key decisions and why?**
A: One primary topic, `doc-events`, keyed by `document_id` for per-document
ordering (§6), with a moderate partition count sized to the expected
ingestion throughput and consumer parallelism (§1) — I'd rather
over-provision partitions modestly up front than need to expand later and
disturb the key mapping. `acks=all` for durability, since losing a
document-change event means that document silently never gets
re-indexed — a correctness bug, not just a performance concern.
Idempotent producer enabled by default. Two independent consumer groups
read it (§2): ingestion workers (parse/chunk/embed/upsert) and a
cache-invalidation consumer — decoupled so one being slow never affects
the other. Ingestion workers use manual offset commits after the vector
upsert succeeds, with idempotent upserts keyed by
`(source_system, source_id, content_hash)` as the actual correctness
mechanism (§3), not reliance on Kafka's EOS. Non-retryable failures
(malformed documents) go straight to a `doc-events-dlq` topic;
transient failures (embedding API timeouts) retry with bounded backoff
first (§5). A schema registry with backward-compatible evolution enforced
lets new source connectors add fields without coordinating simultaneous
deploys with every consumer (§7). And consumers run on the cooperative
rebalancing protocol with static membership so rolling deploys of the
ingestion workers don't pause the whole group (§4).

**Q: What's the biggest Kafka production incident you'd want to be ready to discuss, even hypothetically, and what would you check first?**
A: A consumer group falling behind (rising lag) is the most common real
incident shape. First check: is it one partition or all of them — a
single hot/skewed partition (§1) points to key distribution; all
partitions lagging evenly points to either a genuine throughput
mismatch (need more consumers, up to partition count) or a slow
downstream dependency each message processing call is waiting on (in
this pipeline, the embedding API). Second check: is the group churning
through rebalances (§4) — visible in consumer logs as repeated
join/leave — which would explain lag as a symptom of instability rather
than raw throughput. I'd have a lag-alerting dashboard per consumer
group as a standing tool for this, not something built during the
incident.

**Q: How does everything in this guide reinforce the "treat AI pipeline components like any other distributed system" theme from the AI Knowledge guide?**
A: Every AI-specific concern in the RAG pipeline — re-embedding after a
model change, keeping the vector index fresh, avoiding duplicate
processing — maps directly onto standard Kafka/distributed-systems
patterns: replay-from-offset for reprocessing, idempotent upserts for
duplicate safety, partitioned ordering for correctness, and DLQ +
schema registry for operability. None of it requires inventing new
mechanisms — the discipline is recognizing that "call an embedding API
and write to a vector store" is just another consumer with an external
side effect, subject to exactly the same failure modes as a consumer
writing to a relational database, and should be engineered with the
same rigor.

---

*Next up (on request): Distributed Systems.*
