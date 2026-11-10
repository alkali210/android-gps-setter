package io.github.jqssun.gpssetter.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XshareTest {
    private fun parse(text: String) = Xshare.parse(text.byteInputStream())

    @Test fun completeFileParses() {
        val p = parse("start=true\nlatitude_d=4625614447572637508\njitter_m=1.5\naccuracy_level=10\nend=1\n")!!
        assertEquals("true", p.getProperty("start"))
        assertEquals(4625614447572637508L, p.getProperty("latitude_d").toLong())
        assertEquals(1.5f, p.getProperty("jitter_m").toFloat())
    }

    // a read that races the app's truncate-then-write sees no end marker and is ignored
    @Test fun halfWrittenOrEmptyFileIsIgnored() {
        assertNull(parse("start=true\nlatitude_d=46256"))
        assertNull(parse(""))
    }
}
