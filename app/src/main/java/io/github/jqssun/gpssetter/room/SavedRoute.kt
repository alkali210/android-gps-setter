package io.github.jqssun.gpssetter.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import io.github.jqssun.gpssetter.route.RouteDocument
import io.github.jqssun.gpssetter.route.RouteMath
import io.github.jqssun.gpssetter.route.toGpx
import kotlinx.coroutines.flow.Flow

@Entity
data class SavedRoute(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val gpx: String,
    val meters: Double,
) {
    companion object {
        fun from(name: String, document: RouteDocument) = SavedRoute(
            name = name, gpx = document.toGpx(name), meters = RouteMath.pathLength(document.points)
        )
    }
}

data class RouteSummary(val id: Long, val name: String, val meters: Double)

@Dao
abstract class SavedRouteDao {
    @Insert
    abstract suspend fun insert(route: SavedRoute): Long

    @Query("SELECT id, name, meters FROM SavedRoute ORDER BY id DESC")
    abstract fun summaries(): Flow<List<RouteSummary>>

    @Query("SELECT id, name, meters FROM SavedRoute WHERE id = :id")
    protected abstract suspend fun summary(id: Long): RouteSummary?

    // A track can exceed Android's SQLite CursorWindow. Keep each result row below 1 MiB,
    // even for four-byte Unicode. SQLite substr() takes 1-based Unicode character offsets.
    @Query("SELECT substr(gpx, :offset, :size) FROM SavedRoute WHERE id = :id")
    protected abstract suspend fun gpxPart(id: Long, offset: Int, size: Int): String

    @Transaction
    open suspend fun find(id: Long): SavedRoute? {
        val row = summary(id) ?: return null
        val size = 128 * 1024
        val first = gpxPart(id, 1, size)
        val gpx = if (first.length < size) first else buildString {
            append(first)
            var offset = size + 1
            do {
                val part = gpxPart(id, offset, size)
                append(part)
                offset += size
            } while (part.length >= size)
        }
        return SavedRoute(row.id, row.name, gpx, row.meters)
    }

    @Query("DELETE FROM SavedRoute WHERE id = :id")
    protected abstract suspend fun delete(id: Long)

    @Transaction
    open suspend fun remove(id: Long): SavedRoute? = find(id)?.also { delete(id) }
}
