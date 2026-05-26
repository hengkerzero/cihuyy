package io.github.jqssun.gpssetter.shopeefood.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.shopeefood.model.Restaurant

/**
 * RecyclerView Adapter untuk menampilkan daftar restoran ShopeeFood.
 * Menampilkan: Nama, Alamat, dan Jarak.
 */
class RestaurantAdapter(
    private val onItemClick: (Restaurant) -> Unit
) : ListAdapter<Restaurant, RestaurantAdapter.RestaurantViewHolder>(RestaurantDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RestaurantViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_restaurant, parent, false)
        return RestaurantViewHolder(view)
    }

    override fun onBindViewHolder(holder: RestaurantViewHolder, position: Int) {
        val restaurant = getItem(position)
        holder.bind(restaurant)
    }

    inner class RestaurantViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_restaurant_name)
        private val tvAddress: TextView = itemView.findViewById(R.id.tv_restaurant_address)
        private val tvDistance: TextView = itemView.findViewById(R.id.tv_restaurant_distance)
        private val tvCoords: TextView = itemView.findViewById(R.id.tv_restaurant_coords)

        fun bind(restaurant: Restaurant) {
            tvName.text = restaurant.name
            tvAddress.text = restaurant.address
            tvDistance.text = restaurant.distanceText
            tvCoords.text = "${restaurant.latitude}, ${restaurant.longitude}"

            itemView.setOnClickListener {
                onItemClick(restaurant)
            }
        }
    }

    class RestaurantDiffCallback : DiffUtil.ItemCallback<Restaurant>() {
        override fun areItemsTheSame(oldItem: Restaurant, newItem: Restaurant): Boolean {
            return oldItem.merchantId == newItem.merchantId
        }

        override fun areContentsTheSame(oldItem: Restaurant, newItem: Restaurant): Boolean {
            return oldItem == newItem
        }
    }
}
