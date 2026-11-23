package io.github.jqssun.gpssetter.route

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.math.BigDecimal
import javax.xml.parsers.SAXParserFactory

data class RouteStop(val point: RoutePoint, val name: String?)
data class RouteDocument(
    val name: String?,
    val points: List<RoutePoint>,
    val stops: List<RouteStop> = emptyList(),
)

// Keep the existing playback entry point: stop markers are metadata, not extra track vertices.
fun parseGpx(text: String): List<RoutePoint> = readGpx(text)?.points.orEmpty()

internal fun readGpx(text: String): RouteDocument? {
    val source = text.removePrefix("\uFEFF")
    // GPX never needs a DTD. Reject it before the XML reader can expand any entities.
    if ("<!DOCTYPE" in source) return null
    val handler = GpxReader()
    return try {
        SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser()
            .parse(InputSource(StringReader(source)), handler)
        handler.document()
    } catch (_: SAXException) {
        null
    }
}

private class GpxReader : DefaultHandler() {
    private val points = mutableListOf<RoutePoint>()
    private val stops = mutableListOf<RouteStop>()
    private var name: String? = null
    private val path = ArrayList<String>()
    private val value = StringBuilder()
    private var valueDepth = 0
    private var pointDepth = 0
    private var lat: Double? = null
    private var lon: Double? = null
    private var elevation = 0.0
    private var label: String? = null
    private var isStop = false

    override fun startElement(uri: String, local: String, qName: String, attrs: Attributes) {
        val parent = path.lastOrNull()
        if (parent == null && local != "gpx") throw SAXException("Not GPX")
        path.add(local)
        if (pointDepth == 0 && (local == "trkpt" || local == "rtept" || local == "wpt")) {
            pointDepth = path.size
            lat = attrs.getValue("lat")?.toDoubleOrNull()
            lon = attrs.getValue("lon")?.toDoubleOrNull()
            elevation = 0.0
            label = null
            isStop = false
        } else if ((pointDepth != 0 && path.size == pointDepth + 1 &&
                (local == "name" || local == "ele" || local == "type")) ||
            (local == "name" && (parent == "metadata" || parent == "trk" || parent == "rte" || parent == "gpx"))) {
            valueDepth = path.size
            value.setLength(0)
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (valueDepth == path.size) value.append(ch, start, length)
    }

    override fun endElement(uri: String, local: String, qName: String) {
        if (valueDepth == path.size) {
            val text = value.toString()
            when (local) {
                "ele" -> elevation = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
                "type" -> isStop = text.trim() == "stop"
                "name" -> if (pointDepth != 0) label = text.takeUnless { it.isBlank() }
                    else if (name == null) name = text.takeUnless { it.isBlank() }
            }
            valueDepth = 0
        }
        if (pointDepth == path.size) {
            val latitude = lat
            val longitude = lon
            if (latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                val point = RoutePoint(latitude, longitude, elevation)
                if (local == "wpt" && isStop) stops.add(RouteStop(point, label)) else points.add(point)
            }
            pointDepth = 0
        }
        path.removeAt(path.lastIndex)
    }

    fun document() = RouteDocument(name, points, stops)
}

fun RouteDocument.toGpx(title: String): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<gpx version=\"1.1\" creator=\"GPS Setter\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    append("<metadata><name>").xmlText(title).append("</name></metadata>\n")
    for ((point, label) in stops) {
        pointElement("wpt", point)
        if (label != null) append("<name>").xmlText(label).append("</name>")
        append("<type>stop</type></wpt>\n")
    }
    append("<trk><trkseg>\n")
    for (point in points) pointElement("trkpt", point).append("</trkpt>\n")
    append("</trkseg></trk></gpx>\n")
}

private fun StringBuilder.pointElement(tag: String, p: RoutePoint): StringBuilder =
    append('<').append(tag).append(" lat=\"").appendDecimal(p.lat).append("\" lon=\"").appendDecimal(p.lng)
        .append("\"><ele>").appendDecimal(p.altitude).append("</ele>")

private fun StringBuilder.appendDecimal(value: Double): StringBuilder {
    val text = value.toString()
    return append(if ('E' in text) BigDecimal(text).toPlainString() else text)
}

// XML 1.0 permits supplementary characters, but not unpaired surrogates or most C0 controls.
internal fun String.xmlSafeText(): String {
    var safe: StringBuilder? = null
    var index = 0
    while (index < length) {
        val cp = codePointAt(index)
        if (cp == 9 || cp == 10 || cp == 13 || cp in 0x20..0xD7FF ||
            cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF) {
            safe?.appendCodePoint(cp)
        } else {
            if (safe == null) safe = StringBuilder(length).append(this, 0, index)
            safe.append('\uFFFD')
        }
        index += Character.charCount(cp)
    }
    return safe?.toString() ?: this
}

private fun StringBuilder.xmlText(text: String): StringBuilder = apply {
    for (c in text.xmlSafeText()) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '\r' -> append("&#13;")
        else -> append(c)
    }
}
