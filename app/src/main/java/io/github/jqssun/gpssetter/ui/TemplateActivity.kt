package io.github.jqssun.gpssetter.ui

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.databinding.ActivityTemplateBinding
import io.github.jqssun.gpssetter.databinding.DialogLocationTemplateBinding
import io.github.jqssun.gpssetter.databinding.DialogGroupTemplateBinding
import io.github.jqssun.gpssetter.model.AppGroupTemplate
import io.github.jqssun.gpssetter.model.LocationTemplate
import io.github.jqssun.gpssetter.ui.viewmodel.TemplateViewModel
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TemplateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTemplateBinding
    private val viewModel: TemplateViewModel by viewModels()

    private val tabTitles by lazy {
        arrayOf(
            getString(R.string.template_tab_location),
            getString(R.string.template_tab_group)
        )
    }

    // SAF launchers for export/import
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            contentResolver.openOutputStream(it)?.let { out ->
                viewModel.exportTemplates(out) { success ->
                    if (success) showToast(getString(R.string.template_exported))
                    else showToast("Export gagal")
                }
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            contentResolver.openInputStream(it)?.let { input ->
                viewModel.importTemplates(input) { result ->
                    result.fold(
                        onSuccess = { count ->
                            showToast(getString(R.string.template_imported, count))
                        },
                        onFailure = { e ->
                            showToast(getString(R.string.template_import_error, e.message ?: "Unknown"))
                        }
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTemplateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupViewPager()
        setupFab()
    }

    private fun setupToolbar() {
        binding.toolbarTemplate.setNavigationOnClickListener { finish() }
        binding.toolbarTemplate.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export -> {
                    exportLauncher.launch("cihuyy_templates.json")
                    true
                }
                R.id.action_import -> {
                    importLauncher.launch(arrayOf("application/json"))
                    true
                }
                else -> false
            }
        }
    }

    private fun setupViewPager() {
        // Use childFragmentManager-equivalent via supportFragmentManager so Hilt
        // can properly inject @AndroidEntryPoint fragments inside ViewPager2
        binding.viewPager.adapter = TemplatePagerAdapter(this)

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()
    }

    /**
     * Named adapter class so Hilt-annotated fragments are correctly
     * instantiated via the FragmentManager (not plain constructors).
     */
    private class TemplatePagerAdapter(activity: AppCompatActivity) :
        FragmentStateAdapter(activity) {

        override fun getItemCount() = 2

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> LocationTemplatesFragment()
            1 -> GroupTemplatesFragment()
            else -> throw IllegalArgumentException("Invalid tab position: $position")
        }
    }

    private fun setupFab() {
        binding.fabAddTemplate.setOnClickListener {
            val currentTab = binding.viewPager.currentItem
            if (currentTab == 0) {
                showLocationTemplateDialog(null)
            } else {
                showGroupTemplateDialog(null)
            }
        }
    }

    fun showLocationTemplateDialog(existing: LocationTemplate?) {
        val dialogBinding = DialogLocationTemplateBinding.inflate(layoutInflater)

        existing?.let {
            dialogBinding.etTemplateName.setText(it.name)
            dialogBinding.etTplLat.setText(it.lat.toString())
            dialogBinding.etTplLng.setText(it.lng.toString())
            dialogBinding.etTplAccuracy.setText(it.accuracy.toString())
            dialogBinding.etTplAltitude.setText(it.altitude.toString())
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.dialog_button_add)) { _, _ ->
                val name = dialogBinding.etTemplateName.text.toString().trim()
                if (name.isEmpty()) {
                    showToast(getString(R.string.template_name_required))
                    return@setPositiveButton
                }
                val lat = dialogBinding.etTplLat.text.toString().toDoubleOrNull()
                val lng = dialogBinding.etTplLng.text.toString().toDoubleOrNull()
                if (lat == null || lng == null) {
                    showToast(getString(R.string.template_coords_required))
                    return@setPositiveButton
                }
                val accuracy = dialogBinding.etTplAccuracy.text.toString().toFloatOrNull() ?: 5f
                val altitude = dialogBinding.etTplAltitude.text.toString().toDoubleOrNull() ?: 0.0

                if (existing != null) {
                    viewModel.updateLocationTemplate(
                        existing.copy(
                            name = name, lat = lat, lng = lng,
                            accuracy = accuracy, altitude = altitude
                        )
                    )
                } else {
                    viewModel.addLocationTemplate(
                        LocationTemplate(name = name, lat = lat, lng = lng,
                            accuracy = accuracy, altitude = altitude)
                    )
                }
                showToast(getString(R.string.template_saved))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showGroupTemplateDialog(existing: AppGroupTemplate?) {
        val dialogBinding = DialogGroupTemplateBinding.inflate(layoutInflater)
        val selectedPackages = existing?.packages?.toMutableSet() ?: mutableSetOf()

        existing?.let {
            dialogBinding.etGroupName.setText(it.name)
            updateSelectedAppsText(dialogBinding, selectedPackages)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.dialog_button_add)) { _, _ ->
                val name = dialogBinding.etGroupName.text.toString().trim()
                if (name.isEmpty()) {
                    showToast(getString(R.string.template_name_required))
                    return@setPositiveButton
                }

                if (existing != null) {
                    viewModel.updateGroupTemplate(
                        AppGroupTemplate(
                            id = existing.id,
                            name = name,
                            packages = selectedPackages
                        )
                    )
                } else {
                    viewModel.addGroupTemplate(
                        AppGroupTemplate(name = name, packages = selectedPackages)
                    )
                }
                showToast(getString(R.string.template_saved))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialogBinding.btnPickApps.setOnClickListener {
            val picker = AppPickerDialog.newInstance()
            picker.setOnAppsSelectedListener { selected ->
                selectedPackages.clear()
                selectedPackages.addAll(selected)
                updateSelectedAppsText(dialogBinding, selectedPackages)
            }
            picker.show(supportFragmentManager, "group_app_picker")
        }

        dialog.show()
    }

    private fun updateSelectedAppsText(
        dialogBinding: DialogGroupTemplateBinding,
        packages: Set<String>
    ) {
        if (packages.isEmpty()) {
            dialogBinding.tvSelectedApps.text = getString(R.string.template_group_no_apps)
        } else {
            val pm = packageManager
            val names = packages.mapNotNull { pkg ->
                try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
            }
            dialogBinding.tvSelectedApps.text = names.joinToString(", ")
        }
    }
}
