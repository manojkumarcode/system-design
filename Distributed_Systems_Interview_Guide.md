# Distributed Systems — Interview Deep Dive

> Companion guide to `Engineering_Leadership_Interview_Preparation_Guide.md`,
> `AI_Knowledge_Interview_Guide.md`, `AI_RAG_Assistant_System_Design_Guide.md`,
> and `Kafka_Interview_Guide.md`. Covers the **Distributed Systems**
> checklist in full: CAP theorem, consistency models, idempotency, retry
> strategies, circuit breaker, saga pattern, outbox pattern, and
> event-driven architecture.
>
> Framing: this is the theory underneath everything in the other three
> guides — Kafka's ordering/delivery guarantees, the AI/RAG pipeline's
> ACL-vs-freshness consistency split, idempotent ingestion upserts, all of
> it are applications of the patterns here. Interviewers at the EM/
> Principal/Staff level use this topic to check whether you reason about
> failure and trade-offs from first principles, not whether you can recite
> definitions. Every section leads with the concept, then the trade-off,
> then Q&A — including "what breaks and how do you know" follow-ups.

---

## Table of Contents

1. [CAP Theorem](#1-cap-theorem)
2. [Consistency Models](#2-consistency-models)
3. [Idempotency](#3-idempotency)
4. [Retry Strategies](#4-retry-strategies)
5. [Circuit Breaker](#5-circuit-breaker)
6. [Saga Pattern](#6-saga-pattern)
7. [Outbox Pattern](#7-outbox-pattern)
8. [Event-Driven Architecture](#8-event-driven-architecture)
9. [How These Patterns Compose](#9-how-these-patterns-compose)
10. [Interview Q&A](#10-interview-qa-cross-cutting)

---

## 1. CAP Theorem

**What it is:** In the presence of a network **P**artition, a distributed
system must choose between **C**onsistency (every read sees the latest
write) and **A**vailability (every request gets a response). You cannot
have all three of C, A, and P simultaneously when a partition actually
occurs — and in any real distributed system, partitions *will* occur, so
this isn't a hypothetical choice, it's a design default you commit to
ahead of time.

**Concepts to know — and the common misconceptions to avoid stating:**

- **CAP is not a permanent three-way trade-off you're making on every
  request** — it's specifically about behavior **during a partition**.
  When the network is healthy, you can have both C and A. The theorem
  only forces a choice when nodes can't talk to each other.
- **"P" isn't optional** — in any system with more than one node
  communicating over a real network, partitions happen (a switch fails, a
  process pauses for GC, a region loses connectivity). So in practice the
  real-world choice is CP vs. AP, not "pick two of three."
- **CP system**: during a partition, the system refuses to serve requests
  it can't guarantee are consistent — returns an error or times out
  rather than risk a stale/conflicting read. Example: a strongly
  consistent leader-based system (etcd, ZooKeeper, a single-leader
  relational DB) that stops accepting writes if it can't reach a quorum.
- **AP system**: during a partition, the system keeps serving requests
  from whatever node it can reach, accepting that different nodes might
  return different (stale or conflicting) answers temporarily, reconciled
  later. Example: DynamoDB, Cassandra (tunable), most caches.
- **This isn't binary in practice** — modern systems are usually **tunable
  per-operation**, not globally CP or AP. Cassandra lets you choose
  read/write consistency levels (`QUORUM`, `ONE`, `ALL`) per query.
  The real skill being tested is picking the right point on the spectrum
  **per use case**, not memorizing which database is "CP" or "AP" as a
  label.
- **Practical translation for the AI/RAG design** (§2 NFRs in that guide):
  ACL/authorization checks need **CP behavior** — fail closed rather than
  serve a request based on possibly-stale permission data. Document
  content freshness can be **AP** — serving a slightly stale answer while
  the index catches up is an acceptable degradation, not a correctness
  violation. Stating this split explicitly is exactly the kind of
  concrete, non-textbook answer interviewers want.

### What Exactly Is a Network Partition?

Worth pulling apart on its own, because "partition" is the word in CAP
that gets waved through fastest — and it's the one the whole theorem
hinges on.

> **A network partition is a communication failure, not a server
> failure.** The nodes are alive and healthy. The network link between
> some of them is broken, delayed, or dropping messages. Each side keeps
> running, unaware of what the other side is doing.

That's the distinction to lead with in an interview: people default to
picturing "a server is down," but a partition is specifically the case
where **every node is up** and the failure is purely in the wire between
them — which is what makes it insidious. A crashed node is easy to
detect and route around. A partitioned node looks, from its own point of
view, completely fine — it just can't reach its peers.

**Three DB replicas, one broken link:**

```
Normally — all three replicate freely:

    DB1 <----> DB2
    DB2 <----> DB3
    DB1 <----> DB3

A cable/switch/routing failure breaks one link:

    DB1 <--X--> DB2      ← DB1 and DB2 can no longer talk
    DB2 <-------> DB3
    DB1 <-------> DB3

DB1 is alive. DB2 is alive. Both keep serving reads/writes locally.
Neither can propagate updates to the other. This is the partition.
```

**Same failure, cloud-native framing — two Availability Zones:**

```
   Mumbai AZ-1                    Mumbai AZ-2
   ┌───────────┐   replication   ┌───────────┐
   │ Database A│ <-------------> │ Database B│
   └───────────┘                 └───────────┘

           the inter-AZ network link fails

   ┌───────────┐        X        ┌───────────┐
   │ Database A│ <-------------> │ Database B│
   └───────────┘                 └───────────┘

   Both databases still accept client connections in their own AZ.
   Neither can confirm the other received its latest writes.
```

**The analogy that makes it stick:** two bank branches connected by a
phone line. Cut the phone line and Delhi keeps serving customers, Mumbai
keeps serving customers — neither is "down" — but neither knows what the
other just did. That gap is the partition.

**Why it's dangerous, concretely — the double-withdrawal:**

```
Account balance, replicated:            DB1: ₹10,000   DB2: ₹10,000

Network partitions. DB1 and DB2 can no longer sync.

Customer A → DB1 → withdraws ₹5,000  →  DB1: ₹5,000    DB2: ₹10,000
Customer B → DB2 → withdraws ₹5,000  →  DB1: ₹5,000    DB2: ₹5,000

₹10,000 was withdrawn from an account that should have blocked the
second withdrawal — each replica enforced its own "sufficient balance"
check against data that was correct locally and stale globally.
```

This is exactly the fork in the road CAP describes: once DB1 and DB2
can't confirm each other's state, the system has to pick one of two
responses to Customer B's request —

- **Reject it** (CP) — "can't verify balance, replica unreachable, try
  again later" — correctness preserved, availability sacrificed.
- **Accept it** (AP) — serve the request from local, possibly-stale
  state, and reconcile (or simply detect and remediate) the conflict once
  the partition heals — availability preserved, a temporary consistency
  violation accepted as the cost.

There is no third option that gives both, **for the duration of the
partition** — the two replicas cannot exchange the information needed to
guarantee agreement, so any answer given right now is either "possibly
stale" or "no answer."

**Where real systems land** (useful to have a few memorized, not as
labels to recite but as evidence you've actually looked):

| System | Behavior during a partition | CAP leaning |
|---|---|---|
| ZooKeeper / etcd | Reject writes without quorum | CP |
| HBase | Reject writes to an unreachable region | CP |
| MongoDB (majority write concern) | Reject writes without majority ack | CP |
| Cassandra | Keep serving, tunable per-query consistency level | AP by default, tunable toward CP |
| DynamoDB | Keep serving, tunable consistency (eventual vs. strong reads) | AP by default |
| Redis Cluster | Depends on `min-replicas-to-write` config | CP or AP by configuration |

Note this table is really a restatement of the point already made above
in this section — "tunable per-operation, not a fixed label" — Cassandra
and Redis Cluster show up on both sides depending on how a given
operation is configured, which is the answer to give if an interviewer
pushes on "so is Cassandra CP or AP?"

### Interview Q&A

**Q: What exactly is a network partition? How is it different from a node crashing?**
A: A partition is a failure of the *network*, not the *nodes* — every
node involved is still running and would respond correctly to a client
that could reach it, but the links between some subset of nodes are
down, delayed, or dropping traffic, so they can't confirm each other's
state. A crashed node is comparatively easy to handle: other nodes can
detect it's unreachable, stop routing to it, and treat it as removed from
the cluster. A partition is harder specifically because there's
ambiguity — from DB1's side, is DB2 down, or just unreachable *from
here*? Those require different responses (evict a dead node vs. degrade
gracefully while a live-but-unreachable one might still be serving its
own clients), and a system that conflates the two can make the wrong
call — e.g., DB1 wrongly evicting a perfectly healthy DB2 and both
continuing to accept writes as if they were now the sole owner of the
data, which is worse than either CP or AP handled deliberately. This
ambiguity is precisely why consensus protocols (Raft, Paxos) exist —
they give a principled way to establish who's allowed to keep serving
writes when connectivity is uncertain, rather than each side guessing.

**Q: Explain CAP theorem, and then tell me why that textbook framing is incomplete.**
A: CAP says that during a network partition, a distributed system must
choose consistency or availability — it can't guarantee both. The
textbook framing is incomplete because it implies a static, system-wide
choice, when in practice: partitions are the exception state, not the
normal state, so most of the time you get both C and A; the real
engineering decision is what happens *during* the partition, which is
often tunable per-operation rather than a fixed property of "this
database"; and most real systems mix CP and AP behavior across different
parts of their data — I'd rather talk about which operations need which
guarantee than label an entire system CP or AP.

**Q: Design an e-commerce checkout system. Where would you choose CP vs. AP, and why?**
A: Inventory decrement and payment authorization need CP-like behavior —
I would rather reject or delay a checkout during a partition than risk
overselling inventory or double-charging a customer; those are
correctness violations with real financial/legal consequences. Product
catalog browsing and search, on the other hand, should stay AP — showing
a slightly stale price or "in stock" badge during a partition is a far
better user experience than an outage, and it gets corrected the moment
connectivity is restored. The system as a whole isn't "a CP system" or
"an AP system" — it's a set of operations, each deliberately placed on
the spectrum based on the cost of being wrong versus the cost of being
unavailable.

**Q: How does this apply to the ACL enforcement in the AI/RAG assistant design?**
A: ACL checks are explicitly CP — from the Failure Handling section of
that design, if ACL data is stale or unreachable, the system fails closed
(deny access) rather than fail open. That's a direct CAP trade-off stated
as policy: for that specific operation, correctness (never leak
unauthorized content) outranks availability (better to refuse to answer
than to answer wrong). Meanwhile document content freshness in the same
system is deliberately AP — an eventually-consistent vector index that
can lag by minutes is an accepted trade-off, because the cost of a
slightly stale answer is low compared to the cost of blocking on it.

---

## 2. Consistency Models

**What it is:** A precise specification of what guarantees a distributed
data store makes about the order and visibility of operations across
nodes — the spectrum between "every read sees every write immediately,
everywhere" (strong) and "reads may return stale or conflicting data that
eventually converges" (eventual), with useful intermediate points.

**The spectrum, strongest to weakest:**

| Model | Guarantee | Example use |
|---|---|---|
| **Linearizability** (strict/strong consistency) | Every operation appears to take effect instantaneously at some point between its start and end; all clients see the same order | Leader election, distributed locks, ACL checks — anything where "stale" means "wrong" |
| **Sequential consistency** | All operations appear in *some* single global order consistent with each client's own program order — but that order need not match real time | Rare to need explicitly in application code; mostly a building block for other guarantees |
| **Causal consistency** | Operations that are causally related (a reply to a message) are seen in the same order by everyone; unrelated operations may be seen in different orders by different nodes | Comment threads, chat systems — a reply should never appear before the message it replies to |
| **Read-your-writes** | A client always sees its own prior writes, even if it might see other clients' writes late | User updates their profile and immediately reloads — must see their own change |
| **Eventual consistency** | Given no new writes, all replicas *eventually* converge to the same value — no guarantee on how long, no ordering guarantee in between | Vector index freshness in the AI/RAG design; DNS; most caches |

**Concepts to know:**

- **Stronger consistency costs latency and availability** — linearizable
  reads/writes typically require coordination (consensus, quorum,
  single-leader routing), which means higher latency and reduced
  availability during a partition (this is CAP, applied). Weaker models
  trade correctness guarantees for lower latency and higher availability.
- **Pick the weakest model that's still correct for the use case** — this
  is the actual skill. Defaulting everything to strong consistency is
  the "safe-sounding" wrong answer; it's over-engineering that costs
  latency/availability for guarantees most operations don't need.
  Defaulting everything to eventual consistency is the other failure
  mode — silently wrong behavior where users need read-your-writes at
  minimum (nobody accepts not seeing their own comment after posting it).
- **"Eventual consistency" is not one thing** — "eventually" with no
  bound is a weak, often-unacceptable guarantee in production; most real
  systems want a **bounded staleness** guarantee ("converges within N
  seconds") which is a meaningfully stronger, more testable commitment.

### Interview Q&A

**Q: A user updates their display name and immediately refreshes the page, but still sees the old name. What consistency guarantee is missing, and how do you fix it?**
A: Missing read-your-writes consistency — the read is likely hitting a
replica that hasn't caught up with the write yet. Fixes depend on the
architecture: route a user's reads to the primary/leader for a short
window after their own write (common pattern: "sticky" reads for N
seconds post-write); or have the client optimistically apply its own
write locally and reconcile with the server response instead of
round-tripping through a possibly-stale replica; or, if using a system
with tunable consistency (e.g., Cassandra), issue that specific read at
a stronger consistency level. I'd scope the fix to exactly the operations
that need it rather than making all reads strongly consistent, which
would undo the availability/latency benefits the replica setup exists for
in the first place.

**Q: Why does the AI/RAG assistant design use eventual consistency for the vector index but not for ACLs?**
A: Because the cost of being wrong is completely different for the two.
A stale vector index means an answer might miss a document updated 90
seconds ago — a minor, self-correcting quality issue. Stale ACL data
means a user might see content they're no longer authorized to see — a
security violation with no acceptable "eventually corrects itself"
excuse. Consistency model choice should follow directly from "what does
it cost to be wrong, and for how long," not from a system-wide default —
this is the same principle as the CAP discussion, applied at the
data-model level instead of the availability level.

**Q: What's the difference between eventual consistency and causal consistency, with an example where the difference actually matters?**
A: Eventual consistency gives no ordering guarantee at all between
unrelated writes — different nodes can observe writes in different
orders, even causally related ones, as long as they converge eventually.
Causal consistency specifically preserves order for operations that are
causally linked. Concretely: in a comment thread, if user A posts a
comment and user B replies to it, causal consistency guarantees no reader
ever sees B's reply without also seeing A's original comment first — a
plain eventually-consistent store could show the reply appearing to
respond to nothing, which is confusing and wrong-looking even though
"eventually" both writes are visible everywhere. I'd reach for causal
consistency (or a system that provides it, like many document stores with
vector clocks / session tokens) specifically when the data has this kind
of reply/reference relationship that plain eventual consistency doesn't
preserve.

---

## 3. Idempotency

**What it is:** An operation is idempotent if performing it multiple times
has the same effect as performing it once. This is the single most
important defensive property in any distributed system, because
**at-least-once delivery is the realistic default** everywhere — network
retries, consumer redelivery after a crash (Kafka guide §2), client
double-clicks, load balancer retries — duplicates are not an edge case,
they are a constant background fact of distributed systems.

**Concepts to know:**

- **Naturally idempotent operations**: `SET x = 5`, `DELETE where id=1`,
  an upsert keyed by natural ID. Applying twice = applying once, with no
  extra work.
- **Naturally non-idempotent operations**: `increment balance by 10`,
  `INSERT` without a uniqueness constraint, "send an email," "charge a
  card" — these need to be made idempotent deliberately.
- **The standard mechanism: idempotency keys.** The client (or upstream
  producer) attaches a unique key to the operation (a UUID generated once
  per logical intent, not per retry); the receiving system checks "have I
  already processed this key?" before applying the effect — if yes,
  return the previously recorded result without reapplying it. This is
  exactly the `(source_system, source_id, content_hash)` dedupe key used
  in both the AI/RAG ingestion upserts and the Kafka guide's DLQ/retry
  discussion.
- **Where to store the "have I seen this key" record** matters: it needs
  to be checked and updated **atomically with the effect itself**, or you
  reintroduce the same race you were trying to close (see Outbox Pattern,
  §7, for the canonical way to get this atomicity across a DB write and a
  message publish).
- **Idempotency key lifetime**: keys can't be remembered forever cheaply
  — a TTL (e.g., 24 hours) is standard, sized to comfortably exceed the
  maximum realistic retry window for that operation.
- **Idempotency is a prerequisite for safe retries** (§4) — retrying an
  operation that isn't idempotent is how duplicate charges, duplicate
  emails, and duplicate order creation happen in production.

### Interview Q&A

**Q: How would you make a "create order" API endpoint safe to retry?**
A: Require the client to generate and send an idempotency key (a UUID)
with the request — generated once when the user clicks "place order," and
reused identically on any client-side retry (e.g., after a timeout where
the client doesn't know if the first request succeeded). Server-side, I'd
check a table/index keyed by that idempotency key before creating a new
order: if it exists, return the previously created order's result instead
of creating a duplicate; if not, create the order and record the key
atomically in the same transaction. Critically, the key represents the
user's *intent* ("place this order"), not the HTTP request — the same key
must produce the same outcome even if the retry happens minutes later
through a different server instance.

**Q: Why is idempotency described as a prerequisite for retries rather than something separate?**
A: Because retrying a non-idempotent operation is exactly how you
introduce duplicate side effects — a payment charged twice, an email sent
three times, an order created twice from one click. Retries are
necessary in a distributed system (transient failures are common and
often not the caller's fault), but retrying safely requires that
replaying the same logical operation is a no-op if it already succeeded.
Without idempotency, the "safe" response to a timeout is to *not* retry
and instead surface an error to the user — a strictly worse experience —
so idempotency is what actually unlocks retries as a viable resilience
strategy rather than a source of new bugs.

**Q: In the Kafka ingestion pipeline, what specifically makes the vector upsert idempotent, and what would break if you removed that mechanism?**
A: The upsert is keyed by `(source_system, source_id)` with a
`content_hash` check — reprocessing the same document-changed event
(from Kafka's at-least-once redelivery after a consumer crash, or a
rebalance-triggered reprocess) looks up the existing document by that
key and either no-ops (hash unchanged) or updates in place (hash
changed), rather than inserting a new row. Remove that key and every
redelivery — which, per the Kafka consumer-groups discussion, is a normal
and expected occurrence, not a rare failure — would create a duplicate
chunk in the vector index, degrading retrieval quality over time as
duplicate/stale chunks accumulate and eventually get retrieved alongside
or instead of the current version.

---

## 4. Retry Strategies

**What it is:** The policy governing *whether*, *how many times*, and
*with what timing* a failed operation is retried — sitting directly on
top of idempotency (§3), because retries are only safe to the degree the
retried operation is idempotent.

**Concepts to know:**

- **Not all failures should be retried.** A 4xx-class error (bad request,
  unauthorized, not found) retrying won't help — it'll fail identically
  every time and just waste resources/latency. A 5xx/timeout/connection
  error is plausibly transient and worth retrying. Classify before
  retrying, the same distinction as retryable vs. non-retryable failures
  in the Kafka DLQ discussion.
- **Fixed-interval retry** is rarely the right choice in production — if
  many clients retry at the same fixed interval after a shared dependency
  blips, they all hammer it again simultaneously, potentially preventing
  recovery (a self-inflicted **retry storm**).
- **Exponential backoff**: each retry waits progressively longer
  (`base * 2^attempt`), reducing sustained load on a struggling
  dependency and giving it room to recover.
- **Jitter**: adding randomness to the backoff interval so retries from
  many concurrent clients don't stay synchronized even with exponential
  backoff — without jitter, exponential backoff alone can still produce
  synchronized retry waves. "Exponential backoff with full jitter" is the
  standard production recommendation (AWS's well-known architecture
  guidance on this is worth being able to cite).
- **Bounded retry count/budget**: unbounded retries turn a downstream
  degradation into an unbounded latency tail (or, worse, a resource leak
  from accumulating in-flight retry attempts) on the caller side — always
  cap attempts, and have a defined behavior when the cap is hit (fail the
  request, DLQ, fallback).
- **Retries interact with circuit breakers** (§5) — a circuit breaker is
  what stops a caller from retrying at all against a dependency that's
  known to be down, rather than each request independently retrying into
  a wall.

### Interview Q&A

**Q: Design a retry policy for a service calling a flaky downstream payment API.**
A: First, classify errors: retry on timeouts and 5xx (plausibly
transient); never retry on 4xx like invalid card details (retrying a
guaranteed-to-fail request just adds latency and load for no benefit).
For retryable errors: exponential backoff with full jitter, capped at a
small number of attempts (e.g., 3), with a total retry budget/timeout so
the caller doesn't hang indefinitely. Critically, the payment call itself
must be idempotent — an idempotency key sent to the payment provider — or
retrying risks a duplicate charge, which is a worse outcome than the
original failure. I'd pair this with a circuit breaker (§5) so that once
the payment API is clearly down (not just occasionally flaky), we stop
retrying entirely and fail fast instead of adding load to an already
struggling dependency.

**Q: Why is jitter necessary in addition to exponential backoff?**
A: Exponential backoff alone still leaves all clients that failed at
roughly the same moment retrying at roughly the same intervals — attempt
1 at t+1s, attempt 2 at t+2s, etc., for every client, in lockstep. If the
downstream dependency is recovering, that synchronized wave of retries
can look like a second traffic spike and knock it back over, especially
right as it's coming back up. Jitter — randomizing the actual wait within
the backoff window — spreads those retries out in time so the aggregate
retry load smooths into something closer to the dependency's actual
recovering capacity, instead of a series of synchronized bursts.

**Q: What's the failure mode of retries without a circuit breaker, at scale?**
A: If a downstream dependency goes fully down (not flaky, actually down),
every caller independently retries with backoff, but backoff only slows
*each individual caller* — with enough concurrent callers, the aggregate
retry volume can still be substantial and sustained, adding load to a
dependency that's already failing and potentially delaying its recovery
(or preventing it from ever stabilizing, since it never gets a quiet
window). This is exactly the failure mode a circuit breaker exists to
prevent — once failures cross a threshold, the breaker opens and callers
stop sending requests at all for a cooldown period, giving the dependency
room to recover instead of every caller's independent retry logic
collectively DoS-ing it.

---

## 5. Circuit Breaker

**What it is:** A stateful guard in front of a call to an external
dependency that "trips open" after a threshold of failures, causing
subsequent calls to fail fast (without even attempting the call) for a
cooldown period — then cautiously allows a trial request through to check
if the dependency has recovered before fully closing again.

**Concepts to know — the three states:**

- **Closed** (normal): requests pass through to the dependency; failures
  are tracked (typically a rolling window, e.g., failure rate over the
  last N requests or T seconds).
- **Open**: failure threshold exceeded; all requests fail immediately
  without calling the dependency at all — protects both the caller
  (no wasted latency waiting on a call likely to fail) and the callee
  (no added load while it's struggling/recovering).
- **Half-open**: after a cooldown timeout, the breaker allows a limited
  number of trial requests through; if they succeed, transition back to
  closed; if they fail, back to open with the cooldown timer reset.

**Concepts to know:**

- **What it protects against that retries alone don't**: retries (§4)
  handle transient, isolated failures well but don't prevent sustained
  load against a dependency that's genuinely down — a circuit breaker is
  the mechanism that stops trying altogether for a while, which retries
  by themselves don't do.
- **Fail-fast benefit for the caller**: without a breaker, every request
  during an outage still pays the full timeout latency waiting to fail —
  with an open breaker, failure is immediate, which matters a lot for
  upstream user-facing latency and for preventing thread-pool/connection
  exhaustion in the calling service itself (a slow dependency can take
  down an otherwise-healthy caller purely by exhausting its resources
  waiting on slow calls — this is the mechanism behind many cascading
  failures).
- **Fallback behavior while open**: what does the caller do instead of
  the real call — a cached/stale response, a degraded default, or a clear
  error to the end user. Defining this explicitly is part of the design,
  not an afterthought; "the circuit breaker trips" isn't a complete
  answer without "...and then what happens to the user's request."
- **Java/Spring specifics**: resilience4j is the standard library
  (Hystrix is deprecated/EOL) — configurable failure-rate threshold,
  sliding window type (count-based/time-based), wait duration in open
  state, and permitted calls in half-open state. Composes naturally with
  retry and timeout decorators (order matters: typically timeout → retry
  → circuit breaker, so the breaker sees the outcome of the full
  retry-with-timeout attempt as a single unit).

### Interview Q&A

**Q: Why do you need a circuit breaker if you already have retries with backoff?**
A: Retries handle "this one request failed, try again" — they don't
address "this dependency is down and every request will fail for the next
five minutes." Without a breaker, every caller keeps attempting (with
backoff) for the full outage duration, which means: each request still
pays latency waiting to fail/retry, connection pools and thread pools on
the caller side stay tied up waiting on a dependency that isn't
responding (a common cause of a failure cascading from one degraded
service into the callers that depend on it), and the struggling
dependency keeps receiving load exactly when it most needs relief to
recover. A circuit breaker addresses this by cutting off calls entirely
once failure is clearly sustained, protecting both sides, and only
resuming traffic cautiously once there's evidence of recovery.

**Q: What should happen to a user's request while a circuit breaker is open?**
A: Depends on what the dependency does and what's acceptable to degrade.
For the AI/RAG assistant's LLM call, per that design's failure handling,
I'd fail to a secondary provider if configured, otherwise return a clear
"assistant temporarily unavailable" rather than hanging. For something
like a recommendations service on an e-commerce product page, a fallback
to a cached or generic "popular items" list is often better than showing
an error — the feature degrades gracefully instead of breaking the page.
The wrong answer is designing the breaker and stopping there — "it fails
fast" is only half the design; "and here's what the user sees instead" is
the other half, and it should be a deliberate product decision, not
whatever happens to fall out of the exception path.

**Q: How would you tune a circuit breaker's thresholds for a new dependency you don't have much production data on yet?**
A: Start conservative and let real traffic inform tuning rather than
guessing precisely up front: a moderate failure-rate threshold (e.g.,
50% over a reasonable sliding window — count-based if traffic is steady,
time-based if it's bursty), a cooldown/wait-in-open duration roughly
matched to the dependency's typical recovery time if known (or a
reasonable default like 30–60s otherwise), and a small number of trial
calls in half-open rather than immediately flooding back to full traffic.
I'd instrument the breaker's state transitions and failure rates from day
one (§10 in the AI Knowledge guide's monitoring philosophy applies
equally here) so the thresholds can be tightened or loosened based on
observed behavior rather than left as an initial guess indefinitely.

---

## 6. Saga Pattern

**What it is:** A way to manage data consistency across a sequence of
operations spanning multiple services/databases — where a single ACID
transaction isn't possible — by breaking the sequence into a series of
local transactions, each with a defined **compensating action** that
undoes it if a later step in the sequence fails.

**Concepts to know:**

- **Why it exists**: distributed transactions (two-phase commit, 2PC)
  across microservices are slow, hold locks across service boundaries
  (blocking), and don't tolerate partial unavailability well — 2PC is
  generally avoided in modern microservice architectures for exactly the
  reasons distributed systems favor availability and loose coupling. Sagas
  trade strong atomicity for eventual consistency achieved through
  compensation instead of rollback.
- **Two coordination styles**:
  - **Choreography**: each service publishes an event when its local
    transaction completes; the next service(s) react to that event and
    perform their own step, publishing their own completion/failure event
    in turn. No central coordinator — fully decentralized, natural fit
    with event-driven architecture (§8) and Kafka. Simpler for a small
    number of steps; gets hard to reason about ("what's the actual
    end-to-end flow?") as the number of participating services grows,
    since the sequence is implicit in who-listens-to-what rather than
    written down anywhere.
  - **Orchestration**: a central saga orchestrator explicitly calls each
    step in sequence and explicitly triggers compensations on failure.
    Easier to reason about and observe (the flow is one place, one piece
    of code/config) at the cost of a new central component and a
    potential coupling point.
- **Compensating actions are not automatic rollbacks** — they must be
  explicitly designed per step, and they're **semantic undos**, not
  literal ones: "cancel the reservation" / "refund the payment" /
  "release the inventory hold," not a database-level rollback, because
  the original transaction already committed locally and may have had
  externally visible effects (an email already sent can't be "rolled
  back," only compensated with a follow-up correction).
- **Sagas are not isolated** — mid-saga, other transactions can observe
  partial/intermediate state (order shows "processing" while payment is
  still pending) — this is a real trade-off to name: sagas give up the
  "I" (isolation) of ACID, not just the atomicity, and application design
  needs to account for readers seeing in-progress states.
- **Idempotency (§3) is required for every saga step and every
  compensation** — the same delivery-guarantee realities apply (a step or
  its compensation might be triggered more than once).

### Interview Q&A

**Q: Design the saga for an order-checkout flow spanning Order, Payment, and Inventory services.**
A: Choreography-based, using events (this composes naturally with the
event-driven architecture and Kafka topics from the other guides): Order
service creates the order in a `pending` state and publishes
`OrderCreated`. Inventory service consumes it, attempts to reserve stock;
on success publishes `InventoryReserved`, on failure publishes
`InventoryReservationFailed`. Payment service consumes `InventoryReserved`,
attempts to charge; on success publishes `PaymentCompleted` (Order service
marks the order `confirmed`), on failure publishes `PaymentFailed`. Any
failure event triggers the compensating action for prior completed steps
— `PaymentFailed` triggers Inventory service to release its reservation
(`InventoryReleased`), and Order service marks the order `cancelled`. Every
step and compensation is idempotent (§3), keyed by order ID, since events
can be redelivered. I'd choose choreography here because it's only three
participants with a fairly linear flow; I'd switch to an orchestrator if
this grew to five-plus steps with conditional branching, where an
implicit event-chain becomes hard to audit and debug.

**Q: What's the trade-off between choreography and orchestration sagas, concretely?**
A: Choreography is more decoupled — each service only needs to know what
events to react to, not who else is involved — which fits naturally with
an event-driven architecture and scales well as long as the flow stays
relatively simple. Its cost is observability and debuggability: there's no
single place that shows "here's the whole checkout flow," you have to
trace events across services to reconstruct what happened, which gets
genuinely hard past a handful of steps or with conditional/branching
logic. Orchestration centralizes the flow in one component, making it
much easier to see, test, and modify the sequence, and easier to add
timeout/retry logic per step in one place — at the cost of that
orchestrator becoming a component every participating service now
implicitly depends on, and a potential single point of coordination
complexity. I'd default to choreography for simple, linear, small-N-step
sagas and move to orchestration once the flow has real branching or
enough steps that the implicit choreography becomes hard to reason about.

**Q: A saga's compensating action itself fails. What now?**
A: This is the sharp edge of the pattern worth naming unprompted — sagas
assume compensations succeed, but they're just more distributed calls
subject to the same failure modes as anything else. Practically: retry
the compensation with the same backoff/idempotency discipline as any
other operation (§3, §4); if it still can't succeed, this needs to escalate
to a human/operational alert rather than silently leaving the system in
an inconsistent state — e.g., a stuck "cancelling" order that never
actually released its inventory hold. Some systems maintain a
"compensation dead-letter" queue analogous to a Kafka DLQ (Kafka guide
§5) specifically for this — failed compensations are rare enough that
manual intervention is an acceptable fallback, as long as it's visible
and alerted on rather than silently swallowed.

---

## 7. Outbox Pattern

**What it is:** A way to atomically combine a database write with
publishing an event about that write, when the database and the message
broker are two separate systems that can't share a transaction — solving
the classic **dual-write problem**.

**The problem it solves:**

Naively, "update the DB, then publish a Kafka event" is two independent
operations. If the process crashes between them (or the publish fails),
you get the DB updated with **no** corresponding event ever published —
silent inconsistency downstream (in the AI/RAG design, a document could
be updated in the metadata store but the `doc-events` message never
published, so the vector index never gets updated — an invisible,
hard-to-detect bug).

**How it works:**

```
Single local DB transaction:
  1. Write the actual business change (e.g., UPDATE documents SET ... )
  2. Write a row to an "outbox" table in the SAME database, SAME
     transaction — representing the event to be published
                    │
                    ▼ (committed atomically together — this is the trick:
                       it's a single-database transaction, not a
                       cross-system one)
                    │
       Separate async process reads the outbox table
       (polling, or CDC via Debezium reading the DB's WAL/binlog)
                    │
                    ▼
       Publishes the event to Kafka, then marks/deletes the outbox row
```

**Concepts to know:**

- **The core trick**: because both the business write and the outbox row
  are in the *same* database transaction, they're atomic with each other
  using ordinary ACID guarantees — no distributed transaction needed. The
  cross-system hand-off (DB → Kafka) is moved to a separate, async,
  retriable step instead of being inline with the original transaction.
- **Two implementation styles**:
  - **Polling publisher**: a background job periodically queries the
    outbox table for unpublished rows, publishes them, marks them
    published. Simple to build, adds polling latency, adds load to the DB.
  - **CDC-based (Debezium + Kafka Connect)**: reads the database's
    write-ahead log directly and streams outbox inserts to Kafka near
    real-time, without polling the table. Lower latency, no extra DB
    query load, but a heavier piece of infrastructure to operate.
- **The publish-to-Kafka step must be idempotent on the consumer side**
  (§3) regardless of style — the outbox guarantees the event is
  published **at least once** (a crash after publish but before marking
  the outbox row processed causes a republish on recovery), not exactly
  once. This is the same at-least-once reality as everywhere else in this
  guide — outbox solves the dual-write/silent-loss problem, not the
  duplicate-delivery problem, which is why it's typically paired with
  idempotent consumers, not treated as a standalone fix.
- **Where it applies in the AI/RAG design**: this is precisely how a
  source connector or admin-upload path should publish to `doc-events` —
  write the document metadata row and an outbox event row in one Postgres
  transaction, rather than writing to Postgres and then separately
  calling the Kafka producer, which has exactly the crash-window gap the
  outbox pattern closes.

### Interview Q&A

**Q: Why not just publish to Kafka right after committing the database write?**
A: Because that's two separate operations with a gap between them where
things can go wrong: the process could crash after the DB commit but
before the Kafka publish succeeds (event silently never sent — the more
dangerous failure, since nothing errors, the data is just quietly
inconsistent), or the Kafka publish could succeed but the enclosing
request could then fail and roll back logic that assumed both happened
together. There's no way to make "commit to Postgres" and "publish to
Kafka" atomic across two different systems directly. The outbox pattern
sidesteps this by writing the event as a row in the *same* database
transaction as the business change — atomicity is achieved using
ordinary single-database ACID guarantees — and handling the actual
cross-system publish as a separate, retriable, monitorable step.

**Q: Compare polling-based outbox publishing to CDC-based (Debezium). When would you choose each?**
A: Polling is simpler to build and reason about — a scheduled job, no new
infrastructure — but it adds latency (bounded by poll interval) and
periodic query load on the outbox table, and needs care to avoid
publishing the same row twice if the "mark as published" step isn't
handled carefully under concurrent pollers. CDC via Debezium reads the
database's replication log directly, so it's near-real-time with no
polling overhead, but it's a genuinely heavier piece of infrastructure —
Kafka Connect, Debezium connectors, log-based replication configured
correctly on the source DB — that needs its own operational ownership. For
the AI/RAG ingestion pipeline's freshness requirement ("minutes, not
hours"), a modest polling interval (a few seconds) is probably sufficient
and much simpler to operate; I'd reach for Debezium/CDC specifically if
the freshness bar tightened to near-real-time or the polling load became
a measurable problem on the primary database.

**Q: Does the outbox pattern guarantee exactly-once event delivery?**
A: No — it guarantees **at-least-once**, specifically that an event is
never silently lost (the dual-write gap is closed), but a crash between
"publish to Kafka succeeded" and "mark the outbox row processed" causes
that event to be republished on recovery. This is the same at-least-once
reality that shows up everywhere in distributed messaging, so outbox is
always paired with idempotent consumers on the receiving side — exactly
the `(source_system, source_id, content_hash)` idempotent upsert pattern
from the AI/RAG ingestion pipeline. The outbox pattern's job is solving
silent loss, not solving duplication; duplication is solved at the
consumer, the same way it's solved for any other at-least-once Kafka
consumer.

---

## 8. Event-Driven Architecture

**What it is:** An architectural style where services communicate by
producing and consuming **events** (facts that something happened) via an
asynchronous broker, rather than by calling each other directly via
synchronous request/response — decoupling producers from consumers in
time, location, and cardinality.

**Concepts to know:**

- **What it actually decouples**: a producer doesn't need to know who
  consumes an event, how many consumers there are, or whether they're
  currently available — it publishes and moves on. This is qualitatively
  different from a synchronous call, which fails immediately if the
  callee is down and requires the caller to know the callee's location.
  In the Kafka guide's terms, this is exactly what multiple independent
  consumer groups on `doc-events` give you for free.
- **Event notification vs. event-carried state transfer**:
  - **Event notification**: a lightweight "something happened, here's the
    ID" event; consumers call back to the source system for full details
    if needed. Smaller events, but couples consumers to the source
    system's availability for follow-up reads.
  - **Event-carried state transfer**: the event itself carries the full
    (or sufficient) state of what changed, so consumers don't need to call
    back at all — fully decoupled at read time, at the cost of larger
    events and needing to think about what happens if the event schema
    needs to evolve (this is where schema registry, Kafka guide §7,
    becomes essential rather than optional).
  - The `doc-events` topic in the AI/RAG design is closer to
    event-carried state transfer — it carries enough about the document
    change that ingestion workers don't need to call back to Confluence
    synchronously for every event.
- **Trade-offs vs. synchronous/request-response**:
  - Gains: temporal decoupling (consumer can be down, catches up later),
    natural buffering against load spikes, easy fan-out to new consumers
    without touching the producer, replayability (new consumers can
    process history from an earlier offset).
  - Costs: eventual consistency by default (no immediate confirmation
    that a consumer processed something), harder to trace a request
    end-to-end (distributed tracing across async boundaries needs
    deliberate propagation of trace context through event headers),
    harder to reason about the overall system flow (this is the same
    choreography-vs-orchestration debuggability trade-off from §6, at
    architecture scale).
- **Not a universal replacement for synchronous calls** — a user waiting
  for a page to load a specific answer needs synchronous request/response
  (this is exactly why the AI/RAG design's query path, per that guide's
  §7, is explicitly *not* Kafka-mediated, only the ingestion path is).
  Recognizing where synchronous is still correct is as important as
  knowing when to reach for events.

### Interview Q&A

**Q: When would you choose event-driven communication over a direct synchronous API call between two services?**
A: When the caller doesn't need an immediate answer to proceed, when
there could be multiple current or future consumers of the same fact
(fan-out), when you want the caller to remain available/fast even if the
consumer is temporarily down or slow, or when you want replayability
(reprocessing history, e.g., after fixing a bug in a consumer). I would
not reach for it when a user is synchronously waiting on the result — the
query path of the AI/RAG assistant is a good negative example: it needs
an immediate streamed answer, so it's a direct request/response
(SSE-streamed) call, not routed through Kafka, even though the ingestion
side of the exact same system is fully event-driven. The decision is per
interaction, not an architecture-wide default.

**Q: What does event-driven architecture cost you that a synchronous call graph doesn't?**
A: Primarily observability and reasoning about correctness. In a
synchronous call chain, a failure is immediately visible to the caller
and the call stack shows you the flow. In an event-driven system, a
consumer failing to process an event doesn't surface anywhere near the
producer — you need deliberate tracing (propagating a correlation/trace
ID through event metadata) and monitoring (consumer lag, DLQ volume, per
the Kafka guide) to even notice, let alone debug. It also means embracing
eventual consistency as the default state of the system rather than an
exception, which has to be reflected in product/UX decisions (e.g., "your
document may take a minute to become searchable" needs to be an accepted,
designed-for behavior, not treated as a bug).

**Q: How do event notification and event-carried state transfer differ, and which did you use in the AI/RAG ingestion design?**
A: Event notification publishes a minimal "this changed, here's the ID" —
consumers call back to the source for details, which keeps events small
but re-couples consumers to the source system's availability at
processing time. Event-carried state transfer includes the actual changed
data (or enough of it) in the event itself, so consumers are fully
self-sufficient — no callback needed — at the cost of larger events and a
real schema-evolution story (Kafka guide §7) since the event *is* the
contract now, not just a pointer to one. The `doc-events` topic leans
toward carrying enough state (document metadata, what changed) that
ingestion workers aren't forced to synchronously call back into
Confluence's API for every single event, which would reintroduce exactly
the kind of tight coupling and availability dependency the event-driven
design was meant to remove.

---

## 9. How These Patterns Compose

Interviewers reward seeing these as **one coherent toolkit**, not eight
unrelated flashcards. A quick map of how they stack, using the AI/RAG
ingestion pipeline as the through-line:

```
CAP / Consistency Models  →  the FRAMING: decide, per-operation, what
                              guarantee you actually need (strong for
                              ACLs, eventual for index freshness)
        │
        ▼
Event-Driven Architecture →  the STRUCTURE: decouple ingestion from
                              query via async events (Kafka `doc-events`)
        │
        ▼
Outbox Pattern            →  the RELIABILITY of *publishing* those events
                              without silent loss (atomic w/ the DB write)
        │
        ▼
Idempotency               →  the RELIABILITY of *consuming* those events
                              safely under at-least-once delivery
        │
        ▼
Retry Strategies           →  what a consumer does on a TRANSIENT failure
  + Circuit Breaker             (backoff+jitter) vs. a SUSTAINED outage
                                (stop calling, fail fast, recover gradually)
        │
        ▼
Saga Pattern               →  when the work spans MULTIPLE services/steps
                              with no shared transaction, compensate
                              instead of rollback on failure
```

Being able to draw this dependency chain, unprompted, in an interview is
a stronger signal than defining any single box in isolation.

---

## 10. Interview Q&A (Cross-Cutting)

**Q: A colleague says "let's just use a distributed transaction across the Order and Inventory databases instead of dealing with sagas." How do you respond?**
A: I'd push back, but explain why rather than just asserting it. A
distributed transaction (2PC) requires both databases to participate in a
coordinated commit protocol — holding locks across both systems for the
duration, blocking other transactions, and requiring both systems to be
available and reachable simultaneously for the whole operation to
succeed. That directly trades away availability (CAP, §1) for atomicity,
at a scale (cross-service, cross-database) where availability usually
matters more, and it tightly couples the two services' operational
availability together, which undermines a big part of why they're
separate services in the first place. A saga trades strict atomicity for
eventual consistency achieved via compensation — more application code to
write (each step's compensation), but no cross-system locking, each
service stays independently available, and it fits naturally with the
event-driven, at-least-once-delivery reality the rest of the system
already lives in.

**Q: Everything in this guide assumes failures happen. How do you decide how much resilience engineering (retries, circuit breakers, sagas, outbox) a given piece of code actually needs?**
A: Match the investment to the cost of getting it wrong and the
likelihood of the failure, not a blanket "always add every pattern."
A low-stakes, easily-retried read from a cache doesn't need a saga or
outbox — a simple timeout and retry is enough. A payment or inventory
operation spanning services needs the full treatment (idempotency, saga,
careful compensation design) because the cost of silent inconsistency is
high and hard to detect after the fact. I'd ask, concretely: what happens
if this operation partially fails and nobody notices for a day — if the
answer is "nothing bad, it self-corrects," lighter-weight handling is
fine; if the answer is "a customer got double-charged" or "a security
boundary was silently violated," it earns the full resilience-pattern
investment. This mirrors the CAP discussion — the right answer is always
per-operation judgment, not a system-wide default applied uniformly.

**Q: How would you explain to a non-technical stakeholder why "the document search says it might take a minute to update" isn't a bug?**
A: I'd frame it in terms of the trade-off it buys them: keeping the
document search fast and always-available for every question means it
can't also guarantee instant awareness of every edit happening anywhere
in the company at the same moment — those two properties are in real
tension in any system operating at this scale, not a limitation specific
to our implementation. We deliberately chose "fast and available, with a
short, bounded catch-up window for edits" over "always perfectly
up-to-the-second, but slower or occasionally unavailable" — because a
minute of lag on a wiki edit is a non-issue for how people actually use
it, while the alternative would mean slower answers, or degraded search
availability, on every single query. I'd tie it back to the concrete
NFR from the design (freshness target: minutes, not seconds) so it's
clear it's an intentional, bounded commitment, not an open-ended
excuse.

---

*This completes the requested guide set: AI Knowledge, AI/RAG Assistant
System Design, Kafka, and Distributed Systems.*
