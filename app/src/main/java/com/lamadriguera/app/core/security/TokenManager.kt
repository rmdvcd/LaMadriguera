package com.lamadriguera.app.core.security

import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gestión de tokens Supabase (JWT) en EncryptedSharedPreferences.
 * La UI nunca toca tokens; solo via AuthRepository.
 */
@Singleton
class TokenManager @Inject constructor(
    private val securePrefs: SharedPreferences
) {
    private val _tokenFlow = MutableStateFlow(getToken())

    val tokenFlow: Flow<String?> = _tokenFlow

    fun getToken(): String? = securePrefs.getString(KEY_TOKEN, null)

    fun saveToken(token: String) {
        securePrefs.edit().putString(KEY_TOKEN, token).apply()
        _tokenFlow.value = token
    }

    fun clearToken() {
        securePrefs.edit().remove(KEY_TOKEN).apply()
        _tokenFlow.value = null
    }

    companion object {
        private const val KEY_TOKEN = "supabase_access_token"
    }
}
