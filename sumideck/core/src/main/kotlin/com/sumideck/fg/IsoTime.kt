package com.sumideck.fg

/**
 * `2026-09-18T06:41:12+09:00` 形式だけを読む最小のパーサ。
 * java.time に依存しないので Android の低い minSdk でもそのまま動く。
 * 読めなければ null を返し、呼び出し側は「判定不能」として扱う(勝手に古いと言わない)。
 */
object IsoTime {
    private val PATTERN = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|z|[+-]\d{2}:?\d{2})?$"""
    )

    fun toEpochSecondsOrNull(text: String): Long? {
        val m = PATTERN.matchEntire(text.trim()) ?: return null
        val g = m.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        val hour = g[4].toInt()
        val minute = g[5].toInt()
        val second = g[6].toInt()
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
        val offset = parseOffset(g.getOrNull(7)) ?: return null
        return daysFromCivil(year, month, day) * 86400L + hour * 3600L + minute * 60L + second - offset
    }

    private fun parseOffset(raw: String?): Long? {
        if (raw.isNullOrEmpty() || raw.equals("Z", ignoreCase = true)) return 0L
        val sign = if (raw[0] == '-') -1L else 1L
        val body = raw.substring(1).replace(":", "")
        if (body.length != 4) return null
        val hh = body.substring(0, 2).toIntOrNull() ?: return null
        val mm = body.substring(2, 4).toIntOrNull() ?: return null
        return sign * (hh * 3600L + mm * 60L)
    }

    /** Howard Hinnant の days_from_civil。1970-01-01 からの日数。 */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }
}
