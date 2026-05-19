-- AgentPay Orchestrator schema, Iter 4b.2: replace V1's placeholder route_metrics with the
-- Spring AI PgVectorStore-compatible shape and switch the vector column to 512 dims
-- (voyage-3-lite). Migrating the column type would require pgvector-aware operators we don't
-- want to write by hand; the V1 table is empty in every environment (no seed yet, no production
-- data), so DROP + CREATE is safe.
--
-- Why we change the column set, not just the dimensions: Spring AI's PgVectorStore reads/writes
-- a fixed schema (id uuid, content text, metadata json|jsonb, embedding vector(N)). We map
-- pspId / routeId / expectedSuccessRate / expectedCostBps / sampleSize / observedAt / notes
-- into the metadata JSONB so the autoconfigured store can own this table directly and
-- RouteMetricsRetriever's similaritySearch returns Documents whose metadata maps 1:1 onto
-- RouteCandidate. ADR candidate (Iter 6): document this trade-off if we ever outgrow it.

DROP TABLE IF EXISTS route_metrics;

CREATE TABLE route_metrics (
    id        UUID PRIMARY KEY,
    content   TEXT,
    metadata  JSONB,
    embedding VECTOR(512)
);

-- HNSW + cosine distance: chosen to match application.yml's pgvector store config so the
-- autoconfigured PgVectorStore's similaritySearch uses this index (not a sequential scan).
CREATE INDEX idx_route_metrics_embedding_hnsw
    ON route_metrics
    USING HNSW (embedding vector_cosine_ops);
