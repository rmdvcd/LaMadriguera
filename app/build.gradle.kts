import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Credenciales de Supabase: local.properties (no se commitea) o -PSUPABASE_URL=...
// Nunca hardcodear secretos en el repositorio.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secretOrPlaceholder(key: String, placeholder: String): String =
    localProperties.getProperty(key)
        ?: providers.gradleProperty(key).orNull
        ?: placeholder

// --- Firma de release -------------------------------------------------------
// Se busca `keystore.properties` en la raíz del repo (ver keystore.properties.example)
// o las variables de entorno KEYSTORE_PATH / KEYSTORE_PASSWORD / KEYSTORE_ALIAS /
// KEYSTORE_KEY_PASSWORD (CI). Si no hay nada, release se compila SIN firmar y
// scripts/build-apk.sh lo indica; nunca se commitean claves.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

fun keystoreValue(key: String, envName: String): String? =
    keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(envName).orNull?.takeIf { it.isNotBlank() }

val releaseKeystorePath = keystoreValue("storeFile", "KEYSTORE_PATH")
val releaseKeystorePassword = keystoreValue("storePassword", "KEYSTORE_PASSWORD")
val releaseKeystoreAlias = keystoreValue("keyAlias", "KEYSTORE_ALIAS")
val releaseKeystoreKeyPassword = keystoreValue("keyPassword", "KEYSTORE_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseKeystorePath, releaseKeystorePassword, releaseKeystoreAlias, releaseKeystoreKeyPassword
).all { it != null } && releaseKeystorePath?.let { rootProject.file(it).exists() } == true

// --- Versión ----------------------------------------------------------------
// Fuente de verdad: gradle.properties (VERSION_CODE / VERSION_NAME),
// sobrescribible con -PVERSION_CODE=... -PVERSION_NAME=...
val appVersionCode = (
    providers.gradleProperty("VERSION_CODE").orNull
        ?: localProperties.getProperty("VERSION_CODE")
        ?: "1"
    ).toIntOrNull() ?: 1
val appVersionName = providers.gradleProperty("VERSION_NAME").orNull
    ?: localProperties.getProperty("VERSION_NAME")
    ?: "1.0.0-dev"

android {
    namespace = "com.lamadriguera.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lamadriguera.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"${secretOrPlaceholder("SUPABASE_URL", "https://placeholder.supabase.co")}\""
        )
        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            "\"${secretOrPlaceholder("SUPABASE_ANON_KEY", "placeholder-anon-key")}\""
        )

        // Claves públicas X25519 de custodia (base64) para el chat cifrado.
        // En local se usa el par de desarrollo embebido en DisputeKeyConfig.
        buildConfigField(
            "String",
            "DISPUTE_ADMIN_PUB",
            "\"${secretOrPlaceholder("DISPUTE_ADMIN_PUB", "placeholder-dev-key")"}\""
        )
        buildConfigField(
            "String",
            "DISPUTE_MODERATOR_PUB",
            "\"${secretOrPlaceholder("DISPUTE_MODERATOR_PUB", "placeholder-dev-key")"}\""
        )

        // Moderación de texto: endpoint de la API configurable por variable de
        // entorno. Vacío ⇒ Edge Function Supabase `moderate-text` (que a su vez
        // lee MODERATION_PROVIDER / MODERATION_API_URL / MODERATION_API_KEY).
        buildConfigField(
            "String",
            "MODERATION_API_URL",
            "\"${secretOrPlaceholder("MODERATION_API_URL", "")}\""
        )

        // NSFW.js en cliente: URL del bundle/modelo para el WebView. Vacío ⇒
        // solo la heurística offline de respaldo.
        buildConfigField(
            "String",
            "NSFWJS_MODEL_URL",
            "\"${secretOrPlaceholder("NSFWJS_MODEL_URL", "")}\""
        )

        // Firebase Cloud Messaging (push). La app inicializa Firebase SOLO si
        // ambos valores existen (Firebase console → Configuración del proyecto:
        // ID del proyecto y clave de API de Android), sin necesidad de
        // google-services.json. Vacío ⇒ push desactivado y la app usa el
        // fallback in-app (user_notifications + Realtime) y el bot de Telegram.
        buildConfigField(
            "String",
            "FCM_PROJECT_ID",
            "\"${secretOrPlaceholder("FCM_PROJECT_ID", "")}\""
        )
        buildConfigField(
            "String",
            "GOOGLE_API_KEY",
            "\"${secretOrPlaceholder("GOOGLE_API_KEY", "")}\""
        )
    }
    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeystoreAlias
                keyPassword = releaseKeystoreKeyPassword
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            // R8 + shrink: requisito de una distribución ligera para redes
            // móviles cubanas; las keep-rules viven en proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // Build sin secretos (CI sin configurar): advertir, no romper.
                project.logger.warn(
                    "⚠ release SIN FIRMAR: falta keystore.properties o " +
                        "KEYSTORE_PATH/KEYSTORE_PASSWORD/KEYSTORE_ALIAS/KEYSTORE_KEY_PASSWORD. " +
                        "Ejecuta scripts/build-apk.sh --gen-keystore para crear uno."
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtimeKtx)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.lifecycle.viewModelCompose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splash.screen)
    implementation(libs.androidx.startup)

    // Compose (BOM controla versiones)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation + Hilt integration
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)

    // Supabase: auth, postgrest, storage, realtime
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.realtime)

    // Ktor Client (llamadas auxiliares fuera de Supabase)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.contentNegotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)

    // Coil 3 (imágenes)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // DataStore (preferencias)
    implementation(libs.datastore.preferences)

    // WorkManager (reintentos de sincronización)
    implementation(libs.androidx.work.runtime)

    // Almacenamiento seguro de tokens
    implementation(libs.security.crypto)

    // Firebase Cloud Messaging (push). Inicialización manual en PushSupport:
    // sin google-services.json ni plugin, Firebase queda inerte si no se configuran
    // FCM_PROJECT_ID/GOOGLE_API_KEY y la app cae al fallback in-app/Telegram.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Serialization + coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Tests
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// Hilt + KSP
ksp {
    arg("dagger.fastInit", "enabled")
}
