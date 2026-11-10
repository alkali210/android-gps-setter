package io.github.jqssun.gpssetter.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// A route in the Saved tab. The track is kept as GPX text (route/GpxParser.kt: toGpx / parseGpx), so
// exporting it is writing [gpx] out as-is. [meters] is stored so the list doesn't parse every track.
@Entity
data class SavedRoute(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val gpx: String,
    val meters: Double,
)

@Dao
interface SavedRouteDao {
    // REPLACE so Undo after a delete can put the same row back
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(route: SavedRoute): Long

    @Delete
    suspend fun delete(route: SavedRoute)

    @Query("SELECT * FROM SavedRoute ORDER BY id DESC")
    fun getAll(): Flow<List<SavedRoute>>
}
