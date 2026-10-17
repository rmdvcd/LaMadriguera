package com.lamadriguera.app.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lamadriguera.app.domain.model.AccountKind
import com.lamadriguera.app.domain.repository.DeviceAccountRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Regla "1 teléfono = 1 cuenta principal" a nivel de dispositivo:
 * la primera cuenta que entra queda registrada como principal; cualquier otra
 * cuenta en el mismo dispositivo es adicional (requiere identidad para
 * subastas y apuestas). Persistido en DataStore.
 */
@Singleton
class DataStoreDeviceAccountRegistry @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : DeviceAccountRegistry {

    override suspend fun resolveAccountKind(accountId: String): AccountKind {
        require(accountId.isNotBlank()) { "Empty account id" }
        val primary = primaryAccountId()
        return when {
            primary == null -> {
                dataStore.edit { it[KEY_PRIMARY_ACCOUNT] = accountId }
                AccountKind.PRIMARY
            }
            primary == accountId -> AccountKind.PRIMARY
            else -> AccountKind.SECONDARY
        }
    }

    override suspend fun primaryAccountId(): String? =
        dataStore.data.first()[KEY_PRIMARY_ACCOUNT]

    private companion object {
        val KEY_PRIMARY_ACCOUNT = stringPreferencesKey("primary_account_id")
    }
}
