# 02 – RAG Architecture

## Executive Summary

A Retrieval-Augmented Generation (RAG) system improves LLM accuracy by retrieving relevant enterprise knowledge before generation.

## High-Level Architecture

```text
User -> API -> Query Preprocessor -> Embedding Service -> Vector Database -> Retriever -> Prompt Builder -> LLM -> Response
```

## Core Components

### Client
Receives the user's question.

### API Layer
- Authentication
- Authorization
- Rate limiting
- Request validation

### Query Preprocessor
- Normalize queries
- Expand abbreviations
- Add conversation context

### Embedding Service
Converts the query into a vector using models such as OpenAI, Azure OpenAI, BGE, or MiniLM.

### Vector Database
Stores documents, metadata, and embeddings.
Examples: MongoDB Atlas Vector Search, Pinecone, Weaviate, Milvus.

### Retriever
Performs similarity search, metadata filtering, ranking, and Top-K selection.

### Prompt Builder
Combines the system prompt, retrieved context, and user question.

### LLM
Generates the final answer.

### Post-processing
- PII masking
- Citation generation
- Safety checks

## Production Considerations
- Horizontal scaling
- Semantic caching
- Streaming responses
- Circuit breakers
- Observability
- Prompt compression

## Common Interview Questions
1. Walk through the RAG pipeline.
2. Why generate embeddings?
3. Why use metadata filtering?
4. How do you choose Top-K?
5. Where should caching be introduced?

## Principal Engineer Notes
The retriever is often more important than the choice of LLM. High-quality document ingestion, metadata, and retrieval typically improve results more than switching to a larger model.

## Next Chapter
03 – Document Ingestion
