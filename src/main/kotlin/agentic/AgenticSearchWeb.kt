package agentic

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ChatMessage(val role: String, val content: String)

private val mapper = jacksonObjectMapper().apply {
    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}

private val sessions = ConcurrentHashMap<String, MutableList<ChatMessage>>()

private fun ApplicationCall.sessionId(): String {
    val existing = request.cookies["CHAT_SESSION"]
    if (existing != null && sessions.containsKey(existing)) return existing
    val id = UUID.randomUUID().toString()
    response.cookies.append("CHAT_SESSION", id, path = "/")
    sessions[id] = mutableListOf()
    return id
}

private fun ApplicationCall.getMessages(): MutableList<ChatMessage> {
    val id = sessionId()
    return sessions.getOrPut(id) { mutableListOf() }
}

private val httpClient = HttpClient(CIO) {
    engine {
        requestTimeout = 120_000
    }
}

private val markdownImagePattern = Regex("""\[!\[([^\]]*)\]\([^)]+\)\]\(([^)]+)\)""")

fun stripImageMarkdown(text: String): String =
    markdownImagePattern.replace(text) { "[${it.groupValues[1]}](${it.groupValues[2]})" }

data class AgenticResponse(val answer: String, val products: String)

suspend fun callAgenticSearch(query: String, history: List<ChatMessage>): AgenticResponse {
    val historyPayload = history.map {
        mapOf("role" to it.role, "content" to it.content)
    }
    val body = mapper.writeValueAsString(
        mapOf("query" to query, "history" to historyPayload)
    )
    val response = httpClient.post("http://linux:8080/search") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    val json = response.bodyAsText()
    val parsed: Map<String, Any?> = mapper.readValue(json)
    val answer = parsed["answer"] as? String ?: "No answer received."
    @Suppress("UNCHECKED_CAST")
    val products = parsed["products"] as? List<Map<String, Any?>> ?: emptyList()
    val productsJson = if (products.isNotEmpty()) mapper.writeValueAsString(products) else ""
    return AgenticResponse(answer, productsJson)
}

fun renderMarkdownImages(text: String): String {
    val pattern = Regex("""\[!\[([^\]]*)\]\(([^)]+)\)\]\(([^)]+)\)""")
    return pattern.replace(text) { match ->
        val alt = match.groupValues[1]
        val imgUrl = match.groupValues[2]
        val linkUrl = match.groupValues[3]
        """<a href="$linkUrl" target="_blank"><img src="$imgUrl" alt="$alt" style="max-width:120px;max-height:120px;border-radius:8px;margin:4px;vertical-align:middle;"></a>"""
    }
}

fun formatMessageHtml(content: String): String {
    val withImages = renderMarkdownImages(content)
    val tagPattern = Regex("""<a href="[^"]*" target="_blank"><img src="[^"]*" alt="[^"]*" style="[^"]*"></a>""")
    val parts = mutableListOf<String>()
    var lastEnd = 0
    for (match in tagPattern.findAll(withImages)) {
        if (match.range.first > lastEnd) {
            parts.add(escapeHtml(withImages.substring(lastEnd, match.range.first)))
        }
        parts.add(match.value)
        lastEnd = match.range.last + 1
    }
    if (lastEnd < withImages.length) {
        parts.add(escapeHtml(withImages.substring(lastEnd)))
    }
    return parts.joinToString("").replace("\n", "<br>")
}

fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

fun main() {
    embeddedServer(Netty, port = 9090) {
        routing {
            get("/") {
                val messages = call.getMessages()
                call.respondHtml {
                    head {
                        title("Agentic Search")
                        meta { charset = "utf-8" }
                        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
                        style {
                            unsafe {
                                raw("""
                                    * { box-sizing: border-box; margin: 0; padding: 0; }
                                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f0f2f5; height: 100vh; display: flex; flex-direction: column; }
                                    .header { background: #075e54; color: white; padding: 16px 24px; font-size: 20px; font-weight: 600; flex-shrink: 0; display: flex; justify-content: space-between; align-items: center; }
                                    .header form { margin: 0; }
                                    .header button { background: rgba(255,255,255,0.2); color: white; border: none; padding: 6px 16px; border-radius: 4px; cursor: pointer; font-size: 14px; }
                                    .header button:hover { background: rgba(255,255,255,0.3); }
                                    .messages { flex: 1; overflow-y: auto; padding: 20px; display: flex; flex-direction: column; gap: 12px; }
                                    .message { max-width: 75%; padding: 10px 16px; border-radius: 12px; line-height: 1.5; word-wrap: break-word; }
                                    .message.user { align-self: flex-end; background: #dcf8c6; border-bottom-right-radius: 4px; }
                                    .message.assistant { align-self: flex-start; background: white; border-bottom-left-radius: 4px; box-shadow: 0 1px 2px rgba(0,0,0,0.1); }
                                    .message img { display: inline-block; }
                                    .input-area { flex-shrink: 0; padding: 12px 20px; background: #f0f2f5; border-top: 1px solid #ddd; }
                                    .input-area form { display: flex; gap: 10px; }
                                    .input-area input[type=text] { flex: 1; padding: 12px 16px; border: 1px solid #ccc; border-radius: 24px; font-size: 16px; outline: none; }
                                    .input-area input[type=text]:focus { border-color: #075e54; }
                                    .input-area button { background: #075e54; color: white; border: none; padding: 12px 24px; border-radius: 24px; font-size: 16px; cursor: pointer; }
                                    .input-area button:hover { background: #064e46; }
                                    .empty { text-align: center; color: #999; margin-top: 40px; font-size: 18px; }
                                """.trimIndent())
                            }
                        }
                    }
                    body {
                        div("header") {
                            span { +"Agentic Search" }
                            form(action = "/clear", method = FormMethod.post) {
                                button(type = ButtonType.submit) { +"Clear" }
                            }
                        }
                        div("messages") {
                            id = "messages"
                            if (messages.isEmpty()) {
                                div("empty") { +"Ask anything to search products..." }
                            }
                            for (msg in messages) {
                                div("message ${msg.role}") {
                                    unsafe { raw(formatMessageHtml(msg.content.replace(Regex("""\n\n\[Products shown:.*""", RegexOption.DOT_MATCHES_ALL), ""))) }
                                }
                            }
                        }
                        div("input-area") {
                            form(action = "/", method = FormMethod.post) {
                                input(type = InputType.text, name = "query") {
                                    placeholder = "Type your search..."
                                    autoFocus = true
                                    attributes["autocomplete"] = "off"
                                }
                                button(type = ButtonType.submit) { +"Send" }
                            }
                        }
                        script {
                            unsafe {
                                raw("""document.getElementById('messages').scrollTop = document.getElementById('messages').scrollHeight;""")
                            }
                        }
                    }
                }
            }

            post("/") {
                val params = call.receiveParameters()
                val query = params["query"]?.trim() ?: ""
                if (query.isNotEmpty()) {
                    val messages = call.getMessages()
                    messages.add(ChatMessage("user", query))
                    try {
                        val response = callAgenticSearch(query, messages.dropLast(1))
                        val stored = if (response.products.isNotEmpty())
                            "${response.answer}\n\n[Products shown: ${response.products}]"
                        else
                            response.answer
                        messages.add(ChatMessage("assistant", stored))
                    } catch (e: Exception) {
                        messages.add(ChatMessage("assistant", "Error: ${e.message}"))
                    }
                }
                call.respondRedirect("/")
            }

            post("/clear") {
                val id = call.request.cookies["CHAT_SESSION"]
                if (id != null) sessions.remove(id)
                call.response.cookies.append("CHAT_SESSION", "", path = "/", maxAge = 0L)
                call.respondRedirect("/")
            }
        }
    }.start(wait = true)
}
