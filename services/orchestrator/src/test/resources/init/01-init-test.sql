-- Test-only init: pgvector extension is required before Flyway runs V1__init.sql (route_metrics
-- references vector(1536)). The pgvector/pgvector:pg16 image already ships the extension binary;
-- this just activates it in the test database.
CREATE EXTENSION IF NOT EXISTS vector;
