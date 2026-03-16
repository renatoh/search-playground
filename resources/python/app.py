import json
import logging
import os

import httpx
from fastapi import FastAPI
from openai import OpenAI
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)

MODEL_NAME = os.getenv("MODEL_NAME", "gpt-4o")
SOLR_URL = os.getenv("SOLR_URL", "http://linux:8983/solr")
SOLR_COLLECTION = os.getenv("SOLR_COLLECTION", "products")

app = FastAPI()
client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "search_products",
            "description": "Search the product catalog and return matching products.",
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
        "rows": 50,
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
    products: list[dict] = []


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
                f"You MUST call search_products with a NEW query that combines the original search intent "
                f"with any refinements from the new question. "
                f"Do NOT answer from the conversation above — always search fresh. "
                f"Format each product EXACTLY as: [![Title](image_url)](product_url) - $price"
            )

    messages.append({"role": "user", "content": user_content})

    all_tool_calls: list[dict] = []
    all_products: list[dict] = []
    logger.info(f"Search request: query='{req.query}', max_iterations={req.max_iterations}")

    for iteration in range(1, req.max_iterations + 1):
        logger.info(f"--- Iteration {iteration} ---")

        response = client.chat.completions.create(
            model=MODEL_NAME,
            messages=messages,
            tools=TOOLS,
            tool_choice="auto",
        )

        message = response.choices[0].message
        logger.info(f"=== LLM OUTPUT ===\ncontent: {message.content}\ntool_calls: {message.tool_calls}\n=== END ===")

        if not message.tool_calls:
            logger.info(f"LLM gave final answer (iteration {iteration})")
            answer = message.content or ""
            shown_products = [p for p in all_products if p["title"] in answer]
            logger.info(f"Products shown to customer: {len(shown_products)} out of {len(all_products)}")
            return SearchResponse(
                answer=answer,
                tool_calls=all_tool_calls,
                iterations=iteration,
                products=shown_products,
            )

        messages.append(message)

        for tc in message.tool_calls:
            fn_name = tc.function.name
            fn_args = json.loads(tc.function.arguments)
            logger.info(f"Calling tool: {fn_name}({fn_args})")

            all_tool_calls.append({"name": fn_name, "arguments": fn_args})

            result = TOOL_HANDLERS[fn_name](fn_args)
            all_products.extend(
                {"title": p["title"], "code": p["code"], "image_url": p["image_url"], "product_url": p["product_url"]}
                for p in result
            )
            logger.info(f"=== TOOL RESULT ({fn_name}) ===\n{json.dumps(result, indent=2)}\n=== END TOOL RESULT ===")

            messages.append({
                "role": "tool",
                "tool_call_id": tc.id,
                "content": json.dumps(result),
            })

    logger.info(f"Max iterations ({req.max_iterations}) reached")
    return SearchResponse(
        answer="Max iterations reached without a final answer.",
        tool_calls=all_tool_calls,
        iterations=req.max_iterations,
        products=all_products,
    )
