package solr

import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.client.solrj.impl.Http2SolrClient
import org.apache.solr.client.solrj.request.UpdateRequest
import org.apache.solr.common.SolrInputDocument
import sampleproducts.Product
import java.util.concurrent.ForkJoinPool
import kotlin.time.Duration
import kotlin.time.measureTime

class SolrIndexProducts {
    
     fun indexProducts(products: MutableList<Product>, collectionName : String): Duration {
        val solrUrl = "http://linux:8983/solr/"
        val pool = ForkJoinPool(40)
        val solrDocs = products.map { createSolrDocForProduct(it) }
        val solrClient: SolrClient = Http2SolrClient.Builder(solrUrl)
            .build()
    
    
        val timeTaken = measureTime {
            val taks = pool.submit {
                solrDocs.chunked(100).parallelStream()
                    .forEach { addDocsToSolr(solrClient, it, collectionName) }
            }
    
        }
        return timeTaken
    }
    
    private fun addDocsToSolr(
        solrClient: SolrClient,
        docs: List<SolrInputDocument>,
        collection: String,
    ) {
    
        val startTime = System.currentTimeMillis()
        UpdateRequest().apply {
            add(docs)
            val timeTaken = measureTime {
                commitWithin
                process(solrClient, collection)
            }
            println("Indexed ${docs.size} docs into '$collection' in $timeTaken ms. time passed since started:${(System.currentTimeMillis() - startTime) / 1000}s")
        }
    
    }
    
    
    fun createSolrDocForProduct(product: Product): SolrInputDocument {
        val doc = SolrInputDocument()
    
        doc.addField("id", "${product.id}")
        doc.addField("code_s", "${product.code_s}")
        doc.addField("title_txt_en", "${product.title_txt_en}")
        doc.addField("image_url_s", "${product.imageUrl_s}")
        doc.addField("productUrl_s", "${product.productUrl_s}")
        doc.addField("rating_d", "${product.rating_d}")
        doc.addField("numberOfReviews_i", "${product.numberOfReviews_i}")
        doc.addField("price_d", "${product.price_d}")
        doc.addField("listPrice_d", "${product.listPrice_d}")
        doc.addField("categoryId_i", "${product.categoryId_i}")
        doc.addField("bestSeller_b", "${product.bestSeller_b}")
        doc.addField("boughtLastMonth_i", "${product.boughtLastMonth_i}")
    
        return doc
    }
}
