package jp.mushamusha.autotrade.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class AppPreferences(private val context: Context) {
    companion object {
        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val SERVER_URL = stringPreferencesKey("server_url")
    }

    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[ONBOARDING_DONE] ?: false }
    val serverUrl: Flow<String> = context.dataStore.data.map { it[SERVER_URL] ?: "" }

    suspend fun setOnboardingDone(value: Boolean) = context.dataStore.edit { it[ONBOARDING_DONE] = value }
    suspend fun setServerUrl(value: String) = context.dataStore.edit { it[SERVER_URL] = value.trim().removeSuffix("/") }
}
