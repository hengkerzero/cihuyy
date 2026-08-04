package io.github.jqssun.gpssetter.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.jqssun.gpssetter.model.AppSpoofConfig
import io.github.jqssun.gpssetter.model.LocationTemplate
import io.github.jqssun.gpssetter.model.ScopeConfig
import io.github.jqssun.gpssetter.utils.PrefManager
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for per-app scope configuration.
 *
 * - Persists to JSON file with atomic write (tmp + rename).
 * - Syncs a copy to SharedPreferences so Xposed hooks can read it
 *   via XSharedPreferences without direct file access.
 */
@Singleton
class ScopeConfigRepository @Inject constructor(
    private val context: Context,
    private val templateRepository: TemplateRepository,
    private val gson: Gson
) {
    private val file = File(context.filesDir, "cihuyy_scope_config.json")

    /**
     * Load the current scope configuration from disk.
     * Returns a default empty config if file doesn't exist or is corrupt.
     */
    fun load(): ScopeConfig {
        if (!file.exists()) return ScopeConfig()
        return try {
            gson.fromJson(file.readText(), ScopeConfig::class.java) ?: ScopeConfig()
        } catch (e: Exception) {
            Timber.e(e, "Failed to load scope config, returning default")
            ScopeConfig()
        }
    }

    /**
     * Save scope configuration to disk atomically, then sync to SharedPreferences.
     */
    fun save(config: ScopeConfig) {
        try {
            val tmp = File(context.filesDir, "cihuyy_scope_config.tmp")
            tmp.writeText(gson.toJson(config))
            file.delete()
            tmp.renameTo(file)
            // Sync to SharedPreferences for Xposed hook access
            syncToPrefs(config)
        } catch (e: Exception) {
            Timber.e(e, "Failed to save scope config")
        }
    }

    /**
     * Add or update a single app scope entry.
     */
    fun putScope(packageName: String, appConfig: AppSpoofConfig) {
        val config = load()
        config.scope[packageName] = appConfig
        save(config)
    }

    /**
     * Remove an app from the scope.
     */
    fun removeScope(packageName: String) {
        val config = load()
        config.scope.remove(packageName)
        save(config)
    }

    /**
     * Toggle the enabled state of a scope entry without losing its config.
     */
    fun toggleScope(packageName: String, enabled: Boolean) {
        val config = load()
        config.scope[packageName]?.let {
            it.enabled = enabled
            save(config)
        }
    }

    /**
     * Resolve the final fake location for a given package.
     * Priority: overrideLat/Lng > templateId > null (passthrough to real GPS).
     *
     * @return Pair(lat, lng) or null if no scope or scope disabled.
     */
    fun resolveLocation(packageName: String): ResolvedLocation? {
        val config = load()
        val appConfig = config.scope[packageName] ?: return null
        if (!appConfig.enabled) return null

        // Priority 1: manual override
        val overrideLat = appConfig.overrideLat
        val overrideLng = appConfig.overrideLng
        if (overrideLat != null && overrideLng != null) {
            return ResolvedLocation(
                lat = overrideLat,
                lng = overrideLng,
                accuracy = appConfig.accuracy,
                altitude = appConfig.altitude,
                spoofAltitude = appConfig.spoofAltitude,
                updateIntervalMs = appConfig.updateIntervalMs
            )
        }

        // Priority 2: resolve from template
        val templateId = appConfig.templateId
        if (templateId != null) {
            val templates = templateRepository.loadLocationTemplates()
            val template = templates.find { it.id == templateId }
            if (template != null) {
                return ResolvedLocation(
                    lat = template.lat,
                    lng = template.lng,
                    accuracy = template.accuracy,
                    altitude = template.altitude,
                    spoofAltitude = appConfig.spoofAltitude,
                    updateIntervalMs = appConfig.updateIntervalMs
                )
            }
        }

        // No location configured → passthrough
        return null
    }

    /**
     * Sync scope config JSON to SharedPreferences for Xposed hook access.
     */
    private fun syncToPrefs(config: ScopeConfig) {
        PrefManager.scopeConfigJson = gson.toJson(config)
    }

    /**
     * Clean up stale scope entries for uninstalled apps.
     */
    fun cleanStaleScopeEntries() {
        val config = load()
        val pm = context.packageManager
        val installedPackages = pm.getInstalledPackages(0).map { it.packageName }.toSet()
        val staleKeys = config.scope.keys.filter { it !in installedPackages }
        if (staleKeys.isNotEmpty()) {
            staleKeys.forEach { config.scope.remove(it) }
            save(config)
            Timber.d("Cleaned ${staleKeys.size} stale scope entries")
        }
    }

    data class ResolvedLocation(
        val lat: Double,
        val lng: Double,
        val accuracy: Float = 5f,
        val altitude: Double = 0.0,
        val spoofAltitude: Boolean = false,
        val updateIntervalMs: Long = 1000L
    )
}
