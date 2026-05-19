# ADR-009: Use Spring AI's PgVectorStore Canonical Schema for `route_metrics`

**Status:** Accepted
**Date:** 2026-05-19

## Context
`REQUIREMENTS.md` §8 specifies a denormalized `route_metrics` table — `psp_id`, `route_id`, `embedding`, `metrics_json` — with `(psp_id, route_id)` as the primary key. During Iter 4b.2, `RouteMetricsRetriever` was wired to Spring AI's `PgVectorStore.similaritySearch`, which returns `Document` objects whose `metadata` map is what `RouteCandidate` consumes. Spring AI's `PgVectorStore` reads and writes a fixed canonical schema — `id UUID`, `content TEXT`, `metadata JSONB`, `embedding VECTOR(N)` — and its autoconfiguration owns the table. Mapping between the canonical shape and the `REQUIREMENTS.md` shape would require a hand-written DAO that Spring AI's autoconfig would not own, plus its own HNSW index management.

## Decision
We use Spring AI's canonical `PgVectorStore` schema for `route_metrics` and pack `pspId`, `routeId`, `expectedSuccessRate`, `expectedCostBps`, `sampleSize`, `observedAt`, and `notes` into the `metadata` JSONB column. V2 (`services/orchestrator/src/main/resources/db/migration/V2__route_metrics_voyage_dims.sql`) drops V1's placeholder table — empty in every environment — and creates `route_metrics(id UUID PRIMARY KEY, content TEXT, metadata JSONB, embedding VECTOR(512))` with an HNSW index using `vector_cosine_ops`. The 512 dims match voyage-3-lite.

## Consequences
- `PgVectorStore` autoconfig owns the table directly; no custom DAO, no separate index lifecycle.
- `Document` → `RouteCandidate` is a 1:1 metadata-map projection — the retrieval path is ~10 lines.
- Filtering by `metadata.pspId` uses JSONB operators when needed; `similaritySearch` remains the primary access pattern.
- The composite primary key from `REQUIREMENTS.md` §8 is gone — uniqueness on `(pspId, routeId)` is now an ingestion-time invariant, not a database constraint.
- `REQUIREMENTS.md` §8 documents intent; the actual schema diverges. This ADR is where that divergence lives.

## Alternatives considered
- **Hand-roll a DAO over the `REQUIREMENTS.md` schema and skip `PgVectorStore`** — the reviewer's default. Rejected: re-implements `similaritySearch`, HNSW index management, and the `Document` abstraction Spring AI already provides on the pinned stack.
- **Two tables — one canonical for the vector store, one denormalized for queries** — rejected: write amplification and sync risk for a pet project with one writer and no measured query hot spot on the denormalized columns.
- **Keep V1's schema and patch the vector dimensions in place** — rejected: would require pgvector-aware column-type migration we don't want to hand-write, and still leaves the column-set mismatch.

## When to revisit
When `metadata.pspId` (or any metadata key) queries become a hot path — denormalize a covering index, or split into a second table at that point, not before.

Related: [[001-stable-stack-baseline]].
