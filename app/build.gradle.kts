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

android {
    namespace = "com.lamadriguera.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lamadriguera.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-dev"

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
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
