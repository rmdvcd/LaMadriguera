package com.lamadriguera.app.core.di

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.lamadriguera.app.BuildConfig
import com.lamadriguera.app.core.security.chat.DisputeKeyConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.SecureRandom
import javax.inject.Singleton

/**
 * Provee almacenamiento seguro para tokens (Supabase JWT) y secretos del chat
 * (claves de sesión por conversación, identidad X25519 del dispositivo),
 * además de los primitivos criptográficos compartidos.
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideMasterKey(@ApplicationContext context: Context): MasterKey =
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    @Provides
    @Singleton
    fun provideEncryptedPrefs(
        @ApplicationContext context: Context,
        masterKey: MasterKey
    ) = EncryptedSharedPreferences.create(
        context,
        "lamadriguera_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Fuente de aleatoriedad criptográfica para claves e IV del chat. */
    @Provides
    @Singleton
    fun provideSecureRandom(): SecureRandom = SecureRandom()

    /**
     * Claves públicas de custodia del diseño C (admin general + moderador
     * senior). Vienen de local.properties/BuildConfig; si faltan, se usa el
     * par de DESARROLLO embebido (las privadas están publicadas a propósito
     * para entornos locales; ver DisputeKeyConfig).
     */
    @Provides
    @Singleton
    fun provideDisputeKeyConfig(): DisputeKeyConfig = DisputeKeyConfig.fromBase64(
        adminPubB64 = BuildConfig.DISPUTE_ADMIN_PUB.takeUnless { it.contains("placeholder") },
        moderatorPubB64 = BuildConfig.DISPUTE_MODERATOR_PUB.takeUnless { it.contains("placeholder") }
    )
}
