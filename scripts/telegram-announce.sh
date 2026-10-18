#!/usr/bin/env bash
# =============================================================================
# scripts/telegram-announce.sh — publica el release en el canal de Telegram.
#
# Anuncia versión, enlace de descarga y SHA-256 leyendo dist/release.env y
# dist/version.json (generados por scripts/build-apk.sh). En cada release el
# canal queda con version.json + hash, como exige el flujo de distribución.
#
# Configuración (en el entorno o en local.properties):
#   TELEGRAM_BOT_TOKEN   token del bot creado con @BotFather
#   TELEGRAM_CHAT_ID     id del canal: @tucanal o -100xxxxxxxxxx (el bot debe
#                        ser administrador del canal con permiso de publicar)
#
# Uso:
#   scripts/telegram-announce.sh              # envía el mensaje
#   scripts/telegram-announce.sh --dry-run    # imprime el mensaje sin enviar
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

die() { echo "ERROR: $*" >&2; exit 1; }

local_prop() { sed -n "s/^$1=//p" local.properties 2>/dev/null | head -n1 | tr -d '\r'; }

BOT_TOKEN="${TELEGRAM_BOT_TOKEN:-$(local_prop TELEGRAM_BOT_TOKEN || true)}"
CHAT_ID="${TELEGRAM_CHAT_ID:-$(local_prop TELEGRAM_CHAT_ID || true)}"
[[ -f dist/release.env ]] || die "falta dist/release.env: ejecuta primero scripts/build-apk.sh"
# shellcheck disable=SC1091
source dist/release.env

# Notas de la versión: primer bloque de docs/CHANGELOG.md (markdown plano).
NOTES=""
if [[ -f docs/CHANGELOG.md ]]; then
    NOTES="$(awk '
        /^## / { n++; next }
        n > 1  { exit }
        n == 1 && /^- / { print "•  " substr($0, 3) }
    ' docs/CHANGELOG.md | sed 's/\*\*//g; s/`//g' || true)"
fi

NOTES_BLOCK=""
if [[ -n "$NOTES" ]]; then
    NOTES_BLOCK="🆕 <b>Novedades</b>
$NOTES"
fi

MESSAGE=$(cat <<EOF
🐺 <b>La Madriguera v${VERSION_NAME}</b> (build ${VERSION_CODE})

📦 <a href="${APK_URL}">Descargar APK</a> (${SIZE_BYTES} bytes)
🔐 SHA-256:
<code>${SHA256}</code>

${NOTES_BLOCK}

⚠️ Instalar desde fuera de Play: activa «orígenes desconocidos» solo para tu navegador de descarga y comprueba el hash antes de instalar. Las instrucciones están en el README del repo.
EOF
)

echo "────────────── mensaje al canal ──────────────"
echo "$MESSAGE"
echo "───────────────────────────────────────────────"

if [[ "$DRY_RUN" == 1 ]]; then
    echo "(dry-run: nada enviado)"
    exit 0
fi

[[ -n "$BOT_TOKEN" && -n "$CHAT_ID" ]] || die \
    "faltan TELEGRAM_BOT_TOKEN y/o TELEGRAM_CHAT_ID (entorno o local.properties). Usa --dry-run para previsualizar."

command -v curl >/dev/null 2>&1 || die "curl no está disponible"

# Se envía como documento además de texto: el canal conserva el version.json
# oficial junto al hash (los clientes lo usan para comprobar actualizaciones).
if [[ -f dist/version.json ]]; then
    curl -sS -F chat_id="$CHAT_ID" -F document=@"dist/version.json" \
        -F caption="$MESSAGE" -F parse_mode=HTML -F disable_web_page_preview=true \
        "https://api.telegram.org/bot${BOT_TOKEN}/sendDocument" >/dev/null
else
    curl -sS -X POST "https://api.telegram.org/bot${BOT_TOKEN}/sendMessage" \
        -d chat_id="$CHAT_ID" -d parse_mode=HTML -d disable_web_page_preview=true \
        --data-urlencode text="$MESSAGE" >/dev/null
fi
echo "✅ Publicado en Telegram (chat_id=${CHAT_ID})."
