package opensearch.integration


import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.apache.hc.core5.http.HttpHost
import org.opensearch.client.json.jackson.JacksonJsonpMapper
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch.core.BulkRequest
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder


class CreateLinkDocs {

    data class Product2Customer(
        val customerId: Long,
        val productId: Long
    )

    val mapper = ObjectMapper()
        .registerKotlinModule()

    
    var customerIds = (1..100000)
    companion object {

        var customerIds = (1..100000)

        

        @JvmStatic
        fun main(args: Array<String>) {

            val mapper = ObjectMapper().registerKotlinModule()
            
            
            val jacksonJsonpMapper = JacksonJsonpMapper(mapper)
            val builder = ApacheHttpClient5TransportBuilder
                .builder(HttpHost("http", "linux", 9200))
                .setMapper(jacksonJsonpMapper)
                .build()

            val client = OpenSearchClient(builder)

            val documents = listOf(
                Product2Customer(1L, 101L),
                Product2Customer(2L, 102L),
                Product2Customer(3L, 103L)
            )
            
            //productIds are from 1 to 100000
            val mappingsPerProduct = (50001..60000).toList().parallelStream().map { productId ->
                customerIds.shuffled().take(10000).map { customerId ->
                    Product2Customer(customerId.toLong(), productId.toLong())
                }
            }

            mappingsPerProduct.forEach { mappingPperProduct->
                val bulkRequest = creeateBulkReqquest(mappingPperProduct)
                val response = client.bulk(bulkRequest.build())
            }

        }

        private fun creeateBulkReqquest(documents: List<Product2Customer>): BulkRequest.Builder {
            val bulkRequest = BulkRequest.Builder()

            documents.forEach { doc ->
                bulkRequest.operations { ops ->
                    ops.index { idx ->
                        idx.index("product2customer")
                            .id("${doc.customerId}-${doc.productId}") // optional custom ID
                            .document(doc)
                    }
                }
            }
            return bulkRequest
        }

    }
}
