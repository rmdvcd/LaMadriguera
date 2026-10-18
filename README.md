# La Madriguera · Android

Aplicación Android de **marketplace y apuestas peer-to-peer para Cuba**.

Este repositorio contiene el esqueleto Android (arquitectura, navegación, tema e inyección),
la **capa de persistencia Supabase/Kotlin** (migraciones SQL con RLS, DTOs serializables,
modelos, mappers y repositorios inyectados por Hilt), el **módulo de autenticación y
perfiles** y el **flujo de monetización** (Fase 5): promociones, pagos manuales por
Transfermóvil y conciliación privada por admin/cobrador.

La configuración del esquema, permisos y pruebas de la capa de datos se documenta en
[`supabase/README.md`](supabase/README.md).

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
| Push | Firebase Cloud Messaging (HTTP v1) + fallback in-app Realtime + bot Telegram opcional |
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

   Para el push FCM (opcional) añade `FCM_PROJECT_ID` y `GOOGLE_API_KEY`; sin ellos la
   app compila e ignora Firebase y los avisos llegan por el fallback in-app (ver
   *Notificaciones push*).

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

# Compilar APK de release firmado (+ SHA-256 + version.json + página de descarga)
scripts/build-apk.sh

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

## Compilar un release firmado (fuera de Google Play)

La Madriguera se distribuye **sin tienda**: el usuario descarga el APK, verifica
el hash y lo instala. Para eso el APK debe ir firmado con la clave de release
del proyecto (siempre la misma; si se pierde, no se pueden actualizar las
instalaciones existentes).

### 1. La clave de firma (una sola vez, en la máquina que publica)

```bash
# Crea release.keystore + keystore.properties con contraseña aleatoria fuerte.
scripts/build-apk.sh --gen-keystore
```

El par `release.keystore` + `keystore.properties` **no se commitea** (está en
`.gitignore`): guárdalo en un gestor de contraseñas o cofre cifrado. Equivale a
`keytool -genkeypair -keystore release.keystore -alias lamadriguera -keyalg RSA
-keysize 2048 -validity 10000` y genera la misma configuración que el script.

Alternativa manual: copia `keystore.properties.example` → `keystore.properties`
y rellena `storeFile/storePassword/keyAlias/keyPassword`. En CI se usan las
variables de entorno `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEYSTORE_ALIAS` y
`KEYSTORE_KEY_PASSWORD` (el workflow las materializa desde secrets; la clave
nunca vive en el repositorio).

### 2. Compilar

```bash
scripts/build-apk.sh
```

Qué hace, en orden:

1. Lee `VERSION_NAME` / `VERSION_CODE` de `gradle.properties` (única fuente de
   verdad; se pueden sobreescribir con variables de entorno y viajan a Gradle
   como `-P`). **Cada release que se publique fuera de Play debe subir el
   `versionCode`** o no sustituirá a la instalación anterior.
2. Ejecuta `./gradlew assembleRelease` (R8 + shrink de recursos activados en
   release; `proguard-rules.pro` conserva serializables, Hilt y el servicio FCM).
3. Copia el APK a `dist/LaMadriguera-v<versión>-<code>.apk` y calcula su
   **SHA-256** (`dist/sha256sums.txt`).
4. Verifica la firma con `apksigner` si el SDK está a mano.
5. Escribe **`dist/version.json`** — `versionCode`, `versionName`, `url` de
   descarga y `sha256`, junto a tamaño, `minSdk` y fecha:

   ```json
   {
     "versionCode": 1,
     "versionName": "1.0.0",
     "url": "https://github.com/rmdvcd/LaMadriguera/releases/latest/download/LaMadriguera-v1.0.0-1.apk",
     "sha256": "c52a…",
     "fileName": "LaMadriguera-v1.0.0-1.apk",
     "sizeBytes": 24890132,
     "minSdk": 26,
     "releasedAt": "2026-10-18T12:00:00Z"
   }
   ```

   La `url` usa `https://github.com/rmdvcd/LaMadriguera/releases/latest/download/…`
   por defecto; con `DOWNLOAD_BASE_URL=https://dl.tudominio/apks` (o `--url`)
   apunta a tu propio espejo.
6. Rellena la plantilla `scripts/templates/download-page.html.tmpl` y genera
   **`docs/download/index.html`** + `docs/download/version.json`: la página de
   descarga con botón al APK, SHA-256 copiable, instrucciones de instalación y
   el changelog del primer bloque de `docs/CHANGELOG.md`.

Flags útiles: `--skip-build` (rehacer hash/versión/página sin recompilar),
`--no-page` (solo APK + version.json), `--url` (base de descarga),
`--gen-keystore`.

> ⚠️ En este sandbox no hay Android SDK ni acceso a Google Maven, así que el
> APK real se compila en la máquina del mantenedor o en CI (workflow abajo).
> `assembleRelease` sin keystore compilado **no falla**: produce un APK sin
> firmar y lo advierte — un APK sin firmar no se puede instalar.

### 3. Publicar y anunciar

```bash
gh release create v1.0.0 dist/LaMadriguera-v1.0.0-1.apk dist/version.json dist/sha256sums.txt \
  -t "v1.0.0" -F <(awk '/^## /{n++;next} n>1{exit} n==1' docs/CHANGELOG.md)
scripts/telegram-announce.sh          # --dry-run para previsualizar
```

`scripts/telegram-announce.sh` publica en el **canal de Telegram de
actualizaciones** la versión, el enlace, el SHA-256 y las novedades; además
adjunta el `version.json` como documento, de modo que el canal queda como
registro público de cada release. Requiere `TELEGRAM_BOT_TOKEN` y
`TELEGRAM_CHAT_ID` (entorno o `local.properties`; el bot debe ser admin del
canal con permiso de publicar).

### 4. Automatizado: workflow de release

`.github/workflows/android-release.yml` corre `scripts/build-apk.sh` en CI al
pushear un tag `v*` (o a mano desde Actions): materializa el keystore desde el
secret `RELEASE_KEYSTORE_B64`, crea la release de GitHub con el APK +
`version.json` + `sha256sums.txt`, anuncia en Telegram y, si se activa la
variable `PUBLISH_DOWNLOAD_PAGE`, sube `docs/download/` a la rama `gh-pages`
(GitHub Pages). Secrets listados en la cabecera del workflow.

---

## Instalar el APK desde fuera de la tienda (origen desconocido)

Guía para quien recibe el APK (se resume en la propia página de descarga):

1. **Descarga solo desde el canal oficial**: la página `docs/download/`
   (GitHub Pages) o las Release de GitHub, anunciadas en el canal de Telegram.
2. **Comprueba el hash** antes de abrir el archivo:
   `sha256sum LaMadriguera-v1.0.0-1.apk` debe coincidir con el SHA-256 publicado
   junto a esa versión. Si difiere, bórralo.
3. Android 8+ (API 26 o superior, requisito de la app): al tocar el APK,
   Ajustes pedirá **permitir instalar aplicaciones desconocidas** para la app
   que hizo la descarga (el navegador). Es por-app, no global: concedes solo a
   ese navegador y conviene revocarlo después de instalar
   (Ajustes → Aplicaciones → tu navegador → Instalar apps desconocidas → No permitir).
4. Si **Play Protect** muestra un aviso por "app desconocida", selecciona
   *Instalar de todos modos*. El APK está firmado con la clave del proyecto y
   su hash es público; no pasa por la tienda simplemente porque no hay Play
   Services garantizados en los dispositivos destino.
5. Las versiones siguientes **se actualizan encima sin perder datos** (misma
   firma, `versionCode` mayor). La app no se autoactualiza: cada release se
   anuncia en el canal de Telegram con su enlace y hash.
6. La app firma con `applicationId com.lamadriguera.app`; si tenías una build
   de `debug` (sufijo `.debug`), convive aparte: no estorba, pero bórrala si no
   la quieres.

Riesgos asumidos fuera de Play (documentados para los usuarios): sin revisión de
la tienda, la verificación de integridad es el hash; las actualizaciones son
manuales; un APK de otro enlace puede ser malicioso. Por eso la página publica
el SHA-256 junto al botón.

---

## Notificaciones push (Fase 8)

Cuatro avisos salen por **push** además de quedar en la bandeja in-app:

| Evento | `user_notifications.type` | Canal Android |
|---|---|---|
| Oferta recibida (puja sobre tu publicación) | `oferta_recibida` | `madriguera_ofertas` |
| Mensaje nuevo | `mensaje_nuevo` | `madriguera_mensajes` |
| Pago confirmado / rechazado / vencido | `payment_confirmed` … (fase 5) | `madriguera_pagos` |
| Subasta ganada | `subasta_ganada` | `madriguera_subastas` |

Los tres nuevos los generan triggers de la migración
`supabase/migrations/009_push_notifications.sql`; todo notifica **primero** a
`user_notifications`, que ya viaja por Realtime — la bandeja y el fallback
existen aunque no haya push. El aviso de chat es genérico por diseño: el
contenido está cifrado de extremo a extremo y el servidor no puede leerlo (ni
debería meterlo en un push).

### Flujo

```
BD (INSERT user_notifications) ─▶ webhook ─▶ Edge Function push-dispatch
     │                                            │
     │                              FCM HTTP v1 ──┼──▶ dispositivos con token (push)
     │                                            │
     │                              Telegram sendMessage ──▶ chat_id (si el usuario lo
     │                                                       compartió y el push no llegó)
     ▼
 Realtime ──▶ bandeja in-app + aviso del sistema (fallback en primer plano
              o cuando no hay Play Services / Firebase)
```

- **Cliente** (`core/push/PushSupport`, `feature/notifications/`): sin
  `google-services.json` ni plugin — la build lee `FCM_PROJECT_ID` y
  `GOOGLE_API_KEY` de `local.properties` y, si existen, inicializa Firebase a
  mano; `LaMadrigueraMessagingService` registra el token en `user_push_tokens`
  (tabla con RLS, creada en 009) cada vez que la sesión cambia o FCM rota el
  token, y lo borra al cerrar sesión. Sin esas claves, Firebase no se levanta:
  el servicio queda inerte y no cuesta nada.
- **Ajustes** (Perfil → Notificaciones): interruptor maestro + por tipo, estado
  real del push en el dispositivo, botón para conceder el permiso de
  notificaciones (Android 13+) y sección **Telegram (opcional)**: nada se envía
  al bot salvo que el usuario escriba su `chat_id` y active la casilla
  (`user_notification_settings`, validado en la BD: solo dígitos).
- **Servidor** (`supabase/functions/push-dispatch`): obtiene el OAuth token con
  la cuenta de servicio (JWT RS256, nunca una clave estática), envía a todos
  los dispositivos de la cuenta respetando los interruptores, purga los tokens
  que FCM reporta como no registrados y hace el fallback a Telegram SOLO si no
  hubo ninguna entrega push.
- **Degradación suave**: dispositivo sin Google Play Services (habitual en
  ROMs cubanas) → no hay token FCM → la app muestra el aviso vía Realtime en
  primer plano, la bandeja lo conserva, y Telegram cubre el "fuera de la app"
  si el usuario optó.

### Puesta en marcha (una vez)

1. Firebase console → crea el proyecto → **ID del proyecto** y clave de API
   Android → a `local.properties` (`FCM_PROJECT_ID`, `GOOGLE_API_KEY`) y al
   secret homónimo del workflow. No se registra el paquete en Play: FCM no lo
   exige.
2. Genera una **cuenta de servicio** (Configuración de la cuenta → Cuentas de
   servicio → clave JSON) y súbelas como `FIREBASE_SERVICE_ACCOUNT_JSON`.
3. Aplica la migración `009_push_notifications.sql` con las GUCs apuntando a la
   función:
   ```sql
   set app.push_dispatch_url = 'https://<ref>.supabase.co/functions/v1/push-dispatch';
   set app.push_dispatch_secret = '<token aleatorio>';
   ```
   (o configura el Database Webhook `user_notifications` → INSERT desde el
   dashboard con el header `x-push-secret`).
4. `supabase functions deploy push-dispatch --no-verify-jwt` y los secretos:
   `PUSH_DISPATCH_SECRET`, `FIREBASE_PROJECT_ID`,
   `FIREBASE_SERVICE_ACCOUNT_JSON` y `TELEGRAM_BOT_TOKEN` (opcional).
5. Bot de avisos (opcional): créalo con @BotFather; cada usuario obtiene su
   `chat_id` hablándole (p. ej. con `/id`) y lo pega en Ajustes → Notificaciones.

---

## Estructura de paquetes

```
com.lamadriguera.app
├── LaMadrigueraApp.kt         Application: @HiltAndroidApp + WorkManager
├── MainActivity.kt            Entry point Compose
│
├── core/
│   ├── di/                    Hilt: App, Network (Ktor), Supabase, DataStore, Security
│   ├── push/                  PushSupport (Firebase manual sin google-services.json, canales)
│   ├── network/               NetworkMonitor (conectividad para reintentos)
│   ├── security/              TokenManager (sesión cifrada)
│   ├── crypto/                CryptoManager (SHA-256, comparación constante)
│   └── result/                Result<T>, AppError, UiState<T>
│
├── data/
│   ├── local/                 DataStore y base de datos local (fase 2)
│   ├── remote/supabase/       Provider, JSON y 25 DTOs de tabla/RPC (@Serializable)
│   ├── remote/moderation/     RemoteModerationApi: Edge Function moderate-text (Ktor)
│   ├── moderation/            NsfwJsImageScanner (NSFW JS en WebView)
│   ├── mapper/                Row ↔ dominio, bytea, fechas y decimales exactos
│   └── repository/            Repositorios PostgREST por tabla
│
├── domain/
│   ├── model/                 User, Listing, Auction, Bet, Conversation, Message, Moderation
│   ├── repository/            Contratos: Auth, Listings, Auction, Chat, Betting, Moderation
│   └── usecase/               ModerateContentUseCase, ModerationRules, ImageUploadGate
│
├── feature/
│   ├── auth/        └── di/AuthModule, Splash, PhoneLogin (OTP), IdentityVerification
│   ├── profile/     └── Profile, PrivacySettings
│   ├── listings/    └── di/ListingsModule
│   ├── auction/     └── di/AuctionModule
│   ├── chat/        └── di/ChatModule
│   ├── betting/     └── di/BettingModule
│   ├── payments/
│   ├── notifications/ └── servicio FCM, registro de token, ajustes + bandeja (fallback)
│   └── moderation/    └── ReportButton, AdminQueueScreen, GroupAdminPanel (+ViewModels)
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
| Arranque / auth | `Splash`, `PhoneLogin` (teléfono + OTP), `IdentityVerification` |
| Inicio | `Home`, `Listings`, `ListingDetail`, `CreateListing`, `EditListing` |
| Subastas | `Auctions`, `AuctionDetail`, `CreateAuction` |
| Chat | `Conversations`, `ChatThread` |
| Apuestas | `BettingHub`, `BettingGroup`, `CreateBet`, `BetDetail`, `MyBets` |
| Perfil | `Profile`, `EditProfile`, `Settings`, `PrivacySettings` |
| Pagos | `Payments`, `AdminPaymentQueue`, `Deposit`, `Withdraw`, `TransactionHistory` |
| Moderación | `Moderation` (cola AdminQueueScreen), `GroupAdminPanel` |

La barra inferior muestra: Publicaciones, Subastas, Chat, Apuestas y Perfil.

La app arranca en **Splash**, que resuelve la sesión: sin sesión va a
`PhoneLogin`; con sesión, a `Publicaciones`. `Login`, `Register` y
`ForgotPassword` desaparecieron: el registro y el login comparten el flujo de
teléfono + código OTP.

---

## Autenticación y perfiles (Fase 1)

- **Acceso**: teléfono + código OTP vía Supabase Auth (provider `OTP`/phone).
  `SupabaseAuthRepository` envía el SMS (`signInWith(OTP)`), verifica el código
  (`verifyPhoneOtp`), provisiona `profiles` y expone `sessionState` reactivo.
- **Estado `temp`**: sin verificar identidad el usuario puede navegar, pero no
  crear grupos ni apostar. Las **capacidades** viven en `UserCapabilities` y se
  derivan de `users.status`, `users.verification_level` y la clase de cuenta.
- **1 teléfono = 1 cuenta principal**: el teléfono tiene unicidad en la BD y el
  dispositivo registra su cuenta principal (`DeviceAccountRegistry`). Las
  cuentas adicionales requieren verificación de identidad para subastas y
  apuestas.
- **Verificación de identidad**: `IdentityVerificationScreen` sube frente y
  reverso del carnet al bucket privado `identity-docs` (cada usuario solo ve su
  carpeta `<uid>/`), y crea una fila `identity_verifications` en `pending`. Un
  administrador aprueba (`approved`) o rechaza (`rejected`); al aprobar, un
  trigger promueve la cuenta a `verification_level='identity'`/`status='active'`.
  Hasta entonces `verification_level='phone'`.
- **Privacidad** (`PrivacySettingsScreen`): nivel por defecto `public`/`anon`,
  política de revelación en chat (`never`/`on_deal`/`on_dispute`/`configurable`)
  y alias. Si se elige `anon` sin alias, se genera `Anon-XXXXXX` (mismo
  algoritmo en cliente y en el trigger de Postgres). Aviso: *solo puedes cambiar
  el nivel antes de publicar* (bloqueado si el usuario ya publicó).
- **Reputación**: `profiles.reputation_avg` y `reviews_count` los calcula un
  trigger de PostgreSQL al insertar una review (solo las publicadas); la UI las
  muestra como estrellas + número de reseñas.

---

## Moderación de contenidos (Fase 6)

Cuatro capas, de más automática a más humana:

1. **IA en texto** (`ModerateContentUseCase` + `RemoteModerationApi` → Edge Function
   `supabase/functions/moderate-text`): se ejecuta ANTES de publicar un listing, un mensaje
   en el muro de grupos públicos, una apuesta o un nombre de perfil/grupo. Una categoría
   grave (violencia, acoso, contenido sexual, apuestas ilegales) **bloquea** la publicación y
   queda registrada en `moderation_actions` con la evidencia del texto.
2. **IA en imágenes** (`ImageUploadGate` + `NsfwJsImageScanner`, modelo NSFW JS en una
   WebView aislada + heurística de piel): la foto se comprueba **antes de subirla**; si es
   dudosa, no se sube ("si es dudoso, no se sube").
3. **Reglas de dominio** (`ModerationRules`): teléfonos en campos prohibidos, enlaces
   acortadores/sospechosos, patrones de spam y referencias a apuestas ilegales.
4. **Cola humana**: SOLO lo que la IA marca como dudoso (umbral bajo, score ≥ 0.2) entra en
   `moderation_reviews`; se publica con revisión posterior y un admin decide.

### Configuración por variable de entorno

| Variable | Dónde | Valores |
|---|---|---|
| `MODERATION_PROVIDER` | Secreto de la Edge Function | `openai` \| `llamaguard` \| `none` (por defecto: sin IA, todo pasa) |
| `MODERATION_API_URL` | Secreto de la Edge Function / `local.properties` (cliente) | URL del proveedor (opcional) |
| `MODERATION_API_KEY` | Secreto de la Edge Function | Clave del proveedor |
| `MODERATION_MODEL` | Secreto de la Edge Function | Modelo (ej. `omni-moderation-latest`) |
| `NSFWJS_MODEL_URL` | `local.properties` (BuildConfig) | URL del modelo NSFW JS (opcional) |

Despliegue: `supabase functions deploy moderate-text` (ver `supabase/README.md`).

### Sanciones y evidencia

- **Exactamente**: `warn`, `hide`, `suspend` (30 días exactos), `ban`. Las aplican los
  moderadores de plataforma desde `AdminQueueScreen`; los dueños de grupo solo `warn`/`hide`
  en SU grupo (`GroupAdminPanel`) y pueden expulsar miembros.
- **Nunca se borra el contenido original**: se marca oculto (`hidden_at/by/reason`) y la
  evidencia completa queda en `moderation_actions` (append-only). El contenido bloqueado
  antes de publicar se registra igualmente con la evidencia del intento.
- La plataforma recibe reportes de todo, pero **no modera el contenido privado** de los
  grupos (muro, mensajes de grupo): eso es competencia del admin del grupo. El chat privado
  solo se interviene por el flujo de disputas cifradas de la fase 3.

### Entregables

| Pieza | Dónde |
|---|---|
| `ModerateContentUseCase` | `domain/usecase/ModerateContentUseCase.kt` |
| `AdminQueueScreen` (filtros por severidad y estado) | `feature/moderation/AdminQueueScreen.kt` |
| `GroupAdminPanel` (reglas visibles + admin responsable) | `feature/moderation/GroupAdminPanel.kt` |
| API de moderación configurable | `supabase/functions/moderate-text` + `MODERATION_PROVIDER` |

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
- [x] **Fase 1 — Autenticación y perfiles:** Supabase Auth por teléfono + OTP, sesión
      persistente, Splash → PhoneLogin/Publicaciones, verificación de identidad con
      carnet (Storage privado + revisión admin), perfil con reputación y privacidad
      (alias automático, revelación en chat).
- [x] **Fase 2 — Marketplace y Subastas:** creación de ofertas y demandas (visibilidad
      pública o solo proveedores), compresión de foto en cliente (`<= 1 MB`), listado con
      búsqueda por municipio + categoría + filtro offer/request, ranking ponderado
      (relevancia 45%, proximidad 25%, reputación 15%, frescura 10%, respuesta 5%; boost
      pagado ×1.3 aunque aún no haya reseñas; el legado conserva umbral antispam), modo subasta configurable (`AuctionConfigScreen`,
      `ListingDetailScreen`, `BidHistoryScreen`), seguridad de pujas y cierre de subastas
      (RPC `close_auction` / `close_due_auctions` + `AuctionClosureWorker`).
- [x] **Fase 3 — Chat cifrado (diseño C):** conversaciones y mensajes en tiempo real
      (Realtime) con cifrado extremo a extremo (AES-256-GCM + X25519, HKDF-SHA256):
      texto, imagen/audio ≤ 1 MB y ubicación; caducidad opcional de 24 h / 7 d con
      autodestrucción en cliente y `purge_expired_messages()` en el servidor; aviso
      al compartir teléfono/correo; reporte que abre ticket de disputa; descifrado
      SOLO con quórum 2-de-2 (admin general + moderador senior) por el proceso
      `supabase/functions/dispute-decrypt`, con log inmutable en `moderation_actions`,
      transcripción para moderación y aviso a ambas partes. Ver `supabase/README.md`.
- [x] **Fase 4 — Apuestas P2P responsables:** grupos privados/públicos, owner administra
      miembros, cuotas opcionales propuestas por el creador, historial y evidencias, disputa
      resuelta por el owner, carnet aprobado + 18+, límite diario configurable y
      autoexclusión de 30 días. La app registra resultados; el pago se acuerda y ejecuta
      fuera de la app.
- [x] **Fase 5 — Monetización y conciliación:** boosts 24 h/7 días, destacados en espacios
      1 de cada 5, alcance geográfico, pagos Transfermóvil con comprobante privado y hash
      antifraude, cola admin/cobrador con URL firmada, confirmación/rechazo y notificaciones.
      Tarifas y cuenta parten sin configurar; las compras quedan bloqueadas hasta activarlas.
      Ver despliegue y roles en `supabase/README.md`.
- [x] **Fase 6 — Moderación de contenidos:** texto con IA (OpenAI Moderation o Llama Guard)
      configurable por variable de entorno, imágenes NSFW JS en cliente, reglas de dominio
      (teléfonos, enlaces, spam), cola humana solo para lo dudoso, ReportButton,
      `AdminQueueScreen`, `GroupAdminPanel`, sanciones warn/hide/suspend(30 d)/ban y
      evidencia inmutable. Ver la sección siguiente y `supabase/README.md`.
- [ ] **Fase 7 — Offline-first:** Room + WorkManager para sincronización con redes inestables.
- [x] **Fase 8 — Distribución fuera de Play y push:** release firmado con clave propia
      (`scripts/build-apk.sh --gen-keystore`), APK + SHA-256 + `version.json` + página de
      descarga renderizada, canal de Telegram de actualizaciones (`scripts/telegram-announce.sh`)
      y workflow de release; notificaciones push FCM (oferta recibida, mensaje nuevo, pago
      confirmado, subasta ganada) con fallback in-app por Realtime y bot opcional de Telegram
      solo si el usuario comparte su `chat_id`.

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
