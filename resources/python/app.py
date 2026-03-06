import json
import logging
import os
import re

import httpx
import torch
from fastapi import FastAPI
from pydantic import BaseModel
from transformers import AutoModelForCausalLM, AutoTokenizer

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)

MODEL_NAME = os.getenv("MODEL_NAME", "Qwen/Qwen2.5-7B-Instruct")
SOLR_URL = os.getenv("SOLR_URL", "http://linux:8983/solr")
SOLR_COLLECTION = os.getenv("SOLR_COLLECTION", "products")

app = FastAPI()

device = "cuda" if torch.cuda.is_available() else "cpu"

print(f"Loading model {MODEL_NAME} on {device} ...")
tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
model = AutoModelForCausalLM.from_pretrained(
    MODEL_NAME,
    torch_dtype=torch.float16,
).to(device)
model.eval()
print("Model loaded.")

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "search_products",
            "description": "Search the product catalog and return matching product titles.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "The search query to find products.",
                    }
                },
                "required": ["query"],
            },
        },
    },
]


# ---- Tool handlers ----

def search_products(query: str) -> list[dict]:
    params = {
        "fl": "*,score,vectorScore:query($vectorQuery),lexicalScore:query($normalisedLexicalQuery)",
        "q": "{!bool filter=$retrievalStage must=$rankingStage}",
        "retrievalStage": "{!bool should=$lexicalQuery should=$vectorQuery}",
        "rankingStage": "{!func}sum(query($normalisedLexicalQuery),query($vectorQuery))",
        "normalisedLexicalQuery": "{!func}scale(query($lexicalQuery),0.1,1)",
        "lexicalQuery": f"{{!type=edismax qf=title_txt_en}}{query}",
        "vectorQuery": f"{{!knn_text_to_vector f=vector_en model=customLocal}}{query}",
        "rows": 10,
        "start": 0,
        "wt": "json",
    }
    logger.info(f"Searching Solr for: {query}")
    resp = httpx.get(f"{SOLR_URL}/{SOLR_COLLECTION}/select", params=params, timeout=30.0)
    resp.raise_for_status()
    docs = resp.json()["response"]["docs"]
    logger.info(f"Solr returned {len(docs)} results")
    return [
        {
            "title": doc.get("title_txt_en", ""),
            "code": doc.get("code_s", ""),
            "price": doc.get("price_d"),
            "score": doc.get("score"),
            "image_url": doc.get("image_url_s", ""),
            "product_url": doc.get("productUrl_s", ""),
        }
        for doc in docs
    ]


TOOL_HANDLERS = {
    "search_products": lambda args: search_products(args["query"]),
}


# ---- Request / Response models ----

class SearchRequest(BaseModel):
    query: str
    max_iterations: int = 5
    history: list[dict] | None = None


class SearchResponse(BaseModel):
    answer: str
    tool_calls: list[dict]
    iterations: int


# ---- LLM generation ----

def generate(messages: list[dict]) -> str:
    text = tokenizer.apply_chat_template(
        messages,
        tools=TOOLS,
        tokenize=False,
        add_generation_prompt=True,
    )
    inputs = tokenizer(text, return_tensors="pt").to(model.device)
    with torch.no_grad():
        output_ids = model.generate(
            **inputs,
            max_new_tokens=2048,
            do_sample=True,
            temperature=0.7,
            top_p=0.9,
        )
    new_tokens = output_ids[0][inputs["input_ids"].shape[1]:]
    decoded = tokenizer.decode(new_tokens, skip_special_tokens=False)
    # Remove trailing special tokens but keep <tool_call> tags
    decoded = decoded.replace("<|im_end|>", "").replace("<|endoftext|>", "")
    return decoded


def parse_tool_calls(text: str) -> list[dict] | None:
    """Parse Qwen-style tool calls from model output."""
    calls = []
    for match in re.finditer(
        r"<tool_call>\s*(\{.*?\})\s*</tool_call>", text, re.DOTALL
    ):
        try:
            call = json.loads(match.group(1))
            calls.append(call)
        except json.JSONDecodeError:
            continue
    return calls if calls else None


# ---- Endpoint ----

@app.post("/search", response_model=SearchResponse)
async def search(req: SearchRequest):
    messages = [
        {
            "role": "system",
            "content": (
                "You are a helpful product-search assistant. "
                "You MUST ALWAYS call the search_products tool to find products. NEVER answer without searching first. "
                "After gathering results, respond with a final answer listing the most relevant products (pick the top 5-10). "
                "For each product you MUST use the EXACT image_url and product_url from the search results. "
                "NEVER make up or modify URLs. Copy them exactly as returned by the tool. "
                "Format each product as:\n"
                "[![Title](image_url)](product_url) - $price"
            ),
        },
    ]

    user_content = req.query
    if req.history:
        context_lines = []
        for msg in req.history:
            role = msg.get("role", "")
            content = msg.get("content", "")
            if role in ("user", "assistant"):
                context_lines.append(f"{role}: {content}")
        if context_lines:
            context = "\n".join(context_lines)
            user_content = (
                f"Previous conversation for context:\n{context}\n\n"
                f"New question: {req.query}\n\n"
                f"You MUST call search_products to answer this. Do NOT answer from the conversation above."
            )

    messages.append({"role": "user", "content": user_content})

    all_tool_calls: list[dict] = []
    logger.info(f"Search request: query='{req.query}', max_iterations={req.max_iterations}")

    for iteration in range(1, req.max_iterations + 1):
        logger.info(f"--- Iteration {iteration} ---")
        logger.info("Generating LLM response...")
        raw = generate(messages)
        tool_calls = parse_tool_calls(raw)

        if not tool_calls:
            logger.info(f"LLM gave final answer (iteration {iteration})")
            return SearchResponse(
                answer=raw.strip(),
                tool_calls=all_tool_calls,
                iterations=iteration,
            )

        logger.info(f"LLM requested {len(tool_calls)} tool call(s)")
        messages.append({"role": "assistant", "content": raw})

        for tc in tool_calls:
            fn_name = tc.get("name")
            fn_args = tc.get("arguments", {})
            logger.info(f"Calling tool: {fn_name}({fn_args})")

            all_tool_calls.append({"name": fn_name, "arguments": fn_args})

            result = TOOL_HANDLERS[fn_name](fn_args)

            messages.append({
                "role": "tool",
                "content": json.dumps(result),
            })

    logger.info(f"Max iterations ({req.max_iterations}) reached")
    return SearchResponse(
        answer=raw.strip() or "Max iterations reached without a final answer.",
        tool_calls=all_tool_calls,
        iterations=req.max_iterations,
    )
