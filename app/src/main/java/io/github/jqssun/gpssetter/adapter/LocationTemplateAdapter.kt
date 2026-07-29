package io.github.jqssun.gpssetter.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.jqssun.gpssetter.databinding.ItemLocationTemplateBinding
import io.github.jqssun.gpssetter.model.LocationTemplate

class LocationTemplateAdapter(
    private val onClick: (LocationTemplate) -> Unit,
    private val onLongClick: (LocationTemplate) -> Boolean
) : ListAdapter<LocationTemplate, LocationTemplateAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LocationTemplate>() {
            override fun areItemsTheSame(a: LocationTemplate, b: LocationTemplate) = a.id == b.id
            override fun areContentsTheSame(a: LocationTemplate, b: LocationTemplate) = a == b
        }
    }

    inner class ViewHolder(private val binding: ItemLocationTemplateBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: LocationTemplate) {
            binding.tvTemplateName.text = item.name
            binding.tvTemplateCoords.text = String.format(
                "%.4f, %.4f · Acc: %.0fm",
                item.lat, item.lng, item.accuracy
            )

            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { onLongClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLocationTemplateBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
