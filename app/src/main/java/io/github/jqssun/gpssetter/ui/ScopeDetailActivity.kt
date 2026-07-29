package io.github.jqssun.gpssetter.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.databinding.ActivityScopeDetailBinding
import io.github.jqssun.gpssetter.model.AppSpoofConfig
import io.github.jqssun.gpssetter.model.LocationTemplate
import io.github.jqssun.gpssetter.ui.viewmodel.ScopeViewModel
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ScopeDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScopeDetailBinding
    private val viewModel: ScopeViewModel by viewModels()
    private var packageName: String = ""
    private var currentConfig: AppSpoofConfig? = null
    private var availableTemplates: List<LocationTemplate> = emptyList()

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScopeDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: run {
            finish()
            return
        }

        setupToolbar()
        setupAppInfo()
        setupLocationSource()
        setupButtons()
        observeTemplates()
        loadConfig()
    }

    private fun setupToolbar() {
        binding.toolbarScopeDetail.setNavigationOnClickListener { finish() }
    }

    private fun setupAppInfo() {
        val pm = packageManager
        try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            binding.tvDetailAppName.text = pm.getApplicationLabel(appInfo)
            binding.ivDetailAppIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
        } catch (e: Exception) {
            binding.tvDetailAppName.text = packageName
        }
        binding.tvDetailPackage.text = packageName
    }

    private fun setupLocationSource() {
        binding.rgLocationSource.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_use_template -> {
                    binding.cardTemplateSelection.visibility = View.VISIBLE
                    binding.cardManualInput.visibility = View.GONE
                }
                R.id.rb_use_manual -> {
                    binding.cardTemplateSelection.visibility = View.GONE
                    binding.cardManualInput.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener { saveConfig() }
        binding.btnDelete.setOnClickListener {
            viewModel.removeScope(packageName)
            showToast(getString(R.string.scope_deleted))
            finish()
        }
    }

    private fun observeTemplates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.templates.collect { templates ->
                    availableTemplates = templates
                    setupTemplateDropdown(templates)
                }
            }
        }
    }

    private fun setupTemplateDropdown(templates: List<LocationTemplate>) {
        if (templates.isEmpty()) {
            binding.actTemplate.setText(getString(R.string.scope_no_template))
            binding.actTemplate.isEnabled = false
            return
        }

        val names = templates.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names)
        binding.actTemplate.setAdapter(adapter)
        binding.actTemplate.isEnabled = true

        // If config has a template, pre-select it
        currentConfig?.templateId?.let { tplId ->
            val template = templates.find { it.id == tplId }
            template?.let {
                binding.actTemplate.setText(it.name, false)
            }
        }
    }

    private fun loadConfig() {
        lifecycleScope.launch {
            currentConfig = viewModel.getScopeConfig(packageName)
            currentConfig?.let { cfg ->
                binding.switchDetailEnabled.isChecked = cfg.enabled

                if (cfg.overrideLat != null && cfg.overrideLng != null) {
                    binding.rbUseManual.isChecked = true
                    binding.cardManualInput.visibility = View.VISIBLE
                    binding.cardTemplateSelection.visibility = View.GONE
                    binding.etLat.setText(cfg.overrideLat.toString())
                    binding.etLng.setText(cfg.overrideLng.toString())
                    binding.etAccuracy.setText(cfg.accuracy.toString())
                } else if (cfg.templateId != null) {
                    binding.rbUseTemplate.isChecked = true
                    binding.cardTemplateSelection.visibility = View.VISIBLE
                    binding.cardManualInput.visibility = View.GONE
                } else {
                    // Default to template mode
                    binding.rbUseTemplate.isChecked = true
                    binding.cardTemplateSelection.visibility = View.VISIBLE
                    binding.cardManualInput.visibility = View.GONE
                }
            } ?: run {
                // New scope entry — default state
                binding.switchDetailEnabled.isChecked = true
                binding.rbUseTemplate.isChecked = true
                binding.cardTemplateSelection.visibility = View.VISIBLE
                binding.cardManualInput.visibility = View.GONE
            }
        }
    }

    private fun saveConfig() {
        val enabled = binding.switchDetailEnabled.isChecked

        val config = if (binding.rbUseManual.isChecked) {
            // Manual coordinates
            val lat = binding.etLat.text.toString().toDoubleOrNull()
            val lng = binding.etLng.text.toString().toDoubleOrNull()
            val acc = binding.etAccuracy.text.toString().toFloatOrNull() ?: 5f

            if (lat == null || lng == null) {
                showToast(getString(R.string.template_coords_required))
                return
            }

            AppSpoofConfig(
                enabled = enabled,
                templateId = null,
                overrideLat = lat,
                overrideLng = lng,
                accuracy = acc
            )
        } else {
            // Template reference
            val selectedTemplateName = binding.actTemplate.text.toString()
            val template = availableTemplates.find { it.name == selectedTemplateName }

            AppSpoofConfig(
                enabled = enabled,
                templateId = template?.id,
                overrideLat = null,
                overrideLng = null
            )
        }

        viewModel.updateScopeConfig(packageName, config)
        showToast(getString(R.string.scope_saved))
        finish()
    }
}
