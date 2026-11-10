package io.github.jqssun.gpssetter.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.room.Favorite
import java.util.Locale

class FavListAdapter(
    ) : ListAdapter<Favorite,FavListAdapter.ViewHolder>(FavListComparetor()) {

    var onItemClick : ((Favorite) -> Unit)? = null   // show it on the Teleport map
    var onTeleport : ((Favorite) -> Unit)? = null    // show it and start spoofing there
    var onItemDelete : ((Favorite) -> Unit)? = null

   inner class ViewHolder(view: View): RecyclerView.ViewHolder(view) {

        private val address: TextView = view.findViewById(R.id.address)
        private val coords: TextView = view.findViewById(R.id.coords)
        private val delete: ImageView = itemView.findViewById(R.id.del)

        fun bind(favorite: Favorite){
            address.text = favorite.address
            coords.text = String.format(Locale.US, "%.6f, %.6f", favorite.lat ?: 0.0, favorite.lng ?: 0.0)
            delete.setOnClickListener {
                onItemDelete?.invoke(favorite)
            }
            itemView.setOnClickListener {
                onItemClick?.invoke(favorite)
            }
            itemView.findViewById<View>(R.id.teleport).setOnClickListener {
                onTeleport?.invoke(favorite)
            }
        }
    }

    class FavListComparetor : DiffUtil.ItemCallback<Favorite>() {
        override fun areItemsTheSame(oldItem: Favorite, newItem: Favorite): Boolean {
            return oldItem.address == newItem.address
        }

        override fun areContentsTheSame(oldItem: Favorite, newItem: Favorite): Boolean {
            return oldItem == newItem
        }

    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.fav_items, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        if (item != null){
            holder.bind(item)

        }

    }

}