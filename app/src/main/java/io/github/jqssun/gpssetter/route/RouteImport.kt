package io.github.jqssun.gpssetter.route

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Recognize content, not provider MIME types (many file pickers report GPX as plain XML). */
fun importRoute(text: String): RouteDocument? {
    val source = text.removePrefix("\uFEFF")
    val document = when (source.firstOrNull { !it.isWhitespace() }) {
        '<' -> readGpx(source)
        '{' -> readGeoJson(source)
        else -> null
    }
    return document?.takeIf { it.points.size >= 2 }
}

@Suppress("DEPRECATION")
private fun readGeoJson(text: String): RouteDocument? = try {
    val root = JsonParser().parse(text).asJsonObject
    val points = ArrayList<RoutePoint>()
    var name: String? = null

    fun line(positions: JsonArray) {
        require(positions.size() >= 2)
        for (entry in positions) {
            val coords = entry.asJsonArray
            val lng = coords[0].asDouble
            val lat = coords[1].asDouble
            val elevation = if (coords.size() > 2) coords[2].asDouble else 0.0
            require(lng in -180.0..180.0 && lat in -90.0..90.0 && elevation.isFinite())
            points.add(RoutePoint(lat, lng, elevation))
        }
    }

    fun geometry(shape: JsonObject) {
        when (shape.get("type")?.asString) {
            "LineString" -> line(shape.getAsJsonArray("coordinates"))
            "MultiLineString" -> for (part in shape.getAsJsonArray("coordinates")) line(part.asJsonArray)
        }
    }

    fun feature(item: JsonObject) {
        val shape = item.get("geometry")?.takeIf { it.isJsonObject } ?: return
        val before = points.size
        geometry(shape.asJsonObject)
        if (name == null && points.size > before) {
            name = item.get("properties")?.takeIf { it.isJsonObject }?.asJsonObject?.get("name")
                ?.takeIf { it.isJsonPrimitive }?.asString?.takeUnless { it.isBlank() }
        }
    }

    when (root.get("type")?.asString) {
        "FeatureCollection" -> for (item in root.getAsJsonArray("features")) feature(item.asJsonObject)
        "Feature" -> feature(root)
        else -> geometry(root)
    }
    RouteDocument(name, points)
} catch (_: RuntimeException) {
    null // malformed JSON, missing arrays or an invalid position
}
