package com.sumideck.fg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IsoTimeTest {

    @Test fun `JSTのオフセットを解釈する`() {
        val jst = IsoTime.toEpochSecondsOrNull("2026-09-18T06:41:12+09:00")
        val utc = IsoTime.toEpochSecondsOrNull("2026-09-17T21:41:12Z")
        assertEquals(utc, jst)
    }

    @Test fun `コロン無しのオフセットも読む`() {
        assertEquals(
            IsoTime.toEpochSecondsOrNull("2026-09-18T06:41:12+09:00"),
            IsoTime.toEpochSecondsOrNull("2026-09-18T06:41:12+0900"),
        )
    }

    @Test fun `既知の値と一致する`() {
        // 2026-09-18T00:00:00Z
        assertEquals(1789689600L, IsoTime.toEpochSecondsOrNull("2026-09-18T00:00:00Z"))
    }

    @Test fun `読めない文字列はnull`() {
        assertNull(IsoTime.toEpochSecondsOrNull("2026-09-18"))
        assertNull(IsoTime.toEpochSecondsOrNull(""))
        assertNull(IsoTime.toEpochSecondsOrNull("2026-13-01T00:00:00Z"))
    }
}
