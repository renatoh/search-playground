package rerank

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import sampleproducts.Product
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration


class ReRankService {

    private val json = jacksonObjectMapper()
    
    val client = HttpClient.newBuilder()
           .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
           .build()
    
    fun rerankByTitle(query: String, products: List<Product>): List<Pair<Product, Double>> {
        if (products.isEmpty()) return emptyList()
    
        // prepare documents = product titles in current order
        val docs = products.map { it.title_txt_en }
    
        val requestPayload = RankRequest(
            query = query,
            documents = docs                            
        )
        val body = json.writeValueAsString(requestPayload)
        
val request = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:8000/rank"))
    .header("Content-Type", "application/json; charset=UTF-8")
    .timeout(Duration.ofSeconds(10))
    .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
    .build()
    
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    
        if (response.statusCode() !in 200..299) {
            // fallback: return original order if reranker fails
            println("Rerank service failed with status ${response.statusCode()}: ${response.body()}")
            return products.map { it to 0.0 }
        }
    
        val rankResponse: RankResponse = json.readValue(response.body())
    
        // map RankResult.index back to the original products list
        val mapNotNull = rankResponse.results.mapNotNull { rr ->
            products.getOrNull(rr.index)?.let { p -> p to rr.score }
        }
        return mapNotNull
    }

}
