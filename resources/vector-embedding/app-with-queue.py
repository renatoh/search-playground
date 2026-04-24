import asyncio
import time
from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
import torch

app = FastAPI()
device = "cuda"
torch.cuda.set_device(0)
model = SentenceTransformer("WhereIsAI/UAE-Large-V1", device=device)

MAX_BATCH_SIZE =40
MAX_WAIT_MS = 100  # how long to wait collecting a batch

queue: asyncio.Queue = asyncio.Queue()

async def batch_worker():
    while True:
        text, fut = await queue.get()
        batch_texts = [text]
        batch_futs = [fut]
        # Drain more items up to MAX_BATCH_SIZE, waiting at most MAX_WAIT_MS
        deadline = time.monotonic() + MAX_WAIT_MS / 1000
        while len(batch_texts) < MAX_BATCH_SIZE:
            timeout = deadline - time.monotonic()
            if timeout <= 0:
                break
            try:
                t, f = await asyncio.wait_for(queue.get(), timeout=timeout)
                batch_texts.append(t)
                batch_futs.append(f)
            except asyncio.TimeoutError:
                break

        try:
            embeddings = model.encode(batch_texts, convert_to_tensor=True, device=device)
            embeddings = torch.nn.functional.normalize(embeddings, p=2, dim=1)
            results = embeddings.cpu().tolist()
            for f, r in zip(batch_futs, results):
                f.set_result(r)
        except Exception as e:
            for f in batch_futs:
                f.set_exception(e)

@app.on_event("startup")
async def startup():
    asyncio.create_task(batch_worker())

class TextInput(BaseModel):
    inputs: str

@app.post("/embed")
async def embed_text(input: TextInput):
    fut = asyncio.get_event_loop().create_future()
    await queue.put((input.inputs, fut))
    return await fut

@app.post("/embed-opensearch")
async def embed_text_opensearch(input: TextInput):
    vector = await embed_text(input)
    return {
        "inference_results": [{
            "output": [{
                "name": "sentence_embedding",
                "data_type": "FLOAT32",
                "shape": [len(vector)],
                "data": vector,
            }]
        }]
    }
