# 01 — Java & Concurrency

Interview preparation track for core Java internals and concurrency —
the hands-on-depth topics that come up alongside system design in
senior/staff/principal loops: JVM internals, garbage collection,
concurrency primitives, and building blocks like thread pools from
first principles.

## Chapters

### [Chapter 01 — Thread Pools](chapter-01-thread-pools/)

How to design a thread pool from scratch — data structures, worker
lifecycle, saturation policy, shutdown — and how that maps onto Java's
real `ThreadPoolExecutor`.

| # | Chapter | Status |
|---|---|---|
| 01 | [Thread Pool Implementation](chapter-01-thread-pools/01-Thread-Pool-Implementation.md) | ✅ Completed |

Future chapters (planned, not yet written): JVM Memory Model, Garbage
Collectors (G1, ZGC), Virtual Threads, `CompletableFuture` and async
composition, Locks and `java.util.concurrent` deep dive.
