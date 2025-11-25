package solr


import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.client.solrj.impl.Http2SolrClient
import org.apache.solr.client.solrj.request.UpdateRequest
import org.apache.solr.common.SolrInputDocument
import java.util.concurrent.ForkJoinPool
import kotlin.math.round
import kotlin.random.Random
import kotlin.time.measureTime

class CreateLinkDocs {
}

fun main() {

    var customerIds = (1..100000)

    // --- CONFIG ---
    // If you started Solr with embedded ZooKeeper: usually "localhost:9983"
    // If you use external ZK, put your ensemble here (comma-separated).
//          val zkHost = System.getenv("ZK_HOST") ?: "localhost:9983"
//          val zkHost = System.getenv("ZK_HOST") ?: "localhost:2181"
//          val collection = System.getenv("SOLR_COLLECTION") ?: "customerprices"

    val core = System.getenv("SOLR_CORE") ?: "customerprices" // your core name
    val solrUrl = System.getenv("SOLR_URL") ?: "http://linux:8983/solr/"
//    val solrUrl = System.getenv("SOLR_URL") ?: "http://localhost:8983/solr/"


    val t1 = System.currentTimeMillis()
    val c = 0

    val pool = ForkJoinPool(16)
    val solrClient: SolrClient = Http2SolrClient.Builder(solrUrl)
        .build()
/*        val solrClient: SolrClient = CloudSolrClient.Builder(Collections.singletonList(zkHost), Optional.empty()).withDefaultCollection(collection)
            .build()*/

    //productIds from 1 to 100k
//    pool.submit {

//        (49000..50 * 1000).toList().parallelStream().map { productId ->
        (50000 until 100000).toList().map { productId ->

            val docs = customerIds.shuffled().take(10000).map { customerId ->
                createSolrDoc(customerId.toLong(), productId.toLong())
            }
            addDocsToCollection(solrClient, docs, collection = "customerprices", t1)
//            println("docs for product $productId added")
//        }.toList()
    }




    println("time taken: ${(System.currentTimeMillis() - t1) / 1000} seconds")
//    val solr = Http2SolrClient.Builder("http://localhost:8983/solr/product2customer").build()


//    addDocsToCollection(build, docs, collection)
}


private fun randomPrice(): Double {
    return (Random.nextDouble(10.0, 1000.0) * 100).let {
        round(it) / 100
    }
}


private fun addDocsToCollection(
    solrClient: SolrClient,
    docs: List<SolrInputDocument>,
    collection: String,
    startTime : Long
) {

    // Batch add with UpdateRequest for efficiency

    UpdateRequest().apply {
        add(docs)
        val timeTaken = measureTime {
            commitWithin
            process(solrClient, collection)
        }
        println("Indexed ${docs.size} docs into '$collection' in $timeTaken ms. time passed since started:${(System.currentTimeMillis() - startTime) / 1000}s")
    }

}

private fun createSolrDoc(customerId: Long, productId: Long): SolrInputDocument {
    val doc = SolrInputDocument()
    doc.addField("id", "${customerId}_${productId}")   // unique key
    doc.addField("customerId", customerId)
    doc.addField("productId", productId)
    doc.addField("price", randomPrice())
    return doc
}
