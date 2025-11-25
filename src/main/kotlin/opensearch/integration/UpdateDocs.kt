package opensearch.integration


import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.apache.hc.core5.http.HttpHost
import org.opensearch.client.json.jackson.JacksonJsonpMapper
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder
import kotlin.random.Random


class UpdateDocs {

    data class Product(
        val code: String,
        val name: String,
        val description: String,
        val category: String,
        val price: Double,
        val ats: Int
    )

    val mapper = ObjectMapper()
        .registerKotlinModule()

    companion object {

    var customerIds = (1..100000)
        @JvmStatic
        fun main(args: Array<String>) {

            val mapper = ObjectMapper().registerKotlinModule()
            
            val jacksonJsonpMapper = JacksonJsonpMapper(mapper)
            val builder = ApacheHttpClient5TransportBuilder     
                .builder(HttpHost("http", "localhost", 9200))
                .setMapper(jacksonJsonpMapper)
                .build()

            val client = OpenSearchClient(builder)

            (1..100000).toList().parallelStream().map { 
                val id = getProductIdByCode(client, it.toString())
                if(it % 100 == 0) {
                    println("Updating product with ID: $id, count: $it")
                }
                addCustomersToProduct(client, id)
            }.toList()
        }

        private fun getProductIdByCode(client: OpenSearchClient, productCode: String
        ): String {
            val searchResponse = client.search({
                it.index("products")
                    .query { q ->
                        q.term { t ->
                            t.field("code")
                                .value { v -> v.stringValue(productCode) }
                        }
                    }
                    .size(1)
            }, Map::class.java)
                                                    
            val id = searchResponse.hits().hits().firstOrNull()?.id() ?: throw IllegalArgumentException("No product found with code $productCode")
            return id
        }

        fun addCustomersToProduct(client: OpenSearchClient, id: String) {
            val updateResponse = client.update(
                {
                    it.index("products")
                        .id(id)
                        .doc(mapOf("customerIds" to  customerIds.shuffled().take(10000)))
                },
                Map::class.java
            )
        }
     }
    
    
    }
