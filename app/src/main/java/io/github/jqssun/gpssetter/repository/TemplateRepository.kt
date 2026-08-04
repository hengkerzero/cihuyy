package io.github.jqssun.gpssetter.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.jqssun.gpssetter.model.AppGroupTemplate
import io.github.jqssun.gpssetter.model.AppSpoofConfig
import io.github.jqssun.gpssetter.model.LocationTemplate
import io.github.jqssun.gpssetter.model.ScopeConfig
import io.github.jqssun.gpssetter.model.TemplateExportBundle
import io.github.jqssun.gpssetter.utils.PrefManager
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for location templates and app group templates.
 *
 * Templates are stored in a separate JSON file from scope config.
 * This separation allows independent CRUD and export/import.
 */
@Singleton
class TemplateRepository @Inject constructor(
    private val context: Context,
    private val gson: Gson
) {
    private val locationFile = File(context.filesDir, "cihuyy_location_templates.json")
    private val groupFile = File(context.filesDir, "cihuyy_group_templates.json")

    // ========== Location Templates ==========

    fun loadLocationTemplates(): List<LocationTemplate> {
        if (!locationFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<LocationTemplate>>() {}.type
            gson.fromJson<List<LocationTemplate>>(locationFile.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Failed to load location templates")
            emptyList()
        }
    }

    fun saveLocationTemplates(templates: List<LocationTemplate>) {
        try {
            val tmp = File(context.filesDir, "cihuyy_location_templates.tmp")
            val json = gson.toJson(templates)
            tmp.writeText(json)
            locationFile.delete()
            tmp.renameTo(locationFile)
            // Sync to SharedPreferences for Xposed hook access
            PrefManager.templatesJson = json
        } catch (e: Exception) {
            Timber.e(e, "Failed to save location templates")
        }
    }

    fun addLocationTemplate(template: LocationTemplate) {
        val list = loadLocationTemplates().toMutableList()
        list.add(template)
        saveLocationTemplates(list)
    }

    fun updateLocationTemplate(template: LocationTemplate) {
        val list = loadLocationTemplates().toMutableList()
        val idx = list.indexOfFirst { it.id == template.id }
        if (idx >= 0) {
            list[idx] = template
            saveLocationTemplates(list)
        }
    }

    fun deleteLocationTemplate(templateId: String) {
        val list = loadLocationTemplates().toMutableList()
        list.removeAll { it.id == templateId }
        saveLocationTemplates(list)
    }

    fun getLocationTemplate(templateId: String): LocationTemplate? {
        return loadLocationTemplates().find { it.id == templateId }
    }

    // ========== App Group Templates ==========

    fun loadGroupTemplates(): List<AppGroupTemplate> {
        if (!groupFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<AppGroupTemplate>>() {}.type
            gson.fromJson<List<AppGroupTemplate>>(groupFile.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Failed to load group templates")
            emptyList()
        }
    }

    fun saveGroupTemplates(templates: List<AppGroupTemplate>) {
        try {
            val tmp = File(context.filesDir, "cihuyy_group_templates.tmp")
            tmp.writeText(gson.toJson(templates))
            groupFile.delete()
            tmp.renameTo(groupFile)
        } catch (e: Exception) {
            Timber.e(e, "Failed to save group templates")
        }
    }

    fun addGroupTemplate(template: AppGroupTemplate) {
        val list = loadGroupTemplates().toMutableList()
        list.add(template)
        saveGroupTemplates(list)
    }

    fun updateGroupTemplate(template: AppGroupTemplate) {
        val list = loadGroupTemplates().toMutableList()
        val idx = list.indexOfFirst { it.id == template.id }
        if (idx >= 0) {
            list[idx] = template
            saveGroupTemplates(list)
        }
    }

    fun deleteGroupTemplate(templateId: String) {
        val list = loadGroupTemplates().toMutableList()
        list.removeAll { it.id == templateId }
        saveGroupTemplates(list)
    }

    // ========== Apply Template to Group ==========

    /**
     * Apply a location template to all apps in a group,
     * creating scope entries as needed.
     */
    fun applyTemplateToGroup(
        templateId: String,
        group: AppGroupTemplate,
        scopeConfig: ScopeConfig
    ) {
        group.packages.forEach { pkg ->
            val existing = scopeConfig.scope[pkg]
            if (existing != null) {
                existing.templateId = templateId
                existing.enabled = true
                // Clear manual override when applying template
                existing.overrideLat = null
                existing.overrideLng = null
            } else {
                scopeConfig.scope[pkg] = AppSpoofConfig(
                    enabled = true,
                    templateId = templateId
                )
            }
        }
    }

    // ========== Export / Import ==========

    /**
     * Export all templates to an OutputStream as JSON.
     */
    fun exportTemplates(outputStream: OutputStream) {
        val bundle = TemplateExportBundle(
            version = 1,
            locationTemplates = loadLocationTemplates(),
            groupTemplates = loadGroupTemplates()
        )
        outputStream.write(gson.toJson(bundle).toByteArray())
        outputStream.flush()
    }

    /**
     * Import templates from an InputStream.
     * Returns a Result with the imported bundle or an error.
     */
    fun importTemplates(inputStream: InputStream): Result<TemplateExportBundle> = runCatching {
        val json = inputStream.bufferedReader().readText()
        val bundle = gson.fromJson(json, TemplateExportBundle::class.java)
            ?: throw IllegalArgumentException("File JSON tidak valid")
        require(bundle.version <= 1) { "Versi template tidak didukung (v${bundle.version})" }
        bundle
    }

    /**
     * Merge imported templates into the existing collection.
     * Replaces templates with matching IDs, adds new ones.
     */
    fun mergeImportedTemplates(bundle: TemplateExportBundle) {
        // Merge location templates
        val existingLocations = loadLocationTemplates().toMutableList()
        bundle.locationTemplates.forEach { imported ->
            val idx = existingLocations.indexOfFirst { it.id == imported.id }
            if (idx >= 0) {
                existingLocations[idx] = imported
            } else {
                existingLocations.add(imported)
            }
        }
        saveLocationTemplates(existingLocations)

        // Merge group templates
        val existingGroups = loadGroupTemplates().toMutableList()
        bundle.groupTemplates.forEach { imported ->
            val idx = existingGroups.indexOfFirst { it.id == imported.id }
            if (idx >= 0) {
                existingGroups[idx] = imported
            } else {
                existingGroups.add(imported)
            }
        }
        saveGroupTemplates(existingGroups)
    }
}
