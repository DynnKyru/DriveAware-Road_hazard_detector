package com.DynnKyru.driveAware.ui

import android.content.Context
import androidx.preference.PreferenceManager

object SoundSettingsManager {
    private const val SOUND_MODE_KEY = "sound_mode"
    enum class SoundMode {
        SILENT, BEEP, VOICE
    }
    // Save the selected sound mode
    fun saveSoundSetting(context: Context, mode: SoundMode) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString(SOUND_MODE_KEY, mode.name).apply()
    }
    // Get the currently saved sound mode
    fun getCurrentSoundSetting(context: Context): SoundMode {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val modeName = prefs.getString(SOUND_MODE_KEY, null)
        return if (modeName == null) {
            saveSoundSetting(context, SoundMode.BEEP) // Set default to BEEP
            SoundMode.BEEP
        } else {
            SoundMode.valueOf(modeName)
        }
    }
}