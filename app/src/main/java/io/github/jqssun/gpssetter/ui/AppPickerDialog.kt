package io.github.jqssun.gpssetter.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.adapter.AppPickerAdapter
import io.github.jqssun.gpssetter.adapter.AppPickerItem
import io.github.jqssun.gpssetter.databinding.DialogAppPickerBinding

class AppPickerDialog : DialogFragment() {

    private var _binding: DialogAppPickerBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: AppPickerAdapter
    private var allApps: List<AppPickerItem> = emptyList()
    private var onAppsSelected: ((Set<String>) -> Unit)? = null
    private var excludePackages: Set<String> = emptySet()

    companion object {
        private const val ARG_EXCLUDE = "exclude_packages"

        fun newInstance(excludePackages: Set<String> = emptySet()): AppPickerDialog {
            return AppPickerDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_EXCLUDE, ArrayList(excludePackages))
                }
            }
        }
    }

    fun setOnAppsSelectedListener(listener: (Set<String>) -> Unit) {
        onAppsSelected = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog_Alert)
        excludePackages = arguments?.getStringArrayList(ARG_EXCLUDE)?.toSet() ?: emptySet()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogAppPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSearch()
        setupButtons()
        loadApps()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.8).toInt()
            )
        }
    }

    private fun setupRecyclerView() {
        adapter = AppPickerAdapter { selectedPackages ->
            binding.btnAddSelected.isEnabled = selectedPackages.isNotEmpty()
            binding.btnAddSelected.text = if (selectedPackages.isNotEmpty()) {
                getString(R.string.scope_add_selected) + " (${selectedPackages.size})"
            } else {
                getString(R.string.scope_add_selected)
            }
        }
        binding.rvAppPicker.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAppPicker.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupButtons() {
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnAddSelected.setOnClickListener {
            onAppsSelected?.invoke(adapter.getSelectedPackages())
            dismiss()
        }
    }

    private fun loadApps() {
        val pm = requireContext().packageManager
        val ownPkg = requireContext().packageName

        allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app ->
                pm.getLaunchIntentForPackage(app.packageName) != null &&
                    app.packageName != ownPkg &&
                    app.packageName !in excludePackages
            }
            .map { app ->
                AppPickerItem(
                    packageName = app.packageName,
                    appName = pm.getApplicationLabel(app).toString()
                )
            }
            .sortedBy { it.appName.lowercase() }

        adapter.submitList(allApps)
    }

    private fun filterApps(query: String) {
        if (query.isEmpty()) {
            adapter.submitList(allApps)
        } else {
            val q = query.lowercase()
            adapter.submitList(allApps.filter {
                it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            })
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
