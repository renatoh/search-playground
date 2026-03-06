from fastapi import FastAPI
from pydantic import BaseModel
from typing import List
from sentence_transformers import CrossEncoder
import time
import uvicorn

app = FastAPI()

# Load the pre-trained CrossEncoder model once at startup
model = CrossEncoder("cross-encoder/ms-marco-MiniLM-L-6-v2")


# model = CrossEncoder('cross-encoder/ms-marco-MiniLM-L12-v2')


# ---------- Existing /score endpoint ----------

class SentencePair(BaseModel):
    sentence1: str
    sentence2: str


class BatchRequest(BaseModel):
    pairs: List[SentencePair]


@app.post("/score")
def score(request: BatchRequest):
    # Convert input into list of (s1, s2) tuples
    pair_list = [(p.sentence1, p.sentence2) for p in request.pairs]

    # Model prediction
    scores = model.predict(pair_list)

    # Convert to plain Python types for JSON
    return {"scores": scores.tolist()}


# ---------- New /rank endpoint ----------

class RankRequest(BaseModel):
    query: str
    documents: List[str]


@app.post("/rank")
def rank(request: RankRequest):
    """
    Rank a list of documents by relevance to a query.
    Higher score = more relevant.
    """
    # Build pairs: (query, document_i)
    
    start = time.time()
    pairs = [(request.query, doc) for doc in request.documents]

    # Get scores for each (query, doc) pair
    scores = model.predict(pairs)

    # Attach index + score + document, then sort by score desc
    scored_docs = [
        {
            "index": i,              # original index in input list
            "score": float(score),   # ensure JSON-serializable
            "document": doc,
        }
        for i, (doc, score) in enumerate(zip(request.documents, scores))
    ]

    scored_docs.sort(key=lambda x: x["score"], reverse=True)

    end = time.time()

    print(f"time taken {end - start} seconds")


    return {"results": scored_docs}


# For local debugging; Docker will use CMD in Dockerfile
if __name__ == "__main__":
    uvicorn.run("app:app", host="0.0.0.0", port=8000)