package io.github.jqssun.gpssetter.ui.viewmodel

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import io.github.jqssun.gpssetter.adapter.AppPickerItem
import io.github.jqssun.gpssetter.adapter.ScopeAppItem
import io.github.jqssun.gpssetter.model.AppSpoofConfig
import io.github.jqssun.gpssetter.model.LocationTemplate
import io.github.jqssun.gpssetter.model.ScopeConfig
import io.github.jqssun.gpssetter.repository.ScopeConfigRepository
import io.github.jqssun.gpssetter.repository.TemplateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ScopeViewModel @Inject constructor(
    private val scopeConfigRepository: ScopeConfigRepository,
    private val templateRepository: TemplateRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _scopeApps = MutableStateFlow<List<ScopeAppItem>>(emptyList())
    val scopeApps: StateFlow<List<ScopeAppItem>> = _scopeApps.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppPickerItem>>(emptyList())
    val installedApps: StateFlow<List<AppPickerItem>> = _installedApps.asStateFlow()

    private val _templates = MutableStateFlow<List<LocationTemplate>>(emptyList())
    val templates: StateFlow<List<LocationTemplate>> = _templates.asStateFlow()

    init {
        loadScopeApps()
        loadTemplates()
    }

    fun loadScopeApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val config = scopeConfigRepository.load()
            val pm = context.packageManager
            val items = config.scope.map { (pkg, cfg) ->
                val appName = try {
                    pm.getApplicationInfo(pkg, 0).let {
                        pm.getApplicationLabel(it).toString()
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    pkg
                }
                ScopeAppItem(packageName = pkg, appName = appName, config = cfg)
            }.sortedBy { it.appName.lowercase() }
            _scopeApps.emit(items)
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val config = scopeConfigRepository.load()
            val existingPackages = config.scope.keys

            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    // Only show launchable apps, exclude ourselves and already-scoped apps
                    pm.getLaunchIntentForPackage(app.packageName) != null &&
                        app.packageName !in existingPackages &&
                        app.packageName != context.packageName
                }
                .map { app ->
                    AppPickerItem(
                        packageName = app.packageName,
                        appName = pm.getApplicationLabel(app).toString()
                    )
                }
                .sortedBy { it.appName.lowercase() }

            _installedApps.emit(apps)
        }
    }

    fun loadTemplates() {
        viewModelScope.launch(Dispatchers.IO) {
            _templates.emit(templateRepository.loadLocationTemplates())
        }
    }

    fun addAppsToScope(packageNames: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            packageNames.forEach { pkg ->
                scopeConfigRepository.putScope(pkg, AppSpoofConfig(enabled = true))
            }
            loadScopeApps()
        }
    }

    fun toggleScope(packageName: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            scopeConfigRepository.toggleScope(packageName, enabled)
            loadScopeApps()
        }
    }

    fun updateScopeConfig(packageName: String, config: AppSpoofConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            scopeConfigRepository.putScope(packageName, config)
            loadScopeApps()
        }
    }

    fun removeScope(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            scopeConfigRepository.removeScope(packageName)
            loadScopeApps()
        }
    }

    fun getScopeConfig(packageName: String): AppSpoofConfig? {
        return scopeConfigRepository.load().scope[packageName]
    }
}
