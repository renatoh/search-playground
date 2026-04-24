package opensearch.integration


import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.apache.hc.core5.http.HttpHost
import org.opensearch.client.json.jackson.JacksonJsonpMapper
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch._types.FieldValue
import org.opensearch.client.opensearch._types.query_dsl.MatchAllQuery
import org.opensearch.client.opensearch._types.query_dsl.Operator
import org.opensearch.client.opensearch.core.SearchRequest
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder


class OpenSearchIntegration {

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
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    val jacksonJsonpMapper = JacksonJsonpMapper(mapper)

    val builder = ApacheHttpClient5TransportBuilder
        .builder(HttpHost("http", "localhost", 9200))
        .setMapper(jacksonJsonpMapper)
        .build()
    
    val client = OpenSearchClient(builder)
    
    fun searchUserInputAnd(userInput: String): List<sampleproducts.Product> {
        val request = SearchRequest.Builder()
            .index("products")
            .query { q ->
                q.bool { b ->
                    b.must { must ->
                        must.match { m ->
                            m.field("title_txt_en")
                                .query(FieldValue.of(userInput))   
                                .operator(Operator.And)        
                        }
                    }
                }
            }.size(50)
            .build()
    
        val response = client.search(request, sampleproducts.Product::class.java)
       
        return response.hits().hits().mapNotNull { it.source().apply { 
            this?.lexicalScore = it?.score()?.toFloat()?:0.0f} 
        }
    }
    
    companion object {

        @JvmStatic
        fun main(args: Array<String>) {

            val mapper = ObjectMapper().registerKotlinModule()

            val jacksonJsonpMapper = JacksonJsonpMapper(mapper)
            val builder = ApacheHttpClient5TransportBuilder
                .builder(HttpHost("http", "localhost", 9200))
                .setMapper(jacksonJsonpMapper)
                .build()
            
            val client = OpenSearchClient(builder)

            val searchRequest = SearchRequest.of { s ->
                s.index("products")
                    .query { q -> q.matchAll(MatchAllQuery.builder().build()) }
                    .size(100)
            }

            val search = client.search(searchRequest, Product::class.java)

            search.hits().hits().forEach { hit ->
                println("ID: ${hit.id()}, Index: ${hit.index()}, Score: ${hit.score()}, Source: ${hit.source()}")
            }
            println(search)

        }
    }

}
