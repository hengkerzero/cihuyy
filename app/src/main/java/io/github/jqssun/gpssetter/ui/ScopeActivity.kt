package io.github.jqssun.gpssetter.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.adapter.ScopeAppAdapter
import io.github.jqssun.gpssetter.databinding.ActivityScopeBinding
import io.github.jqssun.gpssetter.ui.viewmodel.ScopeViewModel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ScopeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScopeBinding
    private val viewModel: ScopeViewModel by viewModels()
    private lateinit var adapter: ScopeAppAdapter

    companion object {
        const val REQUEST_DETAIL = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScopeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupFab()
        observeData()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadScopeApps()
    }

    private fun setupToolbar() {
        binding.toolbarScope.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = ScopeAppAdapter(
            onToggle = { pkg, enabled ->
                viewModel.toggleScope(pkg, enabled)
            },
            onClick = { item ->
                val intent = Intent(this, ScopeDetailActivity::class.java).apply {
                    putExtra(ScopeDetailActivity.EXTRA_PACKAGE_NAME, item.packageName)
                }
                startActivity(intent)
            }
        )
        binding.rvScopeApps.layoutManager = LinearLayoutManager(this)
        binding.rvScopeApps.adapter = adapter
    }

    private fun setupFab() {
        binding.fabAddApp.setOnClickListener {
            val dialog = AppPickerDialog.newInstance()
            dialog.setOnAppsSelectedListener { selectedPackages ->
                viewModel.addAppsToScope(selectedPackages)
            }
            dialog.show(supportFragmentManager, "app_picker")
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.scopeApps.collect { apps ->
                    adapter.submitList(apps)
                    binding.emptyState.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvScopeApps.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }
}
