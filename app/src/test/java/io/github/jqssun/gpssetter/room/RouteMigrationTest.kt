package io.github.jqssun.gpssetter.room

import android.app.Application
import android.content.Context
import io.github.jqssun.gpssetter.module.AppModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RouteMigrationTest {
    @Test fun productionBuilderMigratesV1InPlaceAndCanUndoWithoutReplacingAnotherRoute() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val filename = "user_database"
        app.deleteDatabase(filename)
        app.openOrCreateDatabase(filename, Context.MODE_PRIVATE, null).use { old ->
            // Exact Favorite schema produced by Room in version 1, including its nullable primary key.
            old.execSQL("CREATE TABLE Favorite (id INTEGER, address TEXT, lat REAL, lng REAL, PRIMARY KEY(id))")
            old.execSQL("INSERT INTO Favorite VALUES (3, 'Tile 3', 12.25, -73.5)")
            old.version = 1
        }
        val db = AppModule.provideDatabase(app)
        try {
            assertEquals(listOf(Favorite(3, "Tile 3", 12.25, -73.5)), db.favoriteDao().getAllOnce())
            assertEquals(2, db.openHelper.readableDatabase.version)
            val routes = db.savedRouteDao()
            // Cross the chunk boundary with a non-BMP character; SQLite and JVM string offsets differ.
            val text = "<name>\uD800\uDF30</name>" + "a".repeat(150_000)
            val original = SavedRoute(name = "First", gpx = text, meters = 42.0)
            val id = routes.insert(original)
            val removed = routes.remove(id)!!
            assertEquals(original.copy(id = id), removed)
            assertNull(routes.find(id))
            val replacement = original.copy(id = id, name = "Replacement")
            routes.insert(replacement)
            val restoredId = routes.insert(removed.copy(id = 0))
            assertEquals(replacement, routes.find(id))
            assertEquals(original.copy(id = restoredId), routes.find(restoredId))
        } finally {
            db.close()
            app.deleteDatabase(filename)
        }
        Unit
    }
}
