package io.github.jqssun.gpssetter.adapter

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.databinding.ItemAppPickerBinding

data class AppPickerItem(
    val packageName: String,
    val appName: String,
    val isSelected: Boolean = false
)

class AppPickerAdapter(
    private val onSelectionChanged: (Set<String>) -> Unit
) : ListAdapter<AppPickerItem, AppPickerAdapter.ViewHolder>(DIFF) {

    private val selectedPackages = mutableSetOf<String>()

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppPickerItem>() {
            override fun areItemsTheSame(a: AppPickerItem, b: AppPickerItem) =
                a.packageName == b.packageName
            override fun areContentsTheSame(a: AppPickerItem, b: AppPickerItem) = a == b
        }
    }

    fun getSelectedPackages(): Set<String> = selectedPackages.toSet()

    fun setSelectedPackages(packages: Set<String>) {
        selectedPackages.clear()
        selectedPackages.addAll(packages)
        notifyDataSetChanged()
    }

    inner class ViewHolder(private val binding: ItemAppPickerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AppPickerItem) {
            binding.tvPickerName.text = item.appName
            binding.tvPickerPackage.text = item.packageName

            // Load app icon
            try {
                val pm = binding.root.context.packageManager
                val icon = pm.getApplicationIcon(item.packageName)
                binding.ivPickerIcon.setImageDrawable(icon)
            } catch (e: PackageManager.NameNotFoundException) {
                binding.ivPickerIcon.setImageResource(R.mipmap.ic_launcher)
            }

            binding.cbApp.isChecked = item.packageName in selectedPackages

            val clickAction = {
                if (item.packageName in selectedPackages) {
                    selectedPackages.remove(item.packageName)
                } else {
                    selectedPackages.add(item.packageName)
                }
                binding.cbApp.isChecked = item.packageName in selectedPackages
                onSelectionChanged(selectedPackages)
            }

            binding.cbApp.setOnClickListener { clickAction() }
            binding.root.setOnClickListener { clickAction() }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppPickerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
