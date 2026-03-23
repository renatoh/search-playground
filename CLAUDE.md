# CLAUDE.md

## Guidelines

Do not change code unless without my explicit OK

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build the Kotlin project
./gradlew build

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "opensearch.integration.CreateLinkDocs"

# Start SolrCloud + ZooKeeper via Docker
docker compose -f resources/docker-compose.yml up -d

# Run the Python agentic search service (port 8080)
cd resources/python
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 8080

# Run the cross-encoder reranker service (port 8000)
cd reranker
uvicorn sllawlis-app:app --host 0.0.0.0 --port 8000
```

Main Kotlin entry points are run directly from the IDE (they have `main()` functions):
- `ProductTileApp.kt` — JavaFX desktop search UI
- `agentic/AgenticSearchWeb.kt` — Ktor web chat UI (port 9090)
- `sampleproducts/ParseAndIndexSampleProducts.kt` — CSV ingestion into Solr/OpenSearch

## Architecture

This is a search relevance playground comparing Solr and OpenSearch backends with optional reranking.

### Kotlin/Gradle project (JVM 21)
The project uses Kotlin with JavaFX, Ktor (server + client), OpenSearch Java client, SolrJ, and Jackson.

**`ProductTileApp.kt`** — JavaFX desktop app with a search bar and product tile grid. Toggle between Solr/OpenSearch backends, lexical vs. hybrid search, and cross-encoder reranking. Results display lexical and vector scores per tile.

**`agentic/AgenticSearchWeb.kt`** — Ktor web server providing a WhatsApp-style chat UI. It proxies queries to the Python agentic search service at `http://linux:8080/search`, maintaining per-session conversation history via cookies.

**`sampleproducts/ParseAndIndexSampleProducts.kt`** — Parses an Amazon products CSV and bulk-indexes into Solr or OpenSearch. The `Product` data class (shared across the app) maps Solr field naming conventions (`title_txt_en`, `code_s`, `price_d`, etc.).

**`solr/integration/SolrIntegration.kt`** — Solr search client with two modes:
- `searchUserInputAnd()` — plain edismax lexical search
- `hybridSearchUserInputAnd()` — hybrid search combining lexical + KNN vector via `{!knn_text_to_vector f=vector_en model=customLocal}`

**`opensearch/integration/OpenSearchIntegration.kt`** — OpenSearch client using the opensearch-java SDK, connecting to `localhost:9200`.

**`rerank/ReRankService.kt`** — HTTP client that POSTs product titles to the Python reranker at `http://localhost:8000/rank` and re-orders products by score.

### Python services

**`resources/python/app.py`** — FastAPI agentic search service using a local Qwen2.5-7B-Instruct model. Implements a tool-calling agentic loop: the LLM calls `search_products` (which queries Solr with hybrid search) until it has enough data to answer. Exposes `POST /search`.

**`reranker/sllawlis-app.py`** — FastAPI cross-encoder reranker using `sllawlis/all-distilroberta-ce-esci`. Scores query-document pairs using ESCI labels (Exact/Substitute/Complement/Irrelevant) converted to a weighted relevance score. Exposes `/rank`, `/score`, and `/raw`.

### Infrastructure
- **Solr**: SolrCloud with 2 nodes (`solr1` on port 8983, `solr2` on 8984) + ZooKeeper. Hardcoded to `http://linux:8983/solr/` in Kotlin code.
- **OpenSearch**: Expected at `localhost:9200`.
- **Solr collections**: `products` (base), `products-head` (with custom trained vector model head), `products-head-original`.
- **Vector model**: `customLocal` registered in Solr, used for `{!knn_text_to_vector}` queries.
