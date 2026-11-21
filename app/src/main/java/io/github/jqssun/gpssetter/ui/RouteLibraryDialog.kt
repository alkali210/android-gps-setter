package io.github.jqssun.gpssetter.ui

import android.app.Dialog
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.databinding.DialogRouteLibraryBinding
import io.github.jqssun.gpssetter.room.RouteSummary
import io.github.jqssun.gpssetter.room.SavedRoute
import io.github.jqssun.gpssetter.room.SavedRouteDao
import io.github.jqssun.gpssetter.route.MAX_ROUTE_FILE_MIB
import io.github.jqssun.gpssetter.route.RouteFileTooLargeException
import io.github.jqssun.gpssetter.route.RouteDocument
import io.github.jqssun.gpssetter.route.importRoute
import io.github.jqssun.gpssetter.route.parseGpx
import io.github.jqssun.gpssetter.route.readRouteText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

// The undo payload can be a large track; keep it across rotations, never in a Bundle.
class RouteUndoState : ViewModel() {
    var deleted: SavedRoute? = null
}

@AndroidEntryPoint
class RouteLibraryDialog : DialogFragment() {
    @Inject lateinit var routes: SavedRouteDao
    private val undoState by lazy { ViewModelProvider(this)[RouteUndoState::class.java] }
    private val map get() = requireActivity() as BaseMapActivity
    private lateinit var binding: DialogRouteLibraryBinding
    private var actionDialog: AlertDialog? = null
    private var exportId = 0L

    private val openRoute = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val resolver = requireContext().contentResolver
        val fallback = getString(R.string.library_default_name)
        perform(R.string.library_import_failed) {
            val row = withContext(Dispatchers.IO) {
                val text = resolver.openInputStream(uri)?.use { it.readRouteText() } ?: throw IOException()
                val doc = importRoute(text) ?: throw IOException()
                SavedRoute.from(doc.name ?: resolver.documentName(uri) ?: fallback, doc)
            }
            routes.insert(row)
        }
    }

    private val createGpx = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val id = exportId
        exportId = 0L
        if (uri == null) return@registerForActivityResult
        val resolver = requireContext().contentResolver
        perform(R.string.export_failed) {
            val route = routes.find(id) ?: throw IOException()
            withContext(Dispatchers.IO) {
                val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException()
                stream.bufferedWriter().use { it.write(route.gpx) }
            }
            message(R.string.library_exported)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        exportId = savedInstanceState?.getLong("exportId") ?: 0L
        binding = DialogRouteLibraryBinding.inflate(LayoutInflater.from(requireContext()))
        binding.importRoute.setOnClickListener { openRoute.launch(arrayOf("*/*")) }
        val planned = map.plannedRoute()
        binding.savePlanned.isVisible = planned != null
        if (planned != null) binding.savePlanned.setOnClickListener { nameRoute(planned) }
        binding.undoDelete.setOnClickListener { undoDelete() }
        renderUndo()
        binding.routes.emptyView = binding.emptyRoutes
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                routes.summaries().collect { rows ->
                    val labels = rows.map { getString(R.string.library_summary, it.name, it.meters / 1000) }
                    binding.routes.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)
                    binding.routes.setOnItemClickListener { _, _, position, _ -> actions(rows[position]) }
                }
            }
        }
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.saved_routes).setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null).create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("exportId", exportId)
    }

    override fun onDestroyView() {
        actionDialog?.dismiss()
        actionDialog = null
        super.onDestroyView()
    }

    private fun nameRoute(planned: RouteDocument) {
        val input = EditText(requireContext()).apply {
            setSingleLine()
            hint = getString(R.string.library_name)
            setText(planned.name ?: getString(R.string.library_default_name))
            selectAll()
        }
        actionDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.library_save_planned)
            .setView(input).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim().ifBlank { getString(R.string.library_default_name) }
                perform(R.string.library_save_failed) {
                    routes.insert(withContext(Dispatchers.Default) { SavedRoute.from(name, planned) })
                }
            }.show()
    }

    private fun actions(row: RouteSummary) {
        val options = arrayOf(getString(R.string.play_route), getString(R.string.library_export), getString(R.string.library_delete))
        actionDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(row.name)
            .setItems(options) { _, action ->
                when (action) {
                    0 -> perform(R.string.library_unavailable) {
                        val saved = routes.find(row.id) ?: throw IOException()
                        val points = withContext(Dispatchers.Default) { parseGpx(saved.gpx) }
                        if (points.size < 2) throw IOException()
                        dismiss()
                        map.playSavedRoute(points)
                    }
                    1 -> {
                        exportId = row.id
                        createGpx.launch(row.name.replace('/', '_') + ".gpx")
                    }
                    2 -> perform(R.string.library_unavailable) {
                        val removed = routes.remove(row.id) ?: return@perform
                        undoState.deleted = removed
                        renderUndo()
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun renderUndo() {
        val deleted = undoState.deleted
        binding.undoDelete.isVisible = deleted != null
        if (deleted != null) binding.undoDelete.text = getString(R.string.library_undo, deleted.name)
    }

    private fun undoDelete() {
        val row = undoState.deleted ?: return
        binding.undoDelete.isEnabled = false
        perform(R.string.library_save_failed) {
            try {
                // Always allocate a fresh ID; an undo must never replace an existing row.
                routes.insert(row.copy(id = 0))
                if (undoState.deleted === row) undoState.deleted = null
                renderUndo()
            } finally {
                binding.undoDelete.isEnabled = true
            }
        }
    }

    private fun perform(error: Int, action: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: RouteFileTooLargeException) {
                Toast.makeText(requireContext(), getString(R.string.route_file_too_large, MAX_ROUTE_FILE_MIB), Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                message(error)
            }
        }
    }

    private fun message(resource: Int) {
        Toast.makeText(requireContext(), resource, Toast.LENGTH_LONG).show()
    }
}

private fun ContentResolver.documentName(uri: Uri): String? =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.')?.takeUnless { it.isBlank() } else null
    }
