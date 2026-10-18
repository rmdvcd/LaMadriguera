# Capa de datos Supabase + Kotlin

## Aplicar el esquema

`migrations/001_initial_schema.sql` es una migración inicial transaccional para una base
**Supabase nueva** (PostgreSQL 15+; requiere `auth.users`, `auth.uid()`, `auth.jwt()` y los
roles `anon`, `authenticated`, `service_role`). No se debe ejecutar dos veces ni sobre un
esquema previo con tablas homónimas.

`migrations/002_auth_identity_privacy.sql` añade las reglas del módulo de autenticación y
perfiles (Fase 1): estados `pending/approved/rejected` de `identity_verifications`,
`profiles.default_reveal_policy`, alias automático para perfiles anónimos, trigger de
reputación sobre `reviews`, promoción de cuenta al confirmar teléfono/aprobar identidad y
el bucket privado `identity-docs` con políticas por carpeta `<auth.uid()>/`. Se ejecuta una
vez, después de la 001.

`migrations/003_listings_auctions.sql` añade el módulo de listings y subastas (Fase 2):
foto opcional (`photo_url`), visibilidad de demandas (`public` / `providers_only`),
estados de subasta (`fixed`, `auction_scheduled`, `auction_live`, `auction_ended`,
`awarded`, `no_reserve_met`, `expired`), validación y bloqueo de `auction_config` tras la
primera puja, reglas de seguridad en pujas (`verification_level='identity'` cuando
`requires_identity=true`, máx. 3 pujas por usuario por subasta, bloqueo por disputa
abierta, auto-extensión), las funciones RPC `place_auction_bid`, `close_auction` y
`close_due_auctions` y el bucket `listing-photos`.

`migrations/004_chat_encryption_disputes.sql` añade el chat cifrado (diseño C) y el flujo
de disputa: `profiles.chat_public_key` (X25519 por dispositivo), `conversation_keys`
(sobres de sesión por participante), `dispute_tickets` + `report_conversation()` y
`resolve_dispute_ticket()` (reportar abre ticket y marca el hilo `disputed`),
`purge_expired_messages()` (elimina el ciphertext vencido; solo `service_role`),
`moderation_actions` estrictamente append-only (logs de descifrado inmutables con quién,
cuándo, ticket y motivo), `user_notifications` + `notify_thread_decrypted()` (aviso a
ambas partes) y `dispute_transcripts` (transcripción del descifrado, solo moderación).

`migrations/005_peer_betting.sql` implementa apuestas entre usuarios, sin procesar pagos:
cuotas opcionales que solo propone el creador (el valor por defecto antiguo `1` se elimina),
controles por usuario (límite diario inicial de 1000 CUP y autoexclusión de 30 días),
registro append-only `bet_events`, evidencias en bucket privado, alta/aceptación/resolución
atómica por RPC y permisos de owner para invitar/expulsar. Las RPC de crear y aceptar exigen
cuenta activa, `verification_level='identity'`, carnet aprobado de tipo `carnet` y 18 años
cumplidos. El día de uso del límite se calcula en UTC; propuestas creadas hoy y apuestas
aceptadas hoy cuentan en el límite. La autoexclusión bloquea nuevas apuestas, pero no impide
cerrar o disputar una ya aceptada.

Ejecutar desde el SQL Editor de Supabase como `postgres`, o con conexión administrativa:

```sh
psql "$SUPABASE_DB_URL" -v ON_ERROR_STOP=1 -f supabase/migrations/001_initial_schema.sql
psql "$SUPABASE_DB_URL" -v ON_ERROR_STOP=1 -f supabase/migrations/002_auth_identity_privacy.sql
psql "$SUPABASE_DB_URL" -v ON_ERROR_STOP=1 -f supabase/migrations/003_listings_auctions.sql
psql "$SUPABASE_DB_URL" -v ON_ERROR_STOP=1 -f supabase/migrations/004_chat_encryption_disputes.sql
psql "$SUPABASE_DB_URL" -v ON_ERROR_STOP=1 -f supabase/migrations/005_peer_betting.sql
```

No poner la contraseña de PostgreSQL ni la service-role key en Android/local.properties.
Las tablas de la aplicación tienen RLS activada, índices, claves foráneas y restricciones.
Los UUID son `String` en Kotlin; el backend valida el formato.
Los IDs se pueden generar con `UUID.randomUUID().toString()`; `profiles` usa `user_id`.

### Identidad, roles y confianza

- `users.id` referencia a `auth.users.id`. Un trigger crea la fila con `temp`/`none`;
  también se provisionan usuarios Auth ya existentes. El cliente no puede autoactivarse,
  autoverificarse ni cambiar reputación, confirmaciones de pago o resolución de apuestas.
- `users`: solo lectura propia, incluso para un JWT admin. Actualización propia de `phone`.
  Ese campo es un dato de contacto, **no una prueba de verificación telefónica**: la
  verificación real debe pasar por Supabase Auth/backend.
- Roles en el JWT: `app_metadata.role = "admin"` o `"moderator"`. Solo el backend mediante
  la API administrativa debe asignarlos. `user_metadata` no concede privilegios.
- Un JWT admin puede leer y revisar verificaciones de identidad; no adquiere acceso
  general a chats. Los moderadores/admin solo leen chats con `status = 'disputed'`.
- Un participante abre la disputa con la RPC `open_conversation_dispute(conversation_id)`.
  No puede modificar participantes/listing/estado directamente. Cerrar/resolver la
  disputa requiere backend confiable; al cerrarla se pierde el acceso del moderador.
- Solo el dueño modifica anuncios. Los visitantes solo ven `active`; el dueño también
  ve sus anuncios no activos. Las pujas del cliente se admiten en anuncios de subasta
  visibles y activos; `auction_live` requiere un flujo servidor para lectura/puja.
- Cada grupo añade al dueño como miembro mediante trigger. Solo el dueño añade/quita
  miembros; ni siquiera un admin no miembro puede leer apuestas con un JWT cliente.
- Los permisos de columna complementan RLS: una política de propiedad por sí sola no
  impediría falsificar `verification_level`, `boosted_until` o confirmaciones de pago.
- La service role omite RLS: se utiliza exclusivamente en un backend confiable, con sus
  propias comprobaciones de autorización y auditoría. No es la identidad del usuario.

## Chat cifrado (diseño C)

- Cada conversación tiene una `session_key` AES-256-GCM generada en el cliente. Los
  mensajes (texto, imagen, audio, ubicación) se cifran en el cliente con AAD
  `msg:<conversationId>:<tipo>` y viajan como ciphertext en `messages.content_ref`
  (formato `v1.` + base64 de `iv || ct || tag`).
- La `session_key` se envuelve con la clave pública X25519 de disputa del hilo
  (sobre ECIES: `0x01 || clave_pub_efímera || iv || ct || tag`, HKDF-SHA256 con
  propósito `lmchat/wrap/dispute/v1`) y se guarda en `conversations.session_key_wrapped`.
- La clave privada de disputa se divide XOR en dos mitades; cada mitad se envuelve a su
  custodio (`lmchat/wrap/custodian/v1`): `dispute_key_split_a` al admin general y
  `dispute_key_split_b` al moderador senior. Ninguna mitad revela nada por separado
  (one-time pad); hace falta el quórum 2-de-2.
- Además la sesión se envuelve por participante (`conversation_keys`, propósito
  `lmchat/wrap/session/v1`) para que ambos usuarios descifren su hilo sin tocar el
  material de disputa. La privada de identidad nunca sale del dispositivo
  (EncryptedSharedPreferences).
- La app nunca descifra hilos ajenos ni usa las mitades de custodia. Al reportar una
  conversación (`report_conversation`) se abre el ticket; un moderador lo aprueba
  (`resolve_dispute_ticket`) y el proceso backend `functions/dispute-decrypt` — con las
  dos privadas de custodia como secretos separados — desenvuelve las mitades, recombina
  la privada de disputa y descifra SOLO esa conversación, registrándolo de forma
  inmutable en `moderation_actions` (actor, ticket, motivo), exportando la transcripción
  para moderación y notificando a ambas partes.
- Autodestrucción: `expires_at` (24 h / 7 d) hace que el cliente descarte el ciphertext
  al instante y `purge_expired_messages()` elimina la fila en el servidor (solo
  `service_role`, p. ej. vía `pg_cron`).

Las claves públicas de custodia se configuran con `DISPUTE_ADMIN_PUB` /
`DISPUTE_MODERATOR_PUB` (local.properties o `-P`). Sin ellas se usa el par de desarrollo
embebido en `DisputeKeyConfig`, cuyas privadas son públicas a propósito: nunca usarlas
en producción.

## Kotlin

Las credenciales públicas se configuran en `local.properties` (ver el ejemplo en la raíz):

```properties
SUPABASE_URL=https://tu-proyecto.supabase.co
SUPABASE_ANON_KEY=tu-anon-key
```

Gradle las copia a `BuildConfig`. `SupabaseClientProvider`, singleton Hilt, instala Auth,
Postgrest, Storage y Realtime con OkHttp y una serialización JSON común. El JWT de la sesión
Auth se aplica automáticamente a las peticiones; no se envía un `user_id` como autorización.

- `data/remote/supabase/row/*Row.kt`: 22 data classes `@Serializable`, con `@SerialName`
  para nombres SQL y enums con valores de wire en minúsculas.
- `domain/model`: modelos sin detalles de PostgREST, `BigDecimal` para todo `numeric`,
  `Instant`/`LocalDate` para fechas y `ByteArray` para `bytea`.
- `data/mapper`: mapeo explícito bidireccional de cada tabla. `bytea` utiliza `\\x` + hex,
  no Base64. `DecimalSerializer` no pasa por `Double` ni pierde precisión monetaria.
- `domain/repository`: contratos tipados por tabla; lectura, inserción, actualización y
  borrado se exponen únicamente donde hay operaciones cliente permitidas por el esquema.
- `data/repository`: implementaciones PostgREST y base compartida; todas están enlazadas
  mediante `core/di/RepositoryModule`. Nunca usar repositorios concretos desde la UI.

| Contrato | Operaciones base de cliente |
|---|---|
| UsersRepository | list/find (RLS propia), update (phone) |
| IdentityVerificationsRepository | list/find/create |
| ProfilesRepository | list/find/create/update |
| CategoriesRepository, ListingsRepository | list/find/create/update/delete |
| BidsRepository, MessagesRepository, ReviewsRepository | list/find/create |
| ConversationsRepository | list/find/create/update (claves y reveal_policy) |
| ReportsRepository, PaymentsRepository | list/find/create |
| BoostsRepository, ModerationActionsRepository | list/find |
| BettingGroupsRepository | list/find/create/update (nombre, tema, visibilidad) |
| BettingGroupMembersRepository | list/find (solo lectura; altas/bajas por RPC) |
| BetsRepository | list/find (solo lectura; cambios por RPC) |
| BettingRepository | grupos, historial, aceptar, resolver/disputar, evidencias y controles por RPC |

`list(page = 0, pageSize = 50)` acepta tamaños 1..100, ordena por PK para paginar de forma
estable y devuelve únicamente filas visibles por RLS. No implementa búsqueda de negocio.
`find(id)` devuelve `Success(null)` si la fila no existe o está oculta. `update/delete`
sobre una fila inaccesible devuelve `AppError.NotFound`. Todas las operaciones devuelven
el `Result` del proyecto; la cancelación de coroutines se propaga y los errores no incluyen
respuestas SQL/PII crudas.

Los modelos de creación son snapshots completos: proporcionar el ID y los campos
obligatorios (incluido `createdAt` cuando exista); el repositorio **omite `created_at` y
campos controlados por servidor** en el INSERT y devuelve la fila persistida. En UPDATE
solo envía las columnas editables, nunca la PK o la propiedad. Los valores omitidos no
implican que se haya ejecutado una acción privilegiada: por ejemplo, `create(payment)`
siempre crea un pago pendiente aunque el snapshot recibido contenga otro estado.

Ejemplo de inyección y uso desde un caso de uso/ViewModel:

```kotlin
class LoadListings @Inject constructor(private val repository: ListingsRepository) {
    suspend operator fun invoke(): Result<List<Listing>> = repository.list(page = 0, pageSize = 20)
}
```

### Apuestas entre usuarios

- La UI usa `BettingGroupScreen`, `CreateBetScreen` y `BetDetailScreen`, con sus ViewModels;
  `BettingRepository` queda enlazado en Hilt a `SupabaseBettingRepository`.
- Owner = administrador del grupo. Invita por teléfono confirmado en Supabase Auth y expulsa
  mediante RPC; el owner no puede expulsarse a sí mismo. No se usa `users.phone` editable
  para conceder membresía. Los grupos públicos se pueden descubrir y los usuarios pueden
  unirse; los grupos privados solo aparecen para sus miembros.
- Crear y aceptar una apuesta son RPC transaccionales. El cliente no tiene `INSERT/UPDATE`
  directo sobre `bets` ni puede escribir estados, propietario, aceptante o decisión.
  `BetsRepository` y `BettingGroupMembersRepository` son de solo lectura.
- Toda apuesta requiere una cuenta activa, `verification_level='identity'`, carnet de
  identidad aprobado y edad de 18 años o más derivada de la fecha de nacimiento del carnet.
  El mismo control se aplica al crear y aceptar; no se confía en el dato local del dispositivo.
- Cada usuario tiene límite inicial de `1000 CUP`, configurable entre 1 y 1 000 000 CUP.
  Propuestas creadas hoy (incluidas las abiertas) y apuestas aceptadas hoy consumen el límite;
  las operaciones bloquean la fila de control para evitar saltarse el tope con peticiones
  paralelas. El día se calcula en UTC.
- «No apostar más» establece una autoexclusión no cancelable de 30 días. Bloquea crear y
  aceptar, no la resolución de una apuesta ya aceptada.
- La cuota es opcional y solo muestra el valor que introdujo el creador. Nunca se crea una
  cuota fija desde la plataforma. La moneda permitida es CUP.
- Cualquier participante puede registrar el resultado con texto o captura. Si las dos partes
  indican resultados distintos, o abren una disputa, `status='disputed'`; solo el owner del
  grupo decide `settled_creator`, `settled_acceptor` o `void`, con una nota. `bet_events`
  conserva el historial completo e inmutable, con evidencias en el bucket privado
  `bet-evidence`; el cliente no puede borrar capturas ya subidas.
- La app no procesa ni garantiza pagos: **el pago se acuerda y ejecuta fuera de la app**.
  No hay escrow, saldo, transferencia, comisión ni confirmación de pago en este feature.

Antes de producción aún se requieren procesos backend para activación/verificación,
estados y antifraude de subastas, confirmación de Transfermóvil, boosts, cálculo de
reputación y moderación. Los documentos y claves deben cifrarse antes de persistir: `bytea`
no cifra por sí mismo. También deben implementarse purga de documentos según
`retention_until`, purga de mensajes según `expires_at`, política de retención para las
capturas de apuestas y gestión/rotación de claves. La RLS no elimina archivos ni aplica
automáticamente políticas de retención.

## Pruebas

### SQL y seguridad (sin proyecto remoto ni credenciales)

```sh
npm ci --prefix supabase
npm test --prefix supabase
```

PGlite ejecuta PostgreSQL real embebido en WASM. Solo se simulan las funciones/tablas de
Supabase Auth y el esquema `storage`; las migraciones se ejecutan sin modificaciones. Las
pruebas cubren RLS, acceso propio/participante/owner, permisos de columna, restricciones y
precisión numeric; también comparan columnas y enums SQL con los nombres wire de Kotlin.
Incluyen las reglas de apuestas: carnet y mayoría de edad, moneda/cuotas opcionales, tope
atómico, evidencia, transición a disputa, decisión del owner, autoexclusión de 30 días,
Storage privado e historial append-only. Esto no sustituye una prueba de integración en un
proyecto Supabase antes de desplegar.

### JVM (requiere JDK 17, Android SDK 35 y dependencias Gradle)

```sh
./gradlew testDebugUnitTest
```

`RowMappersTest`: round-trip row/dominio y JSON de las tablas, nombres snake_case,
defaults/nulls, enums, precisión decimal, timestamps con offset, fecha civil y bytea
(incluyendo referencias malformadas). `BettingRulesTest` cubre elegibilidad 18+, límites y
evidencia; `RepositoryRequestTest` cubre Result y cancelación.
Las pruebas no requieren credenciales ni conexión a Supabase.
