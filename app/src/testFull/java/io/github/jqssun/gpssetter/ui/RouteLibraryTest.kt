package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.navigation.NavigationView
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.route.RouteDocument
import io.github.jqssun.gpssetter.route.MAX_ROUTE_FILE_BYTES
import io.github.jqssun.gpssetter.route.MAX_ROUTE_FILE_MIB
import io.github.jqssun.gpssetter.route.RoutePlaybackService
import io.github.jqssun.gpssetter.route.RoutePoint
import io.github.jqssun.gpssetter.route.RouteStop
import io.github.jqssun.gpssetter.route.importRoute
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.flow.first
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RouteLibraryTest {
    @Test fun savingPlannedRouteStoresTheRoadRatherThanAStraightLine() {
        val road = listOf(RoutePoint(1.0, 2.0), RoutePoint(1.8, 2.2, 34.5), RoutePoint(2.0, 3.0))
        Robolectric.buildActivity(MapActivity::class.java).setup().use { controller ->
            val map = controller.get()
            assertNull(map.plannedRoute())
            map.findViewById<View>(R.id.mode_route).performClick()
            // Supply a completed routing response without contacting either directions provider.
            fun set(field: String, value: Any) =
                BaseMapActivity::class.java.getDeclaredField(field).apply { isAccessible = true }.set(map, value)
            set("roadPoints", road)
            set("aPoint", 1.0 to 2.0)
            set("bPoint", 2.0 to 3.0)
            set("aName", "Depot")
            set("bName", "Park")
            map.findViewById<View>(R.id.route_start).isEnabled = true
            map.findViewById<NavigationView>(R.id.nav_view).menu.performIdentifierAction(R.id.saved_routes, 0)
            map.supportFragmentManager.executePendingTransactions()
            val library = map.supportFragmentManager.findFragmentByTag("route_library") as RouteLibraryDialog
            library.requireDialog().findViewById<View>(R.id.save_planned).performClick()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val list = library.requireDialog().findViewById<ListView>(R.id.routes)
            await("saved road geometry") { list.count == 1 }
            val stored = kotlinx.coroutines.runBlocking {
                val row = library.routes.summaries().first().single()
                importRoute(library.routes.find(row.id)!!.gpx)
            }
            assertEquals(RouteDocument("Depot → Park", road,
                listOf(RouteStop(road.first(), "Depot"), RouteStop(road.last(), "Park"))), stored)
        }
    }

    @Test fun importDeleteUndoExportAndPlayThroughTheExistingSetup() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)!!).setLocationEnabled(true)
        val file = """<gpx><metadata><name>Coast &amp; town</name></metadata>
            <wpt lat="1.5" lon="2.5"><ele>7</ele><name>Via &amp; back</name><type>stop</type></wpt>
            <wpt lat="2.5" lon="3.5"><name>Market</name><type>stop</type></wpt>
            <trk><trkseg><trkpt lat="1" lon="2"><ele>5</ele></trkpt><trkpt lat="2" lon="3"><ele>12</ele></trkpt>
            <trkpt lat="3" lon="4"><ele>9</ele></trkpt></trkseg></trk></gpx>"""
        val points = listOf(RoutePoint(1.0, 2.0, 5.0), RoutePoint(2.0, 3.0, 12.0), RoutePoint(3.0, 4.0, 9.0))
        val expected = RouteDocument("Coast & town", points, listOf(RouteStop(RoutePoint(1.5, 2.5, 7.0), "Via & back"),
            RouteStop(RoutePoint(2.5, 3.5), "Market")))
        val source = Uri.parse("content://documents/routes/source")
        val output = Uri.parse("content://documents/routes/output")
        val exported = ByteArrayOutputStream()
        shadowOf(app.contentResolver).registerInputStream(source, file.byteInputStream())
        shadowOf(app.contentResolver).registerOutputStream(output, exported)

        Robolectric.buildActivity(MapActivity::class.java).setup().use { controller ->
            val map = controller.get()
            val menu = map.findViewById<NavigationView>(R.id.nav_view).menu
            assertTrue(menu.performIdentifierAction(R.id.saved_routes, 0))
            map.supportFragmentManager.executePendingTransactions()
            val library = (map.supportFragmentManager.findFragmentByTag("route_library") as RouteLibraryDialog).requireDialog()
            val list = library.findViewById<ListView>(R.id.routes)
            fun picker(action: String, uri: Uri) {
                val launched = shadowOf(map).nextStartedActivityForResult
                assertEquals(action, launched.intent.action)
                assertTrue(map.activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_OK, Intent().setData(uri)))
            }
            fun choose(index: Int) {
                list.performItemClick(null, 0, 0)
                val actions = ShadowDialog.getLatestDialog() as AlertDialog
                actions.listView.performItemClick(null, index, index.toLong())
                shadowOf(Looper.getMainLooper()).idle()
            }
            library.findViewById<View>(R.id.import_route).performClick()
            picker(Intent.ACTION_OPEN_DOCUMENT, source)
            await("imported route") { list.count == 1 }
            choose(2)
            val undo = library.findViewById<View>(R.id.undo_delete)
            await("deleted route and undo") { list.count == 0 && undo.visibility == View.VISIBLE }
            undo.performClick()
            await("restored route") { list.count == 1 }
            choose(1)
            picker(Intent.ACTION_CREATE_DOCUMENT, output)
            await("exported GPX") { exported.size() != 0 }
            assertEquals(expected, importRoute(exported.toString("UTF-8")))
            choose(0)
            await("GPX setup dialog") { ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.gpx_setup_speed) != null }
            val setup = ShadowDialog.getLatestDialog() as AlertDialog
            setup.findViewById<EditText>(R.id.gpx_setup_speed)!!.setText("72")
            setup.findViewById<EditText>(R.id.gpx_update_interval)!!.setText("500")
            setup.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val start = shadowOf(app).nextStartedService
            assertEquals(RoutePlaybackService::class.java.name, start.component!!.className)
            assertArrayEquals(points.map { it.lat }.toDoubleArray(), start.getDoubleArrayExtra("lat"), 0.0)
            assertArrayEquals(points.map { it.lng }.toDoubleArray(), start.getDoubleArrayExtra("lng"), 0.0)
            assertArrayEquals(points.map { it.altitude }.toDoubleArray(), start.getDoubleArrayExtra("ele"), 0.0)
            assertEquals(20f, start.getFloatExtra("speed", 0f), 0f)
            assertEquals(500L, start.getLongExtra("update_interval_ms", 0))
            assertTrue(start.getBooleanExtra("gpx", false))
            Robolectric.buildService(RoutePlaybackService::class.java).create().destroy()
        }
    }

    @Test fun rejectsAnOversizedDocumentWithoutSavingIt() {
        val app = RuntimeEnvironment.getApplication()
        val source = Uri.parse("content://documents/routes/large")
        val document = ByteArray(MAX_ROUTE_FILE_BYTES + 1) { ' '.code.toByte() }
        """{"type":"LineString","coordinates":[[1,2],[3,4]]}""".toByteArray().copyInto(document)
        shadowOf(app.contentResolver).registerInputStream(source, document.inputStream())
        Robolectric.buildActivity(MapActivity::class.java).setup().use { controller ->
            val map = controller.get()
            map.findViewById<NavigationView>(R.id.nav_view).menu.performIdentifierAction(R.id.saved_routes, 0)
            map.supportFragmentManager.executePendingTransactions()
            val library = map.supportFragmentManager.findFragmentByTag("route_library") as RouteLibraryDialog
            library.requireDialog().findViewById<View>(R.id.import_route).performClick()
            val picker = shadowOf(map).nextStartedActivityForResult
            map.activityResultRegistry.dispatchResult(picker.requestCode, Activity.RESULT_OK, Intent().setData(source))
            val error = map.getString(R.string.route_file_too_large, MAX_ROUTE_FILE_MIB)
            await("file size error") { ShadowToast.getTextOfLatestToast() == error }
            assertTrue(kotlinx.coroutines.runBlocking { library.routes.summaries().first().isEmpty() })
        }
    }

    private fun await(description: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (ready()) return
            if (System.nanoTime() >= deadline) fail("Timed out waiting for $description")
            Thread.sleep(10)
        }
    }
}
