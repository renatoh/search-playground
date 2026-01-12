package sampleproducts

import IndexProducts
import com.opencsv.CSVReader
import java.io.FileReader

import kotlinx.serialization.Serializable
import solr.SolrIndexProducts

@Serializable
data class Product(
    val id: Int,
    val code_s: String,
    val title_txt_en: String,
    val imageUrl_s: String,
    val productUrl_s: String,
    val rating_d: Float,
    val numberOfReviews_i: Int,
    val price_d: Float,
    val listPrice_d: Float,
    val categoryId_i: Int,
    val bestSeller_b: Boolean,
    val boughtLastMonth_i: Int,
    val vectorScore: Float? = null,
    var lexicalScore: Float? = null,
    var score: Float? = null
)

fun main() {


    val pathToProductCSv = "/Users/renato/solr/sample_data/amazon_products_head.csv"

    val start = 0 * 1000;
    val limit = 150 * 1000

    val t1 = System.currentTimeMillis()
    var counter = 0
    val products = mutableListOf<Product>()
    CSVReader(FileReader(pathToProductCSv)).use { reader ->
        var line: Array<String>?
        while (reader.readNext().apply { line = this } != null && counter <= limit) {


            if (counter < start) {
                counter++                                                         
                continue;
            }

            if (counter == 0) {
                counter++
                continue
            }
            println(line?.get(0))

            try {
                if (line != null) {
                    val product = Product(
                        counter,
                        line[0].trim(),
                        line[1],
                        line[2],
                        line[3],
                        line[4].toFloat(),
                        line[5].toInt(),
                        line[6].toFloat(),
                        line[7].toFloat(),
                        line[8].toInt(),
                        line[9].toBoolean(),
                        line[10].toInt()
                    )
                    products.add(product)
                }
            } catch (e: NumberFormatException) {
                println("line $line could not be parsed")
            }
            counter++

        }
    }

    print("number of products: ${products.size}")

    // index to OpenSearch
//         val indexProducts = IndexProducts();
//        products.chunked(200).forEach { indexProducts.bulkIndexProducts(it) }
//        indexProducts.bulkIndexProducts(products)
//
//        val allProductCodes = products.map { it.categoryId_i }.toSet()
//        println(allProductCodes)


    val indexer = SolrIndexProducts();
    val timeTaken = indexer.indexProducts(products, "products-head-original")
    println("adding docs to solr took:${timeTaken}")
}



        
