package io.github.sanitised.st

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateOf

internal class BatteryPromptManager(
    private val application: Application,
    private val postUserMessage: (String) -> Unit
) {
    companion object {
        private const val PREFS_NAME = "battery_prompt"
        private const val PREF_PROMPT_HANDLED = "prompt_handled"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val promptHandled = mutableStateOf(
        prefs.getBoolean(PREF_PROMPT_HANDLED, false)
    )

    fun shouldShowPrompt(isBatteryUnrestricted: Boolean): Boolean {
        if (isBatteryUnrestricted) {
            acknowledgePrompt()
            return false
        }
        return !promptHandled.value
    }

    fun acknowledgePrompt() {
        if (promptHandled.value) return
        promptHandled.value = true
        prefs.edit().putBoolean(PREF_PROMPT_HANDLED, true).apply()
    }

    fun dismissPrompt() {
        acknowledgePrompt()
        postUserMessage(application.getString(R.string.battery_prompt_dismissed))
    }
}
