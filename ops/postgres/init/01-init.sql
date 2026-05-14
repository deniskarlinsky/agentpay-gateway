-- pgvector/pgvector:pg16 image already has the extension installed.
-- This script activates it in the agentpay database.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Langfuse gets its own database so migrations don't interfere.
CREATE DATABASE langfuse;
