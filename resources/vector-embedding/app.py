from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
import torch
import time

app = FastAPI()

#device = "cuda" if torch.cuda.is_available() else "cpu"

device="cuda"
print(f"device: {device}")

# Force discrete GPU index (0 = 7900 XT in your listing)
torch.cuda.set_device(0)
print("Using GPU:", torch.cuda.get_device_name(0))


model = SentenceTransformer("WhereIsAI/UAE-Large-V1", device=device)


class TextInput(BaseModel):
    inputs: str

@app.post("/embed")
def embed_text(input: TextInput):
    start_time = time.time()

    # Return embedding as a GPU tensor
    embedding = model.encode(
        input.inputs,
        convert_to_tensor=True,   # ensures we get a torch.Tensor, not numpy
        device=device
    )

    # L2 normalize on GPU
    embedding = torch.nn.functional.normalize(embedding, p=2, dim=0)

    print("--- %s seconds ---" % (time.time() - start_time))

    # Move to CPU only to return JSON
    return embedding.cpu().tolist()

@app.post("/embed-opensearch")
def embed_text_opensearch(input: TextInput):
    vector = embed_text(input)
    return {
        "inference_results": [
            {
                "output": [
                    {
                        "name": "sentence_embedding",
                        "data_type": "FLOAT32",
                        "shape": [len(vector)],
                        "data": vector
                    }
                ]
            }
        ]
    }
