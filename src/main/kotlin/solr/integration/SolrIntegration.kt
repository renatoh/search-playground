package solr.integration

import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.impl.Http2SolrClient
import sampleproducts.Product

class SolrIntegration {

    fun searchUserInputAnd(userInput : String): List<Product> {

        val solrUrl = "http://linux:8983/solr/"
        val solrClient: SolrClient = Http2SolrClient.Builder(solrUrl)
            .build()

        val query = SolrQuery()
        
//        query.set("q", userInput)
//        query.set("df","title_txt_en")
//        query.set("fl", "*,score")

        
        query.set("fl", "*,score,vectorScore:query(\$vectorQuery),lexicalScore:query(\$normalisedLexicalQuery)")
        
        query.set(
            "q",
            "{!bool filter=\$retrievalStage must=\$rankingStage}"
        )

        // stages
        query.set(
            "retrievalStage",
            "{!bool should=\$lexicalQuery should=\$vectorQuery}"
        )
        query.set(
            "rankingStage",
//            "{!func}product(query(\$normalisedLexicalQuery),query(\$vectorQuery))"
            "{!func}sum(query(\$normalisedLexicalQuery),query(\$vectorQuery))"
        )
        query.set(
            "normalisedLexicalQuery",
            "{!func}scale(query(\$lexicalQuery),0.1,1)"
        )

        // actual queries (lexical + vector) using the term
        query.set(
            "lexicalQuery",
            "{!type=edismax qf=title_txt_en}$userInput"
        )
        query.set(
            "vectorQuery",
            "{!knn_text_to_vector f=vector_en model=customLocal}$userInput"
        )

        // optional: rows, start, fields, etc.
        query.rows = 50
        query.start = 0

        val response = solrClient.query("products", query)

        return response.results.map { doc ->
            Product(
                id = (doc["id"] as String).toInt(),
                code_s = doc["code_s"] as String,
                title_txt_en = doc["title_txt_en"] as String,
                imageUrl_s = doc["image_url_s"] as String,
                productUrl_s = doc["productUrl_s"] as String,
                rating_d = (doc["rating_d"] as Number).toFloat(),
                numberOfReviews_i = (doc["numberOfReviews_i"] as Number).toInt(),
                price_d = (doc["price_d"] as Number).toFloat(),
                listPrice_d = (doc["listPrice_d"] as Number).toFloat(),
                categoryId_i = (doc["categoryId_i"] as Number).toInt(),
                bestSeller_b = doc["bestSeller_b"] as Boolean,
                boughtLastMonth_i = (doc["boughtLastMonth_i"] as Number).toInt(),
                vectorScore = (doc["vectorScore"] as? Number)?.toFloat(), 
                lexicalScore = (doc["lexicalScore"] as Number).toFloat()
            );

        }
    }
}
//    
    
    

