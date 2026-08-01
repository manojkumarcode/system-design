# Engineering Interview Handbook

A structured, in-depth reference for senior/staff/principal engineering
and engineering-leadership interviews — organized as numbered chapters so
each topic can be studied, referenced, and extended independently.

## Structure

```
engineering-interview-handbook/
├── assets/                     # Shared diagrams, images, and cheat sheets
├── 01-java-and-concurrency/     # Java internals & concurrency track
│   └── chapter-01-thread-pools/  # Thread pool implementation, in depth
├── 04-distributed-systems/      # Distributed systems track
│   └── chapter-08-multi-tenancy/ # Multi-tenant architecture, in depth
└── 09-ai-engineering/            # AI/LLM engineering track
    └── chapter-01-rag/           # Retrieval-Augmented Generation, in depth
```

Numbering is deliberately non-contiguous at the top level (`04-`, `09-`)
to leave room for other tracks (system design, Java/Spring internals,
cloud, leadership) to be slotted in at their own numbers as the handbook
grows. Chapter numbers within a track are similarly non-contiguous where
a chapter was built ahead of its predecessors (e.g., Multi-Tenancy
shipped as Chapter 08 of the Distributed Systems track before Chapters
01–07 were migrated into this structure).

## Tracks

| # | Track | Status |
|---|---|---|
| 01 | [Java & Concurrency](01-java-and-concurrency/README.md) | Chapter 1 (Thread Pools) complete |
| 04 | [Distributed Systems](04-distributed-systems/README.md) | Chapter 8 (Multi-Tenancy) in progress |
| 09 | [AI Engineering](09-ai-engineering/README.md) | Chapter 1 (RAG) complete — 15/15 chapters |

## Related guides

These standalone guides live at the repo root and complement this
handbook — same subject matter, different format (single-file,
Q&A-dense, interview-transcript style rather than chaptered reference):

- `AI_Knowledge_Interview_Guide.md`
- `AI_RAG_Assistant_System_Design_Guide.md`
- `Kafka_Interview_Guide.md`
- `Distributed_Systems_Interview_Guide.md`
