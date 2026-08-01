# Multi-Tenant Isolation in a Vector Database (Interview Guide)

## Interview Question

**How would you design multi-tenant isolation in a vector database?**

## Summary

Design for strong tenant isolation using one of three approaches: 1.
Separate namespace/collection per tenant (recommended for most
enterprise SaaS). 2. Shared collection with mandatory server-side
metadata filtering. 3. Dedicated database per tenant for highly
regulated environments.

### Key Principles

-   Never trust tenantId from the client.
-   Extract tenantId from the authenticated JWT/session.
-   Automatically inject tenant filters into every vector search.
-   Support RBAC/ABAC using metadata.
-   Encrypt data, audit access, and monitor queries.

## Comparison

  Strategy               Isolation       Cost Best For
  ---------------------- ----------- -------- -------------------------
  Namespace per tenant   High          Medium Enterprise SaaS
  Shared + metadata      Medium           Low Large multi-tenant SaaS
  Database per tenant    Very High       High Banking, Healthcare

## Interview Closing Answer

For most enterprise SaaS platforms, I would choose separate namespaces
or collections per tenant because they provide strong logical isolation
while remaining operationally manageable. For regulated industries, I
would recommend a dedicated database or cluster per tenant.
