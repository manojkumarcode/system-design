# 04 — Distributed Systems

Interview preparation track for core distributed-systems theory and its
application to production system design — CAP theorem, consistency,
idempotency, resilience patterns, and multi-tenancy.

## Chapters

### [Chapter 08 — Multi-Tenant Architecture](chapter-08-multi-tenancy/)

How a single application instance serves many customers (tenants) while
keeping each tenant's data isolated, secure, and independently
scalable — isolation patterns, database design, Spring Boot
implementation, and tenant isolation across Kafka, Redis, and vector
search.

| # | Chapter | Status |
|---|---|---|
| 01 | [Introduction](chapter-08-multi-tenancy/01-Introduction.md) | ✅ Completed |
| 02 | [Tenant Isolation Patterns](chapter-08-multi-tenancy/02-Tenant-Isolation-Patterns.md) | ✅ Completed |
| 03 | [Database Design](chapter-08-multi-tenancy/03-Database-Design.md) | ✅ Completed |
| 04 | [Spring Boot Implementation](chapter-08-multi-tenancy/04-Spring-Boot-Implementation.md) | ✅ Completed |
| 05 | Multi-Tenant Kafka | ⬜ Pending |
| 06 | Multi-Tenant Redis | ⬜ Pending |
| 07 | Multi-Tenant Vector Search | ⬜ Pending |
| 08 | Security and Compliance | ⬜ Pending |
| 09 | Scaling and Migration | ⬜ Pending |
| 10 | Interview Questions | ⬜ Pending |

Chapters 01–07 of this track (CAP, Consistency Models, Idempotency,
Retry Strategies, Circuit Breaker, Saga, Outbox/Event-Driven) exist today
as the standalone `Distributed_Systems_Interview_Guide.md` at the repo
root; folding them into this numbered chapter structure is a planned
follow-up, not yet done — Chapter 08 (Multi-Tenancy) was built directly
into this structure as the first chapter of this track.

## Related guides

- `Distributed_Systems_Interview_Guide.md` (repo root) — CAP theorem,
  consistency models, idempotency, retries, circuit breaker, saga,
  outbox, event-driven architecture.
- `Multi-Tenant-Isolation-in-Vector-Database.md` (repo root) — the
  original short-form interview answer this chapter's Chapter 07 expands
  on in depth.
