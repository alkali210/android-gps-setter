package io.github.jqssun.gpssetter.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.room.SavedRoute
import io.github.jqssun.gpssetter.route.RouteMath
import io.github.jqssun.gpssetter.ui.RouteSketchView

// a saved route plus its thumbnail shape (RouteMath.sketch), worked out off the main thread
class RouteCard(val route: SavedRoute, val sketch: FloatArray)

// Saved routes: open in the Routes tab, export as GPX, delete
class RouteListAdapter : ListAdapter<RouteCard, RouteListAdapter.ViewHolder>(Diff) {

    var onOpen: ((SavedRoute) -> Unit)? = null
    var onExport: ((SavedRoute) -> Unit)? = null
    var onDelete: ((SavedRoute) -> Unit)? = null

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val sketch: RouteSketchView = view.findViewById(R.id.route_sketch)
        private val name: TextView = view.findViewById(R.id.route_name)
        private val meta: TextView = view.findViewById(R.id.route_meta)

        fun bind(card: RouteCard) {
            val r = card.route
            sketch.points = card.sketch
            name.text = r.name
            meta.text = RouteMath.formatDistance(r.meters)
            itemView.setOnClickListener { onOpen?.invoke(r) }
            itemView.findViewById<View>(R.id.route_open).setOnClickListener { onOpen?.invoke(r) }
            itemView.findViewById<View>(R.id.route_export).setOnClickListener { onExport?.invoke(r) }
            itemView.findViewById<View>(R.id.route_delete).setOnClickListener { onDelete?.invoke(r) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<RouteCard>() {
        override fun areItemsTheSame(a: RouteCard, b: RouteCard) = a.route.id == b.route.id
        override fun areContentsTheSame(a: RouteCard, b: RouteCard) = a.route == b.route
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.route_items, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))
}
