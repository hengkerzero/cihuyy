package io.github.jqssun.gpssetter.adapter

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.databinding.ItemScopeAppBinding
import io.github.jqssun.gpssetter.model.AppSpoofConfig

data class ScopeAppItem(
    val packageName: String,
    val appName: String,
    val config: AppSpoofConfig
)

class ScopeAppAdapter(
    private val onToggle: (String, Boolean) -> Unit,
    private val onClick: (ScopeAppItem) -> Unit
) : ListAdapter<ScopeAppItem, ScopeAppAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ScopeAppItem>() {
            override fun areItemsTheSame(a: ScopeAppItem, b: ScopeAppItem) =
                a.packageName == b.packageName
            override fun areContentsTheSame(a: ScopeAppItem, b: ScopeAppItem) = a == b
        }
    }

    inner class ViewHolder(private val binding: ItemScopeAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ScopeAppItem) {
            binding.tvAppName.text = item.appName
            binding.tvPackageName.text = item.packageName

            // Load app icon
            try {
                val pm = binding.root.context.packageManager
                val icon = pm.getApplicationIcon(item.packageName)
                binding.ivAppIcon.setImageDrawable(icon)
            } catch (e: PackageManager.NameNotFoundException) {
                binding.ivAppIcon.setImageResource(R.mipmap.ic_launcher)
            }

            // Location preview
            val cfg = item.config
            val ctx = binding.root.context
            binding.tvLocationPreview.text = when {
                cfg.overrideLat != null && cfg.overrideLng != null ->
                    ctx.getString(R.string.scope_location_manual, cfg.overrideLat, cfg.overrideLng)
                cfg.templateId != null ->
                    ctx.getString(R.string.scope_location_template, cfg.templateId?.take(8) ?: "")
                else -> ctx.getString(R.string.scope_location_none)
            }

            // Toggle
            binding.switchEnabled.isChecked = cfg.enabled
            binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
                onToggle(item.packageName, checked)
            }

            binding.root.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScopeAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
