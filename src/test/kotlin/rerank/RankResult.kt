package rerank

data class RankResult(
    val index: Int,
    val score: Double,
    val document: String
)
