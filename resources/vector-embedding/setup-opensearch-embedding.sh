#!/bin/bash
# Setup OpenSearch with automatic vector embedding generation
# Prerequisites:
#   - OpenSearch running on localhost:9200 (with opensearch-ml plugin)
#   - Embedding service running on http://linux:8001 (app.py with /embed-opensearch endpoint)

OPENSEARCH_URL="http://localhost:9200"
EMBEDDING_URL="http://linux:8001/embed-opensearch"

# Step 1: Whitelist connector URLs
curl -X PUT "$OPENSEARCH_URL/_cluster/settings" \
  -H "Content-Type: application/json" \
  -d '{
  "persistent": {
    "plugins.ml_commons.trusted_connector_endpoints_regex": [
      "^https?://.*$"
    ]
  }
}'

# Step 2: Allow private IPs (needed when embedding service is on a local network)
curl -X PUT "$OPENSEARCH_URL/_cluster/settings" \
  -H "Content-Type: application/json" \
  -d '{
  "persistent": {
    "plugins.ml_commons.connector.private_ip_enabled": true
  }
}'

# Step 3: Create connector to embedding service
# Note the connector_id from the response
curl -X POST "$OPENSEARCH_URL/_plugins/_ml/connectors/_create" \
  -H "Content-Type: application/json" \
  -d '{
  "name": "Local Embedding Model",
  "description": "Connector to local embedding service",
  "version": "1",
  "protocol": "http",
  "credential": {
    "key": "none"
  },
  "actions": [
    {
      "action_type": "predict",
      "method": "POST",
      "url": "'"$EMBEDDING_URL"'",
      "headers": {
        "Content-Type": "application/json"
      },
      "request_body": "{ \"inputs\": \"${parameters.inputs}\" }"
    }
  ]
}'
# Response: { "connector_id": "<CONNECTOR_ID>" }

# Step 4: Register model
# Replace <CONNECTOR_ID> with the connector_id from step 3
curl -X POST "$OPENSEARCH_URL/_plugins/_ml/models/_register" \
  -H "Content-Type: application/json" \
  -d '{
  "name": "local-embedding-model",
  "function_name": "remote",
  "connector_id": "<CONNECTOR_ID>",
  "description": "Local 1024-dim embedding model"
}'
# Response: { "model_id": "<MODEL_ID>" }

# Step 5: Deploy model
# Replace <MODEL_ID> with the model_id from step 4
curl -X POST "$OPENSEARCH_URL/_plugins/_ml/models/<MODEL_ID>/_deploy"

# Step 6: Test model
curl -X POST "$OPENSEARCH_URL/_plugins/_ml/models/<MODEL_ID>/_predict" \
  -H "Content-Type: application/json" \
  -d '{
  "parameters": {
    "inputs": "test embedding generation"
  }
}'

# Step 7: Create index with knn_vector field
curl -X PUT "$OPENSEARCH_URL/products" \
  -H "Content-Type: application/json" \
  -d '{
  "settings": {
    "index.knn": true
  },
  "mappings": {
    "properties": {
      "title_txt_en": { "type": "text" },
      "code_s": { "type": "keyword" },
      "price_d": { "type": "double" },
      "category_s": { "type": "keyword" },
      "description_txt_en": { "type": "text" },
      "embedding": {
        "type": "knn_vector",
        "dimension": 1024,
        "method": {
          "name": "hnsw",
          "space_type": "cosinesimil",
          "engine": "lucene"
        }
      }
    }
  }
}'

# Step 8: Create ingest pipeline using ml_inference processor
# Important: use ml_inference (NOT text_embedding) - it allows explicit parameter mapping
# Replace <MODEL_ID> with the model_id from step 4
curl -X PUT "$OPENSEARCH_URL/_ingest/pipeline/product-embedding-pipeline" \
  -H "Content-Type: application/json" \
  -d '{
  "description": "Generate embeddings for products at index time",
  "processors": [
    {
      "ml_inference": {
        "model_id": "<MODEL_ID>",
        "input_map": [
          {
            "inputs": "title_txt_en"
          }
        ],
        "output_map": [
          {
            "embedding": "$.inference_results[0].output[0].data"
          }
        ]
      }
    }
  ]
}'

# Step 9: Set pipeline as default for the products index
curl -X PUT "$OPENSEARCH_URL/products/_settings" \
  -H "Content-Type: application/json" \
  -d '{
  "index.default_pipeline": "product-embedding-pipeline"
}'

# Step 10: Test - index a product and verify embedding is generated
curl -X POST "$OPENSEARCH_URL/products/_doc" \
  -H "Content-Type: application/json" \
  -d '{
  "title_txt_en": "Wireless Bluetooth Headphones",
  "code_s": "WBH-001",
  "price_d": 49.99
}'

# Verify embedding was stored
curl -s "$OPENSEARCH_URL/products/_search" \
  -H "Content-Type: application/json" \
  -d '{
  "query": { "match_all": {} },
  "_source": ["title_txt_en", "embedding"]
}'
