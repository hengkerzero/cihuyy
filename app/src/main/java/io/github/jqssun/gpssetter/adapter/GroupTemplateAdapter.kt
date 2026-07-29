package io.github.jqssun.gpssetter.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.databinding.ItemGroupTemplateBinding
import io.github.jqssun.gpssetter.model.AppGroupTemplate

class GroupTemplateAdapter(
    private val onClick: (AppGroupTemplate) -> Unit,
    private val onLongClick: (AppGroupTemplate) -> Boolean
) : ListAdapter<AppGroupTemplate, GroupTemplateAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppGroupTemplate>() {
            override fun areItemsTheSame(a: AppGroupTemplate, b: AppGroupTemplate) = a.id == b.id
            override fun areContentsTheSame(a: AppGroupTemplate, b: AppGroupTemplate) = a == b
        }
    }

    inner class ViewHolder(private val binding: ItemGroupTemplateBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AppGroupTemplate) {
            binding.tvGroupName.text = item.name
            binding.tvGroupCount.text = binding.root.context.getString(
                R.string.template_group_app_count, item.packages.size
            )

            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener { onLongClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGroupTemplateBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
