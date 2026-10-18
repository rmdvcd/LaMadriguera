# La Madriguera - ProGuard
# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep model classes for kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Trazas legibles en crash reports de una build minificada (distribución fuera de Play).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp
-keepclasseswithmembers class * {
    @dagger.hilt.android.AndroidEntryPoint <methods>;
}

# Supabase / Ktor
-keep class io.ktor.** { *; }
-keep class io.github.jan.supabase.** { *; }

# Coil
-keep class coil.** { *; }

# --- Firebase Cloud Messaging --------------------------------------------
# Servicio declarado en el manifest; el keep explícito asegura que R8 conserve
# el constructor sin argumentos que usa el framework y la inyección de Hilt.
-keep class com.lamadriguera.app.feature.notifications.LaMadrigueraMessagingService { *; }

# kotlinx.serialization: safety-net para los DTOs de red (los serializers se
# buscan por reflexión vía Companion; las consumer-rules de la librería ya
# cubren esto, pero un release firmado fuera de Play no admite sorpresas).
-keepclassmembers class com.lamadriguera.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.lamadriguera.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.lamadriguera.app.data.remote.supabase.row.**$$serializer { *; }
-keep,includedescriptorclasses class com.lamadriguera.app.ui.navigation.**$$serializer { *; }
