# Chapter 01 – Retrieval-Augmented Generation (RAG)

## Executive Summary

Large Language Models (LLMs) do not know your organization's private knowledge unless it is supplied at runtime.

Retrieval-Augmented Generation (RAG) retrieves enterprise knowledge (Jira, Confluence, logs, runbooks, incidents, documentation) and augments the prompt before sending it to the LLM.

## Learning Objectives
- Why LLMs hallucinate
- Why RAG exists
- Enterprise RAG architecture
- Production use cases
- Interview expectations

## The Problem
A public LLM cannot answer questions about your internal production incidents because it has never seen them.

## Traditional Flow
```
User -> LLM -> Answer
```

## RAG Flow
```
User -> Retriever -> Enterprise Knowledge -> Prompt Builder -> LLM -> Answer
```

## Enterprise Example
Workflow failure -> Spring AI -> Embedding -> MongoDB Atlas Vector Search -> Similar incidents -> LLM -> Root cause + Remediation.

## Benefits
- Reduced hallucinations
- Enterprise-specific answers
- Up-to-date knowledge
- No model retraining

## RAG vs Fine-Tuning
|RAG|Fine-Tuning|
|---|---|
|Runtime knowledge|Changes model weights|
|Easy updates|Retraining required|
|Lower cost|Higher cost|

## Interview Questions
1. Why RAG?
2. Why not fine-tuning?
3. Why vector databases?
4. Where do embeddings fit?
5. How do you reduce hallucinations?

## Next Chapter

[02 – RAG Architecture](02-RAG-Architecture.md)
