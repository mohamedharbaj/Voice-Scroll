package com.voicescroll.app.data

import android.content.Context
import android.content.SharedPreferences
import com.voicescroll.app.model.CommandType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages local app settings and user custom aliases via Android SharedPreferences.
 * Fully offline, no remote DB or analytics.
 */
class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "voicescroll_local_prefs"

        private const val KEY_VOICE_ACTIVE = "key_voice_active"
        // v2: old installs saved a single language (ar-MA) which made only one language work. New default = all languages.
        private const val KEY_COMMAND_LANGUAGE = "key_command_language_v2"
        private const val KEY_SWIPE_DURATION_MS = "key_swipe_duration_ms"
        private const val KEY_COOLDOWN_MS = "key_cooldown_ms"
        private const val KEY_CUSTOM_ALIASES_JSON = "key_custom_aliases_json"

        const val LANG_AR_STANDARD = "ar-SA"
        const val LANG_AR_MOROCCO = "ar-MA"
        const val LANG_FRENCH = "fr-FR"
        const val LANG_ENGLISH = "en-US"
        /** All languages: the recognizer rotates between Darija/Arabic, English and French. */
        const val LANG_AUTO = "auto"

        const val DEFAULT_SWIPE_DURATION_MS = 280L
        const val DEFAULT_COOLDOWN_MS = 900L
    }

    var isVoiceControlActive: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_ACTIVE, value).apply()

    var commandLanguage: String
        get() = prefs.getString(KEY_COMMAND_LANGUAGE, LANG_AUTO) ?: LANG_AUTO
        set(value) = prefs.edit().putString(KEY_COMMAND_LANGUAGE, value).apply()

    var swipeDurationMs: Long
        get() = prefs.getLong(KEY_SWIPE_DURATION_MS, DEFAULT_SWIPE_DURATION_MS)
        set(value) = prefs.edit().putLong(KEY_SWIPE_DURATION_MS, value).apply()

    var cooldownMs: Long
        get() = prefs.getLong(KEY_COOLDOWN_MS, DEFAULT_COOLDOWN_MS)
        set(value) = prefs.edit().putLong(KEY_COOLDOWN_MS, value).apply()

    /**
     * Retrieves all custom aliases defined by the user.
     */
    fun getCustomAliases(): List<CustomAlias> {
        val rawJson = prefs.getString(KEY_CUSTOM_ALIASES_JSON, null) ?: return emptyList()
        val list = mutableListOf<CustomAlias>()
        try {
            val jsonArray = JSONArray(rawJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", "")
                val phrase = obj.optString("phrase", "")
                val cmdString = obj.optString("command", CommandType.NEXT.name)
                val cmd = CommandType.fromString(cmdString)
                if (phrase.isNotBlank() && cmd != CommandType.NONE) {
                    list.add(CustomAlias(id, phrase, cmd))
                }
            }
        } catch (_: Exception) {
            // Safe fallback on parse error
        }
        return list
    }

    /**
     * Saves a new custom alias.
     */
    fun addCustomAlias(phrase: String, command: CommandType) {
        val current = getCustomAliases().toMutableList()
        // Replace existing phrase if exists or add new
        current.removeAll { it.phrase.equals(phrase.trim(), ignoreCase = true) }
        current.add(CustomAlias(phrase = phrase.trim(), command = command))
        saveCustomAliases(current)
    }

    /**
     * Removes an alias by ID.
     */
    fun removeCustomAlias(id: String) {
        val current = getCustomAliases().filter { it.id != id }
        saveCustomAliases(current)
    }

    /**
     * Returns custom aliases as a fast lookup map of phrase to CommandType.
     */
    fun getCustomAliasMap(): Map<String, CommandType> {
        return getCustomAliases().associate { it.phrase to it.command }
    }

    private fun saveCustomAliases(list: List<CustomAlias>) {
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("phrase", item.phrase)
            obj.put("command", item.command.name)
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_CUSTOM_ALIASES_JSON, jsonArray.toString()).apply()
    }
}
