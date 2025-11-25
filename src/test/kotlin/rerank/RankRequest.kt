package rerank

data class RankRequest(
    val query: String,
    val documents: List<String>
)

