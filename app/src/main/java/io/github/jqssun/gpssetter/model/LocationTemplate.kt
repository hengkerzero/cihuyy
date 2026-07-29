package io.github.jqssun.gpssetter.model

import java.util.UUID

/**
 * A named location template that can be shared across multiple app scopes.
 * When a template is updated, all apps referencing it via templateId
 * automatically receive the new location.
 */
data class LocationTemplate(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var lat: Double,
    var lng: Double,
    var accuracy: Float = 5f,
    var altitude: Double = 0.0
)

/**
 * A named group of application package names.
 * Used to quickly apply a template to multiple apps at once.
 */
data class AppGroupTemplate(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val packages: MutableSet<String> = mutableSetOf()
)

/**
 * Bundle used for template export/import via JSON file.
 */
data class TemplateExportBundle(
    val version: Int = 1,
    val locationTemplates: List<LocationTemplate>,
    val groupTemplates: List<AppGroupTemplate>
)
