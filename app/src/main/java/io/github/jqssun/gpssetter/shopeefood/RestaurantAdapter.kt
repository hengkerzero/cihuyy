package io.github.jqssun.gpssetter.shopeefood

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.jqssun.gpssetter.R

/**
 * Adapter untuk RecyclerView hasil pencarian restoran ShopeeFood.
 */
class RestaurantAdapter(
    private val onItemClick: (RestaurantResult) -> Unit
) : ListAdapter<RestaurantResult, RestaurantAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_restaurant, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.restaurant_name)
        private val addressText: TextView = itemView.findViewById(R.id.restaurant_address)
        private val ratingText: TextView = itemView.findViewById(R.id.restaurant_rating)
        private val distanceText: TextView = itemView.findViewById(R.id.restaurant_distance)
        private val statusText: TextView = itemView.findViewById(R.id.restaurant_status)
        private val image: ImageView = itemView.findViewById(R.id.restaurant_image)

        fun bind(item: RestaurantResult) {
            nameText.text = item.name
            addressText.text = item.address.ifBlank { "Alamat tidak tersedia" }

            // Rating
            if (item.rating > 0) {
                ratingText.text = String.format("★ %.1f", item.rating)
                ratingText.visibility = View.VISIBLE
            } else {
                ratingText.visibility = View.GONE
            }

            // Distance
            distanceText.text = if (item.distanceKm < 1) {
                "${(item.distanceKm * 1000).toInt()} m"
            } else {
                String.format("%.1f km", item.distanceKm)
            }

            // Status
            if (!item.isOpen) {
                statusText.text = "Tutup"
                statusText.setTextColor(0xFFF44336.toInt())
                statusText.visibility = View.VISIBLE
            } else {
                statusText.visibility = View.GONE
            }

            // Placeholder image (no external image loader dependency)
            image.setImageResource(R.drawable.ic_baseline_directions_walk_24)
            image.setBackgroundResource(R.drawable.rounded_card_background)

            // Click handler
            itemView.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<RestaurantResult>() {
        override fun areItemsTheSame(oldItem: RestaurantResult, newItem: RestaurantResult): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: RestaurantResult, newItem: RestaurantResult): Boolean {
            return oldItem == newItem
        }
    }
}
