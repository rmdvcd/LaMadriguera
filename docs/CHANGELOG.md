# Changelog — La Madriguera

Registro público de versiones para la distribución fuera de Google Play.
`scripts/build-apk.sh` usa el **primer bloque `##`** para la página de descarga
y para el mensaje del canal de Telegram, así que la sección más reciente va
siempre arriba. Formato: una línea `- ` por novedad.

## [1.0.0] — 2026-10-18 (build 1)

- Primera distribución pública fuera de Google Play: APK firmado con clave de release propia.
- Marketplace completo: publicaciones (oferta/demanda), subastas con pujas y cierre automático, chat cifrado de extremo a extremo.
- Apuestas P2P responsables: límites diarios, autoexclusión y carné verificado para apostar.
- Pagos manuales por Transfermóvil con comprobante privado, hash antifraude y conciliación por admin/cobrador.
- Notificaciones push con FCM (oferta recibida, mensaje nuevo, pago confirmado, subasta ganada).
- Sin Google Play Services o sin Firebase configurado: la app no pierde avisos — notificación in-app en tiempo real y bot opcional de Telegram si compartes tu `chat_id`.
- Moderación en 4 capas (IA de texto, NSFW en cliente, reglas de dominio y cola humana) con evidencia inmutable.
- Página de descarga oficial con SHA-256 y este changelog; canal de Telegram de actualizaciones.
