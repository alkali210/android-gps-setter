package io.github.jqssun.gpssetter.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Favorite::class, SavedRoute::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun savedRouteDao(): SavedRouteDao

    companion object {
        // v2 adds the Saved tab's routes. Favorites are untouched; without this the destructive
        // fallback in AppModule would wipe them. The SQL must match what Room generates for SavedRoute.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `SavedRoute` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `gpx` TEXT NOT NULL, `meters` REAL NOT NULL)"
                )
            }
        }
    }
}
