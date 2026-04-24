package webapp

import agentic.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import opensearch.integration.OpenSearchIntegration
import rerank.ReRankService
import sampleproducts.Product
import solr.integration.SolrIntegration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val mapper = jacksonObjectMapper()
private val solrIntegration = SolrIntegration()
private val openSearchIntegration = OpenSearchIntegration()
private val reRankService = ReRankService()

private val chatSessions = ConcurrentHashMap<String, MutableList<ChatMessage>>()

private fun ApplicationCall.chatSessionId(): String {
    val existing = request.cookies["CHAT_SESSION"]
    if (existing != null && chatSessions.containsKey(existing)) return existing
    val id = UUID.randomUUID().toString()
    response.cookies.append("CHAT_SESSION", id, path = "/")
    chatSessions[id] = mutableListOf()
    return id
}

private fun ApplicationCall.getChatMessages(): MutableList<ChatMessage> {
    val id = chatSessionId()
    return chatSessions.getOrPut(id) { mutableListOf() }
}

fun main() {
    embeddedServer(Netty, port = 9090) {
        routing {

            get("/") {
                call.respondText(buildHtmlPage(), ContentType.Text.Html)
            }

            get("/api/search") {
                val query = call.parameters["q"]?.trim() ?: ""
                val backend = call.parameters["backend"] ?: "solr"
                val hybrid = call.parameters["hybrid"] == "true"
                val trained = call.parameters["trained"] == "true"
                val rerank = call.parameters["rerank"] == "true"

                if (query.isEmpty()) {
                    call.respondText("[]", ContentType.Application.Json)
                    return@get
                }

                var products: List<Product> = if (backend == "solr") {
                    if (hybrid) {
                        val collection = if (trained) "products-head" else "products"
                        solrIntegration.hybridSearchUserInputAnd(query, collection)
                    } else {
                        solrIntegration.searchUserInputAnd(query, "products")
                    }
                } else {
                    openSearchIntegration.searchUserInputAnd(query)
                }

                if (rerank) {
                    products = reRankService.rerankByTitle(query, products).map { it.first }
                }

                val json = mapper.writeValueAsString(products.map { p ->
                    mapOf(
                        "title_txt_en" to p.title_txt_en,
                        "imageUrl_s" to p.imageUrl_s,
                        "price_d" to p.price_d,
                        "code_s" to p.code_s,
                        "lexicalScore" to p.lexicalScore,
                        "vectorScore" to p.vectorScore,
                        "score" to p.score
                    )
                })
                call.respondText(json, ContentType.Application.Json)
            }

            post("/api/chat") {
                val body = call.receiveText()
                val parsed: Map<String, Any?> = mapper.readValue(body, Map::class.java) as Map<String, Any?>
                val query = (parsed["query"] as? String)?.trim() ?: ""

                if (query.isEmpty()) {
                    call.respondText(
                        mapper.writeValueAsString(mapOf("answer" to "", "html" to "")),
                        ContentType.Application.Json
                    )
                    return@post
                }

                val messages = call.getChatMessages()
                messages.add(ChatMessage("user", query))

                try {
                    val response = callAgenticSearch(query, messages.dropLast(1))
                    val stored = if (response.products.isNotEmpty())
                        "${response.answer}\n\n[Products shown: ${response.products}]"
                    else
                        response.answer
                    messages.add(ChatMessage("assistant", stored))

                    val displayContent = response.answer
                    val html = formatChatMessageAsTiles(displayContent)
                    call.respondText(
                        mapper.writeValueAsString(mapOf("answer" to displayContent, "html" to html)),
                        ContentType.Application.Json
                    )
                } catch (e: Exception) {
                    val errorMsg = "Error: ${e.message}"
                    messages.add(ChatMessage("assistant", errorMsg))
                    call.respondText(
                        mapper.writeValueAsString(mapOf("answer" to errorMsg, "html" to escapeHtml(errorMsg))),
                        ContentType.Application.Json
                    )
                }
            }

            post("/api/chat/clear") {
                val id = call.request.cookies["CHAT_SESSION"]
                if (id != null) chatSessions.remove(id)
                call.response.cookies.append("CHAT_SESSION", "", path = "/", maxAge = 0L)
                call.respondText("{}", ContentType.Application.Json)
            }
        }
    }.start(wait = true)
}

private val chatImagePattern = Regex("""\[!\[([^\]]*)\]\(([^)]+)\)\]\(([^)]+)\)(?:\s*-\s*\$[\d.]+)?""")

// Matches numbered product list entries in various formats:
// "1. **BBQ Grill**:\n- - $39.99"  or  "1. - $39.99"  or  "1. BBQ Grill - $39.99"
private val numberedProductPattern = Regex("""(?m)^\d+\.\s+[^\n]*\n?(?:\s*-\s*-?\s*\$[\d.]+\s*\n?)*""")

private fun formatChatMessageAsTiles(text: String): String {
    data class ProductCard(val alt: String, val imgUrl: String, val linkUrl: String)

    val cards = mutableListOf<ProductCard>()
    var cleaned = chatImagePattern.replace(text) { match ->
        cards.add(ProductCard(match.groupValues[1], match.groupValues[2], match.groupValues[3]))
        ""
    }

    if (cards.isNotEmpty()) {
        // Strip the numbered product listing text since tiles replace it
        cleaned = numberedProductPattern.replace(cleaned, "")
    }

    // Collapse excessive blank lines
    cleaned = cleaned.replace(Regex("""\n{3,}"""), "\n\n").trim()

    val textHtml = escapeHtml(cleaned).replace("\n", "<br>")

    if (cards.isEmpty()) return textHtml

    val tilesHtml = cards.joinToString("") { card ->
        """<a href="${card.linkUrl}" target="_blank" class="chat-product-tile">""" +
        """<img src="${card.imgUrl}" alt="${escapeHtml(card.alt)}">""" +
        """<div class="chat-product-title">${escapeHtml(card.alt)}</div></a>"""
    }

    return """$textHtml<div class="chat-product-grid">$tilesHtml</div>"""
}

private fun buildHtmlPage(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Product Search</title>
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f0f2f5; height: 100vh; display: flex; flex-direction: column; overflow: hidden; }

/* Top bar */
.top-bar { background: #fff; padding: 12px 20px; border-bottom: 1px solid #ddd; display: flex; flex-wrap: wrap; align-items: center; gap: 12px; flex-shrink: 0; }
.top-bar label { font-size: 14px; cursor: pointer; white-space: nowrap; }
.top-bar input[type=text] { padding: 8px 14px; border: 1px solid #ccc; border-radius: 6px; font-size: 15px; width: 350px; outline: none; }
.top-bar input[type=text]:focus { border-color: #1a73e8; }
.top-bar button { background: #1a73e8; color: #fff; border: none; padding: 8px 20px; border-radius: 6px; font-size: 15px; cursor: pointer; }
.top-bar button:hover { background: #1557b0; }
.controls { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.controls label { display: flex; align-items: center; gap: 4px; }

/* Product grid */
.product-grid-wrapper { flex: 1; overflow-y: auto; padding: 16px; }
.product-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 14px; }
.product-tile { background: #fff; border: 1px solid #ddd; border-radius: 8px; padding: 12px; display: flex; flex-direction: column; align-items: center; transition: box-shadow 0.2s, border-color 0.2s; }
.product-tile:hover { border-color: #bbb; box-shadow: 0 3px 10px rgba(0,0,0,0.12); }
.product-tile img { width: 150px; height: 150px; object-fit: contain; margin-bottom: 8px; }
.product-tile .title { font-size: 13px; font-weight: 600; text-align: center; margin-bottom: 6px; max-height: 3.2em; overflow: hidden; }
.product-tile .scores { font-size: 11px; color: #666; text-align: center; }
.empty-state { text-align: center; color: #999; margin-top: 60px; font-size: 18px; }

/* Chat flyout toggle button */
.chat-toggle { position: fixed; bottom: 24px; right: 24px; width: 56px; height: 56px; border-radius: 50%; background: #1a73e8; color: #fff; border: none; font-size: 26px; cursor: pointer; box-shadow: 0 3px 12px rgba(0,0,0,0.3); z-index: 1000; display: flex; align-items: center; justify-content: center; transition: background 0.2s; }
.chat-toggle:hover { background: #1557b0; }
.chat-toggle.hidden { display: none; }

/* Chat flyout panel */
.chat-flyout { position: fixed; top: 0; right: -630px; width: 630px; height: 100vh; background: #fff; box-shadow: -3px 0 15px rgba(0,0,0,0.15); z-index: 999; display: flex; flex-direction: column; transition: right 0.3s ease; }
.chat-flyout.open { right: 0; }
.chat-header { background: #1a73e8; color: #fff; padding: 14px 18px; font-size: 17px; font-weight: 600; display: flex; justify-content: space-between; align-items: center; flex-shrink: 0; }
.chat-header button { background: rgba(255,255,255,0.2); color: #fff; border: none; padding: 5px 14px; border-radius: 4px; cursor: pointer; font-size: 13px; }
.chat-header button:hover { background: rgba(255,255,255,0.3); }
.chat-header .header-right { display: flex; gap: 8px; align-items: center; }
.chat-close { background: none !important; font-size: 20px !important; padding: 2px 8px !important; }
.chat-messages { flex: 1; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 10px; background: #f0f2f5; }
.chat-msg { max-width: 92%; padding: 9px 14px; border-radius: 10px; line-height: 1.45; word-wrap: break-word; font-size: 14px; }
.chat-msg.user { align-self: flex-end; background: #d3e3fd; border-bottom-right-radius: 3px; }
.chat-msg.assistant { align-self: flex-start; background: #fff; border-bottom-left-radius: 3px; box-shadow: 0 1px 2px rgba(0,0,0,0.08); }
.chat-msg img { max-width: 110px; max-height: 110px; border-radius: 6px; margin: 3px; vertical-align: middle; }
.chat-empty { text-align: center; color: #999; margin-top: 30px; font-size: 15px; }
.chat-input-area { padding: 10px 14px; border-top: 1px solid #ddd; background: #fff; flex-shrink: 0; }
.chat-input-area form { display: flex; gap: 8px; }
.chat-input-area input[type=text] { flex: 1; padding: 10px 14px; border: 1px solid #ccc; border-radius: 20px; font-size: 14px; outline: none; }
.chat-input-area input[type=text]:focus { border-color: #1a73e8; }
.chat-input-area button { background: #1a73e8; color: #fff; border: none; padding: 10px 18px; border-radius: 20px; font-size: 14px; cursor: pointer; }
.chat-input-area button:hover { background: #1557b0; }

/* Chat product tiles */
.chat-product-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 10px; }
.chat-product-tile { background: #f8f9fa; border: 1px solid #e0e0e0; border-radius: 8px; padding: 8px; text-align: center; text-decoration: none; color: inherit; transition: box-shadow 0.2s, border-color 0.2s; display: flex; flex-direction: column; align-items: center; }
.chat-product-tile:hover { border-color: #1a73e8; box-shadow: 0 2px 8px rgba(0,0,0,0.12); }
.chat-product-tile img { width: 100px; height: 100px; object-fit: contain; margin-bottom: 6px; }
.chat-product-title { font-size: 11px; font-weight: 600; line-height: 1.3; max-height: 2.6em; overflow: hidden; }

/* Loading spinner */
.spinner { display: inline-block; width: 18px; height: 18px; border: 2px solid #ccc; border-top-color: #1a73e8; border-radius: 50%; animation: spin 0.6s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
</head>
<body>

<!-- Top search bar -->
<div class="top-bar">
    <input type="text" id="searchInput" value="bbq accessories" placeholder="Search products..." autocomplete="off">
    <button onclick="runSearch()">Search</button>
    <div class="controls">
        <label><input type="radio" name="backend" value="solr" checked> Solr</label>
        <label><input type="radio" name="backend" value="opensearch"> OpenSearch</label>
        <label><input type="checkbox" id="rerank"> Cross-encoder reranking</label>
        <label><input type="checkbox" id="hybrid"> Hybrid Search</label>
        <label><input type="checkbox" id="trained"> Hybrid Search with Trained Head</label>
    </div>
</div>

<!-- Product grid -->
<div class="product-grid-wrapper" id="gridWrapper">
    <div class="product-grid" id="productGrid">
        <div class="empty-state">Enter a query and press Search</div>
    </div>
</div>

<!-- Chat toggle button -->
<button class="chat-toggle" id="chatToggle" onclick="toggleChat()" title="Shop Assistant">&#128172;</button>

<!-- Chat flyout -->
<div class="chat-flyout" id="chatFlyout">
    <div class="chat-header">
        <span>Shop Assistant</span>
        <div class="header-right">
            <button onclick="clearChat()">Clear</button>
            <button class="chat-close" onclick="closeChat()" title="Close">&times;</button>
        </div>
    </div>
    <div class="chat-messages" id="chatMessages">
        <div class="chat-empty">Ask anything to search products...</div>
    </div>
    <div class="chat-input-area">
        <form onsubmit="sendChat(event)">
            <input type="text" id="chatInput" placeholder="Type your question..." autocomplete="off">
            <button type="submit">Send</button>
        </form>
    </div>
</div>

<script>
// Search
const searchInput = document.getElementById('searchInput');
searchInput.addEventListener('keydown', e => { if (e.key === 'Enter') runSearch(); });

async function runSearch() {
    const q = searchInput.value.trim();
    if (!q) return;
    const backend = document.querySelector('input[name=backend]:checked').value;
    const hybrid = document.getElementById('hybrid').checked;
    const trained = document.getElementById('trained').checked;
    const rerank = document.getElementById('rerank').checked;

    const grid = document.getElementById('productGrid');
    grid.innerHTML = '<div class="empty-state"><div class="spinner"></div> Searching...</div>';

    try {
        const params = new URLSearchParams({ q, backend, hybrid, trained, rerank });
        const resp = await fetch('/api/search?' + params);
        const products = await resp.json();
        renderProducts(products);
    } catch (err) {
        grid.innerHTML = '<div class="empty-state">Error: ' + err.message + '</div>';
    }
}

function renderProducts(products) {
    const grid = document.getElementById('productGrid');
    if (!products.length) {
        grid.innerHTML = '<div class="empty-state">No results found</div>';
        return;
    }
    grid.innerHTML = products.map(p => {
        let scoreHtml = '';
        const lexical = p.lexicalScore ?? p.score;
        if (lexical != null) scoreHtml += 'lexical-score: ' + lexical + '<br>';
        if (p.vectorScore != null) scoreHtml += 'vector-score: ' + p.vectorScore;
        return '<div class="product-tile">' +
            '<img src="' + escapeAttr(p.imageUrl_s) + '" alt="">' +
            '<div class="title">' + escapeHtml(p.title_txt_en) + '</div>' +
            '<div class="scores">' + scoreHtml + '</div>' +
            '</div>';
    }).join('');
}

// Chat
function toggleChat() {
    document.getElementById('chatFlyout').classList.add('open');
    document.getElementById('chatToggle').classList.add('hidden');
    document.getElementById('chatInput').focus();
}
function closeChat() {
    document.getElementById('chatFlyout').classList.remove('open');
    document.getElementById('chatToggle').classList.remove('hidden');
}

async function sendChat(e) {
    e.preventDefault();
    const input = document.getElementById('chatInput');
    const query = input.value.trim();
    if (!query) return;
    input.value = '';

    const messagesDiv = document.getElementById('chatMessages');
    // Remove empty state
    const emptyEl = messagesDiv.querySelector('.chat-empty');
    if (emptyEl) emptyEl.remove();

    // Add user message
    const userDiv = document.createElement('div');
    userDiv.className = 'chat-msg user';
    userDiv.textContent = query;
    messagesDiv.appendChild(userDiv);

    // Add loading indicator
    const loadingDiv = document.createElement('div');
    loadingDiv.className = 'chat-msg assistant';
    loadingDiv.innerHTML = '<div class="spinner"></div>';
    messagesDiv.appendChild(loadingDiv);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;

    try {
        const resp = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query })
        });
        const data = await resp.json();
        loadingDiv.innerHTML = data.html || escapeHtml(data.answer);
    } catch (err) {
        loadingDiv.innerHTML = 'Error: ' + escapeHtml(err.message);
    }
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
}

async function clearChat() {
    await fetch('/api/chat/clear', { method: 'POST' });
    const messagesDiv = document.getElementById('chatMessages');
    messagesDiv.innerHTML = '<div class="chat-empty">Ask anything to search products...</div>';
}

function escapeHtml(t) { return t.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
function escapeAttr(t) { return t.replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
</script>
</body>
</html>
""".trimIndent()
