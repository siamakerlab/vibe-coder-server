package com.siamakerlab.vibecoder.console.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("vibe_coder_prefs")

@Singleton
class AppPreferences @Inject constructor(private val context: Context) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val TOKEN = stringPreferencesKey("token")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    data class Session(val serverUrl: String?, val token: String?, val deviceName: String?, val deviceId: String?)

    val session: Flow<Session> = context.dataStore.data.map {
        Session(
            serverUrl = it[Keys.SERVER_URL],
            token = it[Keys.TOKEN],
            deviceName = it[Keys.DEVICE_NAME],
            deviceId = it[Keys.DEVICE_ID],
        )
    }

    suspend fun current(): Session = session.first()

    suspend fun saveSession(serverUrl: String, token: String, deviceName: String, deviceId: String) {
        context.dataStore.edit {
            it[Keys.SERVER_URL] = serverUrl
            it[Keys.TOKEN] = token
            it[Keys.DEVICE_NAME] = deviceName
            it[Keys.DEVICE_ID] = deviceId
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
