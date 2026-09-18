package com.sumideck.fg

/** テスト用の合成データ。CNNの生データは使わない(SPEC.md 8章)。 */
object Fixtures {

    fun historyJson(points: List<Pair<String, Double>>): String =
        points.joinToString(",") { """["${it.first}",${it.second}]""" }

    fun componentsJson(): String = listOf(
        "momentum" to "モメンタム", "strength" to "株価の強さ", "breadth" to "市場の幅",
        "put_call" to "プット/コール", "volatility" to "ボラティリティ",
        "junk_bond" to "ジャンク債需要", "safe_haven" to "安全資産需要",
    ).mapIndexed { i, (key, ja) ->
        """{"key":"$key","name_ja":"$ja","score":${10.0 + i},"raw":${1.0 + i}}"""
    }.joinToString(",")

    fun doc(
        score: Double? = 28.54,
        rating: String? = "fear",
        delta: Double? = -0.15,
        streak: Int = -3,
        stale: Boolean = false,
        generatedAt: String = "2026-09-18T06:41:12+09:00",
        history: List<Pair<String, Double>> = businessSeries(60) { 30.0 },
        extraTopLevel: String = "",
    ): String = """
        {
          "schema": 2,
          "generated_at": "$generatedAt",
          ${if (extraTopLevel.isEmpty()) "" else "$extraTopLevel,"}
          "us": {
            "source": "cnn",
            "as_of": "${history.lastOrNull()?.first ?: "2026-09-18"}",
            "score": ${score ?: "null"},
            "rating": ${if (rating == null) "null" else "\"$rating\""},
            "prev_close": 28.69,
            "w1": 32.69, "m1": 54.63, "y1": 66.54,
            "delta": ${delta ?: "null"},
            "streak": $streak,
            "components": [${componentsJson()}],
            "history": [${historyJson(history)}],
            "stale": $stale
          }
        }
    """.trimIndent()

    /** 土日を飛ばした日付を count 本、古い順に作る。値は [value] で決める。 */
    fun businessSeries(count: Int, value: (Int) -> Double): List<Pair<String, Double>> {
        val out = mutableListOf<Pair<String, Double>>()
        var day = 1
        var i = 0
        while (out.size < count) {
            // 2026-09 の1日から、土日(5,6,12,13,...)をざっくり避ける
            val dow = (day + 1) % 7
            if (dow != 0 && dow != 6) out += "2026-09-%02d".format(day) to value(i++)
            day++
        }
        return out
    }
}
