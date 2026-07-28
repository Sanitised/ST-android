package io.github.sanitised.st

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.DocumentsContract

internal object ExternalFileAccess {
    private const val PREFS_NAME = "external_file_access"
    private const val PREF_ENABLED = "enabled"
    private const val AUTHORITY_SUFFIX = ".documents"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_ENABLED, enabled)
            .apply()
        updateProviderComponent(context, enabled)
    }

    fun syncProviderComponent(context: Context) {
        updateProviderComponent(context, isEnabled(context))
    }

    fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

    private fun updateProviderComponent(context: Context, enabled: Boolean) {
        val component = ComponentName(context, SillyTavernDocumentsProvider::class.java)
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(
            component,
            state,
            PackageManager.DONT_KILL_APP
        )
        context.contentResolver.notifyChange(
            DocumentsContract.buildRootsUri(authority(context)),
            null
        )
    }
}
