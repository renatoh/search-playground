from fastapi import FastAPI
from pydantic import BaseModel
from typing import List
from sentence_transformers import CrossEncoder
import uvicorn
import time
import torch
import numpy as np

app = FastAPI(
    title="Cross-Encoder ESCI API",
    description="API for sllawlis/all-distilroberta-ce-esci",
    version="1.0.0",
)

# -------------------------------------------------------
# Load the pre-trained CrossEncoder model once at startup
# -------------------------------------------------------

# ESCI cross-encoder: outputs 4 logits: [E, S, C, I]
# E = Exact, S = Substitute, C = Complement, I = Irrelevant
model_name = "sllawlis/all-distilroberta-ce-esci"
model = CrossEncoder(model_name)


# -------------------------------------------------------
# Helper: convert ESCI logits -> scalar relevance score
# -------------------------------------------------------

def escilogits_to_score(logits: np.ndarray) -> np.ndarray:
    """
    Convert ESCI logits (N x 4) into a single relevance score per row.

    Strategy:
      1) softmax over classes -> probabilities
      2) weighted sum over classes with weights: [3, 2, 1, 0]
         i.e. Exact = 3, Substitute = 2, Complement = 1, Irrelevant = 0
    """
    # logits: shape (N, 4)
    logits_t = torch.tensor(logits)
    probs = torch.softmax(logits_t, dim=1)  # (N, 4)

    # weights for [E, S, C, I]
    weights = torch.tensor([3.0, 2.0, 1.0, 0.0])

    scores = (probs * weights).sum(dim=1)  # (N,)
    return scores.detach().cpu().numpy()


# -------------------------------------------------------
# Pydantic models
# -------------------------------------------------------

class SentencePair(BaseModel):
    sentence1: str
    sentence2: str


class BatchRequest(BaseModel):
    pairs: List[SentencePair]


class RankRequest(BaseModel):
    query: str
    documents: List[str]


# -------------------------------------------------------
# /score endpoint – scalar relevance score per pair
# -------------------------------------------------------

@app.post("/score")
def score(request: BatchRequest):
    """
    Given a list of (sentence1, sentence2) pairs, return a scalar relevance
    score per pair derived from the ESCI logits.
    """
    pair_list = [(p.sentence1, p.sentence2) for p in request.pairs]

    # logits: shape (N, 4)
    logits = model.predict(pair_list)
    scores = escilogits_to_score(logits)

    return {
        "scores": scores.tolist()
    }


# -------------------------------------------------------
# /rank endpoint – rank documents for a query
# -------------------------------------------------------

@app.post("/rank")
def rank(request: RankRequest):
    """
    Rank a list of documents by relevance to a query using the ESCI model.

    Returns documents sorted by relevance score (desc).
    """
    start = time.time()

    # Build (query, document_i) pairs
    pairs = [(request.query, doc) for doc in request.documents]

    # logits: shape (N, 4)
    logits = model.predict(pairs)
    scores = escilogits_to_score(logits)

    scored_docs = [
        {
            "index": i,              # original index in request.documents
            "score": float(score),   # JSON-friendly
            "document": doc,
        }
        for i, (doc, score) in enumerate(zip(request.documents, scores))
    ]

    scored_docs.sort(key=lambda x: x["score"], reverse=True)

    end = time.time()
    print(f"[rank] processed {len(request.documents)} docs in {end - start:.4f} seconds")

    return {"results": scored_docs}


# -------------------------------------------------------
# Optional: /raw endpoint – debug logits + probabilities
# -------------------------------------------------------

@app.post("/raw")
def raw_esci_output(request: BatchRequest):
    """
    Debug endpoint: returns raw ESCI logits, probabilities, and predicted class
    for each pair. Useful to inspect what the model is doing.
    """
    pair_list = [(p.sentence1, p.sentence2) for p in request.pairs]

    logits = model.predict(pair_list)         # (N, 4)
    logits_t = torch.tensor(logits)
    probs = torch.softmax(logits_t, dim=1)    # (N, 4)

    probs_np = probs.detach().cpu().numpy()
    pred_classes = probs_np.argmax(axis=1).tolist()  # 0=E, 1=S, 2=C, 3=I

    return {
        "logits": logits.tolist(),
        "probabilities": probs_np.tolist(),
        "predicted_classes": pred_classes,
        "class_mapping": {
            "0": "E (Exact)",
            "1": "S (Substitute)",
            "2": "C (Complement)",
            "3": "I (Irrelevant)",
        },
    }


# -------------------------------------------------------
# Local dev entry point
# -------------------------------------------------------

if __name__ == "__main__":
    # Run with: python app.py
    uvicorn.run("app:app", host="0.0.0.0", port=8000, reload=True)