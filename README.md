# La Madriguera · Android

Aplicación Android de **marketplace y apuestas peer-to-peer para Cuba**.

Este repositorio contiene el **esqueleto compilable** del proyecto: arquitectura, navegación,
tema Material 3, inyección de dependencias y contratos de dominio. **Todavía no hay pantallas
implementadas**; cada ruta del grafo muestra un placeholder.

---

## Stack

| Capa | Tecnología |
|---|---|
| Lenguaje | Kotlin 2.1.10 |
| UI | Jetpack Compose + Material 3 (BOM 2024.12.01) |
| Navegación | Navigation Compose 2.8.5 (rutas tipadas con kotlinx.serialization) |
| DI | Hilt 2.56.2 (KSP) |
| Backend | Supabase Kotlin SDK 3.1.1 — auth, postgrest, storage, realtime |
| HTTP auxiliar | Ktor Client 3.0.3 (OkHttp) |
| Imágenes | Coil 3.1.0 |
| Preferencias | DataStore Preferences 1.1.1 |
| Reintentos | WorkManager 2.10.0 + HiltWorkerFactory |
| SDK | minSdk 26 · targetSdk 35 · compileSdk 35 |
| Build | AGP 8.7.3 · Gradle 8.9 · JDK 17 |

---

## Requisitos

- **Android Studio Ladybug (2024.2.1)** o superior.
- **JDK 17** (el que incluye Android Studio; no hace falta instalarlo aparte).
- **Android SDK Platform 35** y **Build-Tools 35.0.0** instalados desde el SDK Manager.
- Acceso a `google()` y `mavenCentral()` para la primera sincronización.
- No se necesita instalar Gradle: el wrapper (`gradlew`) ya está incluido.

> El proyecto usa `local.properties` para la ruta del SDK y para las credenciales de Supabase.
> Android Studio genera este archivo automáticamente al abrir el proyecto.

---

## Configurar Supabase

1. Crea un proyecto en [supabase.com](https://supabase.com) y obtén la **Project URL** y la
   **anon public key** (Settings → API).
2. Copia el archivo de ejemplo:

   ```bash
   cp local.properties.example local.properties
   ```

3. Completa las claves en `local.properties`:

   ```properties
   sdk.dir=/ruta/a/Android/Sdk

   SUPABASE_URL=https://tu-proyecto.supabase.co
   SUPABASE_ANON_KEY=tu-anon-key
   ```

4. Esas claves se exponen en tiempo de compilación como `BuildConfig.SUPABASE_URL` y
   `BuildConfig.SUPABASE_ANON_KEY`.

> `local.properties` está en `.gitignore`. **Nunca commitees la service-role key ni secretos.**
> La anon key es pública por diseño, pero aun así se mantiene fuera del repositorio.
>
> Si no configuras Supabase, la app **compila y arranca** con valores placeholder; las llamadas
> reales fallarán hasta completar las credenciales.

---

## Compilar y ejecutar

### Android Studio

1. `File → Open…` y selecciona la raíz del repositorio (donde está `settings.gradle.kts`).
2. Espera el **Gradle Sync** (la primera vez descarga dependencias).
3. Selecciona la configuración **app** y un emulador/dispositivo con Android 8.0 (API 26) o superior.
4. `Run ▶`.

### Línea de comandos

```bash
# Compilar APK de debug
./gradlew assembleDebug

# Compilar e instalar en el dispositivo conectado
./gradlew installDebug

# Tests unitarios
./gradlew testDebugUnitTest

# Tests instrumentados (requiere emulador/dispositivo)
./gradlew connectedDebugAndroidTest

# Limpiar
./gradlew clean
```

El APK de debug se genera en `app/build/outputs/apk/debug/app-debug.apk`
(con el sufijo de aplicación `com.lamadriguera.app.debug`).

---

## Estructura de paquetes

```
com.lamadriguera.app
├── LaMadrigueraApp.kt         Application: @HiltAndroidApp + WorkManager
├── MainActivity.kt            Entry point Compose
│
├── core/
│   ├── di/                    Hilt: App, Network (Ktor), Supabase, DataStore, Security
│   ├── network/               NetworkMonitor (conectividad para reintentos)
│   ├── security/              TokenManager (sesión cifrada)
│   ├── crypto/                CryptoManager (SHA-256, comparación constante)
│   └── result/                Result<T>, AppError, UiState<T>
│
├── data/
│   ├── local/                 DataStore y base de datos local (fase 2)
│   ├── remote/supabase/       Clientes y DTOs de Supabase (fase 2)
│   └── repository/            Implementaciones de los repositorios (fase 2)
│
├── domain/
│   ├── model/                 User, Listing, Auction, Bet, Conversation, Message
│   ├── repository/            Contratos: Auth, Listings, Auction, Chat, Betting
│   └── usecase/               Casos de uso (fase 2)
│
├── feature/
│   ├── auth/        └── di/AuthModule
│   ├── profile/
│   ├── listings/    └── di/ListingsModule
│   ├── auction/     └── di/AuctionModule
│   ├── chat/        └── di/ChatModule
│   ├── betting/     └── di/BettingModule
│   ├── payments/
│   └── moderation/
│
└── ui/
    ├── theme/                 Color, Type, Theme (Material 3)
    ├── components/            TopBar, BottomBar, estados Loading/Error/Empty
    └── navigation/            Destinations (rutas tipadas) y NavGraph
```

### Regla de dependencias

```
UI (Compose) → ViewModel → UseCase → Repository (interfaz, domain) ← Implementación (data) → Supabase/Ktor
```

- Los **Composables no contienen lógica de negocio** ni llaman a Supabase.
- Toda llamada a Supabase pasa por un **repositorio**.
- La UI depende de interfaces de `domain`, nunca de implementaciones de `data`.

---

## Convenciones

- **MVVM** con `StateFlow` y `UiState` sellada (`Idle`, `Loading`, `Success`, `Error`).
- Repositorios como **interfaces en `domain`**; implementaciones en `data`.
- **`Result<T>` propio** (`core/result`) para todos los retornos de repositorios y casos de uso.
- **Sin lógica de negocio en Composables**.
- **Strings en español** en `res/values/strings.xml`. Para i18n basta añadir `values-en/`,
  `values-pt/`, etc. (no hay textos de UI hardcodeados).
- Navegación **type-safe** con `@Serializable` (`ui/navigation/Destinations.kt`).
- Módulos Hilt por feature (`feature/*/di`) y módulos de infraestructura en `core/di`.

---

## Rutas declaradas

| Flujo | Rutas |
|---|---|
| Arranque / auth | `Splash`, `Login`, `Register`, `ForgotPassword` |
| Inicio | `Home`, `Listings`, `ListingDetail`, `CreateListing`, `EditListing` |
| Subastas | `Auctions`, `AuctionDetail`, `CreateAuction` |
| Chat | `Conversations`, `ChatThread` |
| Apuestas | `BettingHub`, `BetDetail`, `CreateBet`, `MyBets` |
| Perfil | `Profile`, `EditProfile`, `Settings` |
| Pagos | `Payments`, `Deposit`, `Withdraw`, `TransactionHistory` |
| Moderación | `Moderation`, `Reports`, `ReportDetail` |

La barra inferior muestra: Publicaciones, Subastas, Chat, Apuestas y Perfil.

> Mientras no exista autenticación, la app arranca directamente en **Publicaciones**
> para poder ver el shell de navegación. En la fase 1 el arranque pasará por
> `Splash` y resolverá la sesión.

---

## Tema

Paleta sobria definida en `ui/theme/Color.kt`:

- **Primario:** `#2E5E4E` (verde bosque).
- **Secundario:** `#C9A227` (dorado mostaza).
- Modo claro y oscuro. Material You (color dinámico) está **desactivado** por defecto para
  preservar la identidad de marca; se puede activar pasando `dynamicColor = true` a
  `LaMadrigueraTheme`.

Tipografía del sistema para mantener el APK ligero (redes móviles limitadas).

---

## Estado del proyecto y siguientes pasos

- [x] **Fase 0 — Esqueleto:** Gradle, Hilt, Supabase, Ktor, Coil, DataStore, WorkManager,
      tema, navegación vacía, contratos de dominio y placeholders.
- [ ] **Fase 1 — Autenticación:** Supabase Auth, sesión persistente, pantallas Login/Registro,
      resolución Splash → Login/Home.
- [ ] **Fase 2 — Marketplace:** listado y detalle con Postgrest + Storage/Coil.
- [ ] **Fase 3 — Chat:** Realtime, conversaciones y mensajes.
- [ ] **Fase 4 — Subastas y apuestas:** pujas, validación de saldo y reglas peer-to-peer.
- [ ] **Fase 5 — Pagos y moderación.**
- [ ] **Fase 6 — Offline-first:** Room + WorkManager para sincronización con redes inestables.

---

## Notas técnicas

- **WorkManager:** `LaMadrigueraApp` implementa `Configuration.Provider` con `HiltWorkerFactory`.
  El initializer por defecto de `androidx.startup` está desactivado en el manifest.
- **Sesión:** Supabase Auth guarda la sesión automáticamente (`SettingsSessionManager`).
  `TokenManager` ofrece además almacenamiento cifrado con `EncryptedSharedPreferences`.
- **Backups:** las preferencias y bases de datos se excluyen del auto-backup para no copiar
  tokens de sesión.
- **Splash:** `core-splashscreen` con tema dedicado `Theme.LaMadriguera.Splash`.
- **Iconos:** adaptive icon vectorial (minSdk 26).

---

## Troubleshooting

| Problema | Solución |
|---|---|
| `SDK location not found` | Abre el proyecto en Android Studio o define `sdk.dir` en `local.properties`. |
| `Unsupported class file major version` | Usa JDK 17 (`Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK`). |
| `compileSdk 35 not found` | Instala *Android SDK Platform 35* en el SDK Manager. |
| Error de KSP/Hilt tras actualizar Kotlin | Mantén alineadas las versiones de Kotlin y KSP en `gradle/libs.versions.toml`. |
| Falla la sincronización por dependencias | Verifica conexión a Google Maven y Maven Central; luego `./gradlew --refresh-dependencies`. |
| Errores con configuration cache | Ejecuta con `--no-configuration-cache` o desactívalo en `gradle.properties`. |
