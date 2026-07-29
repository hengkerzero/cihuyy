package io.github.jqssun.gpssetter.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jqssun.gpssetter.model.AppGroupTemplate
import io.github.jqssun.gpssetter.model.LocationTemplate
import io.github.jqssun.gpssetter.model.ScopeConfig
import io.github.jqssun.gpssetter.repository.ScopeConfigRepository
import io.github.jqssun.gpssetter.repository.TemplateRepository
import io.github.jqssun.gpssetter.utils.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

@HiltViewModel
class TemplateViewModel @Inject constructor(
    private val templateRepository: TemplateRepository,
    private val scopeConfigRepository: ScopeConfigRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _locationTemplates = MutableStateFlow<List<LocationTemplate>>(emptyList())
    val locationTemplates: StateFlow<List<LocationTemplate>> = _locationTemplates.asStateFlow()

    private val _groupTemplates = MutableStateFlow<List<AppGroupTemplate>>(emptyList())
    val groupTemplates: StateFlow<List<AppGroupTemplate>> = _groupTemplates.asStateFlow()

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch(Dispatchers.IO) {
            _locationTemplates.emit(templateRepository.loadLocationTemplates())
            _groupTemplates.emit(templateRepository.loadGroupTemplates())
        }
    }

    // ========== Location Templates ==========

    fun addLocationTemplate(template: LocationTemplate) {
        viewModelScope.launch(Dispatchers.IO) {
            templateRepository.addLocationTemplate(template)
            syncTemplatesToPrefs()
            _locationTemplates.emit(templateRepository.loadLocationTemplates())
        }
    }

    fun updateLocationTemplate(template: LocationTemplate) {
        viewModelScope.launch(Dispatchers.IO) {
            templateRepository.updateLocationTemplate(template)
            syncTemplatesToPrefs()
            _locationTemplates.emit(templateRepository.loadLocationTemplates())
        }
    }

    fun deleteLocationTemplate(templateId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            templateRepository.deleteLocationTemplate(templateId)
            syncTemplatesToPrefs()
            _locationTemplates.emit(templateRepository.loadLocationTemplates())
        }
    }

    // ========== Group Templates ==========

    fun addGroupTemplate(template: AppGroupTemplate) {
        viewModelScope.launch(Dispatchers.IO) {
            templateRepository.addGroupTemplate(template)
            _groupTemplates.emit(templateRepository.loadGroupTemplates())
        }
    }

    fun updateGroupTemplate(template: AppGroupTemplate) {
        viewModelScope.launch(Dispatchers.IO) {
            templateRepository.updateGroupTemplate(template)
            _groupTemplates.emit(templateRepository.loadGroupTemplates())
        }
    }

    fun deleteGroupTemplate(templateId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            templateRepository.deleteGroupTemplate(templateId)
            _groupTemplates.emit(templateRepository.loadGroupTemplates())
        }
    }

    // ========== Apply Template to Group ==========

    fun applyTemplateToGroup(templateId: String, groupId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val groups = templateRepository.loadGroupTemplates()
            val group = groups.find { it.id == groupId } ?: return@launch
            val config = scopeConfigRepository.load()
            templateRepository.applyTemplateToGroup(templateId, group, config)
            scopeConfigRepository.save(config)
        }
    }

    // ========== Export / Import ==========

    fun exportTemplates(outputStream: OutputStream, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                templateRepository.exportTemplates(outputStream)
                outputStream.close()
                launch(Dispatchers.Main) { onResult(true) }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    fun importTemplates(inputStream: InputStream, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = templateRepository.importTemplates(inputStream)
            inputStream.close()

            result.fold(
                onSuccess = { bundle ->
                    templateRepository.mergeImportedTemplates(bundle)
                    syncTemplatesToPrefs()
                    loadAll()
                    val count = bundle.locationTemplates.size + bundle.groupTemplates.size
                    launch(Dispatchers.Main) { onResult(Result.success(count)) }
                },
                onFailure = { e ->
                    launch(Dispatchers.Main) { onResult(Result.failure(e)) }
                }
            )
        }
    }

    /**
     * Sync templates to SharedPreferences for Xposed hook access.
     */
    private fun syncTemplatesToPrefs() {
        val templates = templateRepository.loadLocationTemplates()
        PrefManager.templatesJson = com.google.gson.Gson().toJson(templates)
    }
}
