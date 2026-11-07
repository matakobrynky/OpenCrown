package io.github.matakobrynky.opencrown.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.serialization.json.Json
import java.io.File

/** In-memory app state backed by a JSON file. */
object Repo {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private lateinit var file: File

    val modules = mutableStateListOf<HubModule>()
    val profiles = mutableStateListOf<Profile>()

    fun load(ctx: Context) {
        file = File(ctx.filesDir, "opencrown.json")
        val d = try { json.decodeFromString(AppData.serializer(), file.readText()) } catch (_: Exception) { null }
        modules.clear(); profiles.clear()
        if (d != null) {
            // drop the placeholder classic module created by version 1.0
            modules.addAll(d.modules.filterNot { it.name == "My Module" && it.type == HubType.MK6 }); profiles.addAll(d.profiles)
        } else {
            profiles.add(defaultProfile("Off-Roader", null, 0))
            save()
        }
    }

    fun save() {
        try {
            file.writeText(json.encodeToString(AppData.serializer(), AppData(modules.toList(), profiles.toList())))
        } catch (_: Exception) {}
    }

    fun module(id: String?) = modules.firstOrNull { it.id == id }

    fun upsertModule(m: HubModule) {
        val i = modules.indexOfFirst { it.id == m.id }
        if (i >= 0) modules[i] = m else {
            modules.add(m)
            // first hub: hook up every control that isn't bound to anything yet
            if (modules.size == 1) {
                fun Binding.fill() = if (moduleId == null || module(moduleId) == null) copy(moduleId = m.id) else this
                for (pi in profiles.indices) profiles[pi] = profiles[pi].copy(widgets = profiles[pi].widgets.map {
                    it.copy(primary = it.primary.fill(), secondary = it.secondary.fill())
                })
            }
        }
        save()
    }

    fun deleteModule(id: String) { modules.removeAll { it.id == id }; save() }

    fun upsertProfile(p: Profile) {
        val i = profiles.indexOfFirst { it.id == p.id }
        if (i >= 0) profiles[i] = p else profiles.add(p)
        save()
    }

    fun deleteProfile(id: String) { profiles.removeAll { it.id == id }; save() }

    fun duplicate(p: Profile) = upsertProfile(
        p.copy(id = newId(), name = p.name + " copy", widgets = p.widgets.map { it.copy(id = newId()) })
    )

    fun defaultProfile(name: String, moduleId: String?, color: Int) = Profile(
        name = name,
        color = color,
        widgets = listOf(
            Widget(type = WidgetType.SLIDER_V, x = 0.05f, y = 0.22f, w = 0.13f, h = 0.7f, label = "Drive", color = 0,
                primary = Binding(moduleId, 0)),
            Widget(type = WidgetType.SLIDER_H, x = 0.58f, y = 0.66f, w = 0.37f, h = 0.24f, label = "Steer", color = 1,
                primary = Binding(moduleId, 1)),
            Widget(type = WidgetType.TOGGLE, x = 0.27f, y = 0.25f, w = 0.14f, h = 0.22f, label = "Lights", color = 3,
                primary = Binding(moduleId, 2)),
            Widget(type = WidgetType.BUTTON, x = 0.27f, y = 0.6f, w = 0.14f, h = 0.22f, label = "Winch", color = 2,
                primary = Binding(moduleId, 3)),
            Widget(type = WidgetType.JOYSTICK, x = 0.64f, y = 0.04f, w = 0.17f, h = 0.44f, label = "Crane", color = 4,
                primary = Binding(moduleId, 4), secondary = Binding(moduleId, 5)),
        )
    )
}
