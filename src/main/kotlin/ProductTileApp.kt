import javafx.application.Application
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.*
import javafx.stage.Stage
import opensearch.integration.OpenSearchIntegration
import rerank.ReRankService
import sampleproducts.Product
import solr.integration.SolrIntegration

fun main() {
    Application.launch(ProductTilesApp::class.java)
}


class ProductTilesApp : Application() {


    private val openSearch = OpenSearchIntegration()
    private val reRankService = ReRankService()
    private val solrIntegration = SolrIntegration()

    // UI elements we need to update later
    private lateinit var tilePane: TilePane
    private lateinit var searchField: TextField
    private lateinit var rerankCheckBox: CheckBox
    private lateinit var vectorSearch: CheckBox
    private lateinit var useCustomTrainedModel: CheckBox
    private lateinit var solrRadio: RadioButton

    //toy for 5 years old boy
    //bbq accessories
//    bluetooth speakers
//    game console
    override fun start(primaryStage: Stage) {
        // --- Controls at the top -------------------------------------------------
        val backendGroup = ToggleGroup()

        solrRadio = RadioButton("Solr").apply {
            toggleGroup = backendGroup
            isSelected = true   // default
        }

        val openSearchRadio = RadioButton("OpenSearch").apply {
            toggleGroup = backendGroup
        }


        searchField = TextField("bbq accessories").apply {
            promptText = "Search products..."
            prefWidth = 400.0
        }

        rerankCheckBox = CheckBox("Use cross-encoder reranking").apply {
            isSelected = false
        }
        vectorSearch = CheckBox("Use Hybrid Search").apply {
            isSelected = false
        }
        useCustomTrainedModel = CheckBox("Use Hybrid Search with Trained Head").apply {
            isSelected = false
        }

        val searchButton = Button("Search").apply {
            setOnAction {
                runSearch()
            }
        }

        // also allow pressing Enter in the text field
        searchField.setOnAction { runSearch() }

        val topBar = HBox(
            10.0,
            Label("Query:"),
            searchField,
            searchButton,
            rerankCheckBox,
            vectorSearch,
            useCustomTrainedModel,
            solrRadio,
            openSearchRadio
        ).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(10.0)
        }

        // --- TilePane + ScrollPane for results -----------------------------------
        tilePane = TilePane().apply {
            padding = Insets(10.0)
            hgap = 10.0
            vgap = 10.0
            prefColumns = 4
        }

        val scrollPane = ScrollPane(tilePane).apply {
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            isFitToWidth = true
            isPannable = true
        }

        // --- Layout root ---------------------------------------------------------
        val root = BorderPane().apply {
            top = topBar
            center = scrollPane
        }

        val scene = Scene(root, 1600.0, 1200.0)
        primaryStage.title = "Product Tiles"
        primaryStage.scene = scene
        primaryStage.show()

        // initial search
//        runSearch()
    }

    private fun runSearch() {
        val query = searchField.text.trim()
        if (query.isEmpty()) return


        var products = if (solrRadio.isSelected) {
            if (vectorSearch.isSelected) {
                if (useCustomTrainedModel.isSelected) {
                    solrIntegration.hybridSearchUserInputAnd(query, "products-head")
                } else {
                    solrIntegration.hybridSearchUserInputAnd(query, "products-head-original")
                                     }
            } else {
                solrIntegration.searchUserInputAnd(query, "products-head")

            }
        } else {
            openSearch.searchUserInputAnd(query)
        }

        products.forEach { println("${it.code_s}:${it.title_txt_en}") }
        
        // 1) search in OpenSearch

//        var products = solrIntegration.searchUserInputAnd(query)

        // 2) optional rerank
        if (rerankCheckBox.isSelected) {
            products = reRankService.rerankByTitle(query, products).map { it.first }
        }

        // 3) render tiles
        tilePane.children.clear()
        products.forEach { product ->
            tilePane.children.add(createProductTile(product))
        }
    }

    private fun createProductTile(product: Product): VBox {

        fun formatFloat(float: Float?) = float//String.format("%.4f", float)

        val imageView = ImageView().apply {
            image = Image(product.imageUrl_s, 150.0, 150.0, true, true, true)
            fitWidth = 150.0
            fitHeight = 150.0
            isPreserveRatio = true
        }

        val titleLabel = Label(product.title_txt_en).apply {
            maxWidth = 200.0
            isWrapText = true
            style = "-fx-font-size: 14px; -fx-font-weight: bold;"
        }
        val scores =
            Label("lexical-score:" + formatFloat(product.lexicalScore ?: product.score).toString() ).apply {
                maxWidth = 80.0
               
                isWrapText =  true
                style = "-fx-font-size: 14px; -fx-font-weight: bold;"
            }
        val vectorScore =
            Label(if (product.vectorScore != null)  "vector-score:"+(formatFloat(product.vectorScore).toString()) else "").apply {
                maxWidth = 80.0
               
                isWrapText =  true
                style = "-fx-font-size: 14px; -fx-font-weight: bold;"
            }
        


        return VBox(8.0, imageView, titleLabel, scores, vectorScore).apply {
            alignment = Pos.TOP_CENTER
            padding = Insets(8.0)
            style = """
                -fx-background-color: #ffffff;
                -fx-border-color: #dddddd;
                -fx-border-radius: 8;
                -fx-background-radius: 8;
                -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 5, 0.0, 0, 2);
            """.trimIndent()

            setOnMouseEntered {
                style = """
                    -fx-background-color: #f7f7f7;
                    -fx-border-color: #bbbbbb;
                    -fx-border-radius: 8;
                    -fx-background-radius: 8;
                    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 8, 0.0, 0, 3);
                """.trimIndent()
            }
            setOnMouseExited {
                style = """
                    -fx-background-color: #ffffff;
                    -fx-border-color: #dddddd;
                    -fx-border-radius: 8;
                    -fx-background-radius: 8;
                    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 5, 0.0, 0, 2);
                """.trimIndent()
            }

            setOnMouseClicked {
                println("Clicked product: ${product.id} - ${product.title_txt_en}")
            }
        }
    }
}
