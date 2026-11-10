package io.github.jqssun.gpssetter.route

// ponytail: regex over GPX point elements (trkpt/rtept/wpt). Standard exporters are regular enough;
// swap for an XmlPullParser if an exotic file breaks it. Pure, so it's unit-tested.
private val POINT = Regex("""<(trkpt|rtept|wpt)\b([^>]*?)(?:/>|>(.*?)</\1>)""", RegexOption.DOT_MATCHES_ALL)
private val LAT = Regex("""lat\s*=\s*["']([^"']+)["']""")
private val LON = Regex("""lon\s*=\s*["']([^"']+)["']""")
private val ELE = Regex("""<ele>\s*([^<]+)</ele>""")

fun parseGpx(text: String): List<RoutePoint> =
    POINT.findAll(text).mapNotNull { m ->
        val attrs = m.groupValues[2]
        val lat = LAT.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
        val lon = LON.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
        val ele = m.groupValues[3].let { ELE.find(it)?.groupValues?.get(1)?.trim()?.toDoubleOrNull() } ?: 0.0
        RoutePoint(lat, lon, ele)
    }.toList()

private val NAME = Regex("""<name>\s*([^<]*?)\s*</name>""")

// The file's first <name> (the track's, in what toGpx writes), if it has one.
fun gpxName(text: String): String? =
    NAME.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&amp;", "&")

// A one-track GPX file of [points]; what Saved stores for a route and exports. Round-trips through parseGpx.
fun toGpx(name: String, points: List<RoutePoint>): String = buildString {
    val safe = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    append("""<gpx version="1.1" creator="GPS Setter" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
    append("<trk><name>").append(safe).append("</name><trkseg>\n")
    for (p in points) append("""<trkpt lat="${p.lat}" lon="${p.lng}"><ele>${p.altitude}</ele></trkpt>""").append('\n')
    append("</trkseg></trk>\n</gpx>\n")
}
