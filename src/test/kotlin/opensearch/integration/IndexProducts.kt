import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.opensearch.client.json.jackson.JacksonJsonpMapper
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.apache.hc.core5.http.HttpHost
import org.opensearch.client.opensearch.core.BulkRequest
import org.opensearch.client.opensearch.core.bulk.BulkOperation
import org.opensearch.client.opensearch.indices.CreateIndexRequest
import sampleproducts.Product


class IndexProducts {
    val mapper = ObjectMapper().registerKotlinModule()
    val jacksonJsonpMapper = JacksonJsonpMapper(mapper)
    val builder = ApacheHttpClient5TransportBuilder
        .builder(HttpHost("http", "localhost", 9200))
        .setMapper(jacksonJsonpMapper)
        .build()

    val client = OpenSearchClient(builder)

    fun bulkIndexProducts(products: List<Product>) {
        
        val ops = products.map { product ->
            BulkOperation.Builder()
                .index { idx ->
                    idx.index("products")
                        .id(product.id.toString())
                        .document(product)
                }.build()
        }

        val req = BulkRequest.Builder()
            .operations(ops)
            .build()

        val resp = client.bulk(req)

        if (resp.errors()) {
            println("Bulk indexing failures:")
            resp.items().forEach { println(it.error()) }
        } else {
            println("Bulk indexed ${products.size} products")
        }


    }

}
