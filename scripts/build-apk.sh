#!/usr/bin/env bash
# =============================================================================
# scripts/build-apk.sh — compila el APK de release FIRMADO de La Madriguera,
# calcula su SHA-256, genera version.json y renderiza la página de descarga.
#
# Uso:
#   scripts/build-apk.sh                    # build + firma + hash + version.json + web
#   scripts/build-apk.sh --gen-keystore     # primero crea release.keystore y keystore.properties
#   scripts/build-apk.sh --skip-build       # solo regenera version.json/página desde el APK ya compilado
#   scripts/build-apk.sh --no-page          # no renderiza docs/download/index.html
#
# Variables de entorno útiles:
#   VERSION_NAME / VERSION_CODE   sobreescriben lo puesto en gradle.properties (se pasa por -P a Gradle)
#   DOWNLOAD_BASE_URL             URL base donde se publicará el APK
#                               (ej. https://dl.example.org/apks ⇒ el APK debe subirse con ese nombre)
#   KEYSTORE_PATH, KEYSTORE_PASSWORD, KEYSTORE_ALIAS, KEYSTORE_KEY_PASSWORD
#                               firma por entorno (alternativa a keystore.properties; úsala en CI)
#
# Salida:
#   dist/LaMadriguera-v<VERSION>-<CODE>.apk   el APK firmado (no se commitea)
#   dist/version.json                          metadatos del release
#   dist/sha256sums.txt                        hash verificable
#   dist/release.env                           mismo resumen, fácil de sourcear desde shell
#   docs/download/index.html                   página de descarga (plantilla rellena)
#   docs/download/version.json                 copia publicada junto a la web
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GEN_KEYSTORE=0
SKIP_BUILD=0
RENDER_PAGE=1
APK_URL_BASE="${DOWNLOAD_BASE_URL:-}"

die() { echo "ERROR: $*" >&2; exit 1; }
info() { echo "==> $*"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --gen-keystore) GEN_KEYSTORE=1; shift ;;
        --skip-build)   SKIP_BUILD=1; shift ;;
        --no-page)      RENDER_PAGE=0; shift ;;
        --url)          [[ $# -ge 2 ]] || die "--url necesita un valor"; APK_URL_BASE="$2"; shift 2 ;;
        -h|--help)      sed -n '2,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)              die "argumento desconocido: $1 (usa --help)" ;;
    esac
done

# --- Versiones (fuente de verdad: gradle.properties) ------------------------
prop() { sed -n "s/^$1=//p" gradle.properties | head -n1 | tr -d '[:space:]'; }
ENV_VERSION_NAME="${VERSION_NAME:-}"
ENV_VERSION_CODE="${VERSION_CODE:-}"
VERSION_NAME="${ENV_VERSION_NAME:-$(prop VERSION_NAME)}"
VERSION_CODE="${ENV_VERSION_CODE:-$(prop VERSION_CODE)}"
[[ -n "$VERSION_NAME" && -n "$VERSION_CODE" ]] || die "no pude leer VERSION_NAME/VERSION_CODE de gradle.properties"
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || die "VERSION_CODE debe ser un entero (es: $VERSION_CODE)"

# --- Keystore ---------------------------------------------------------------
if [[ "$GEN_KEYSTORE" == 1 && ! -f keystore.properties ]]; then
    command -v keytool >/dev/null 2>&1 || die "keytool no está en el PATH (necesitas JDK 17)"
    info "Generando release.keystore (RSA 2048, válido 27 años)"
    STORE_PASS="$(openssl rand -hex 16 2>/dev/null || head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    keytool -genkeypair -v \
        -keystore release.keystore \
        -alias lamadriguera \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
        -dname "CN=La Madriguera Release, OU=Mobile, O=La Madriguera, L=Havana, C=CU"
    cat > keystore.properties <<EOF
# Generado por scripts/build-apk.sh el $(date -u +%Y-%m-%dT%H:%M:%SZ). NO COMMITEAR.
storeFile=release.keystore
storePassword=$STORE_PASS
keyAlias=lamadriguera
keyPassword=$STORE_PASS
EOF
    chmod 600 keystore.properties release.keystore
    info "keystore.properties escrito. GUARDA COPIA DE SEGURIDAD del par release.keystore+keystore.properties:"
    info "fuera de este keystore NO se podrá actualizar la app instalada, ni publicar en más dispositivos."
fi

RELEASE_SIGNED=0
if [[ -f keystore.properties || -n "${KEYSTORE_PATH:-}" ]]; then
    RELEASE_SIGNED=1
fi

# --- Compilación --------------------------------------------------------------
GRADLE_ARGS=(assembleRelease)
[[ -n "$ENV_VERSION_NAME" ]] && GRADLE_ARGS+=(-PVERSION_NAME="$ENV_VERSION_NAME")
[[ -n "$ENV_VERSION_CODE" ]] && GRADLE_ARGS+=(-PVERSION_CODE="$ENV_VERSION_CODE")

if [[ "$SKIP_BUILD" == 0 ]]; then
    info "./gradlew ${GRADLE_ARGS[*]}"
    ./gradlew "${GRADLE_ARGS[@]}"
else
    info "Compilación omitida (--skip-build); uso el APK existente."
fi

APK_SRC="app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK_SRC" ]] || die "no encuentro $APK_SRC (¿falló assembleRelease?). Revisa el aviso de firma arriba."

# --- Dist --------------------------------------------------------------------
APK_NAME="LaMadriguera-v${VERSION_NAME}-${VERSION_CODE}.apk"
mkdir -p dist
cp -f "$APK_SRC" "dist/$APK_NAME"
APK_PATH="dist/$APK_NAME"

if command -v sha256sum >/dev/null 2>&1; then
    SHA256="$(sha256sum "$APK_PATH" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
    SHA256="$(shasum -a 256 "$APK_PATH" | awk '{print $1}')"
else
    die "ni sha256sum ni shasum disponibles"
fi
SIZE_BYTES="$(wc -c < "$APK_PATH" | tr -d ' ')"
SIZE_MB="$(awk "BEGIN{printf \"%.1f\", $SIZE_BYTES/1048576}")"
RELEASED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# Verificación de firma con apksigner si el SDK está a mano (informativo).
APKSIGNER="$(find "${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools" -name apksigner -type f 2>/dev/null | sort | tail -n1 || true)"
if [[ -n "$APKSIGNER" ]]; then
    if "$APKSIGNER" verify --print-certs "$APK_PATH" >/dev/null 2>&1; then
        info "APK verificado: firma de release OK ($("$APKSIGNER" verify --print-certs "$APK_PATH" 2>/dev/null | awk -F': ' '/Signer #1 certificate SHA-256 digest/{print "cert " substr($2,1,16)"…"}' || true))"
    else
        info "⚠ apksigner dice que el APK NO está firmado: añade keystore (--gen-keystore) y recompila."
    fi
fi

# --- URL pública y version.json ----------------------------------------------
if [[ -n "$APK_URL_BASE" ]]; then
    APK_URL="${APK_URL_BASE%/}/$APK_NAME"
else
    APK_URL="https://github.com/rmdvcd/LaMadriguera/releases/latest/download/$APK_NAME"
fi

cat > dist/version.json <<EOF
{
  "appName": "La Madriguera",
  "packageName": "com.lamadriguera.app",
  "versionCode": ${VERSION_CODE},
  "versionName": "${VERSION_NAME}",
  "fileName": "${APK_NAME}",
  "url": "${APK_URL}",
  "sha256": "${SHA256}",
  "sizeBytes": ${SIZE_BYTES},
  "minSdk": 26,
  "releasedAt": "${RELEASED_AT}",
  "changelog": "https://github.com/rmdvcd/LaMadriguera/blob/main/docs/CHANGELOG.md"
}
EOF

printf '%s  %s\n' "$SHA256" "$APK_NAME" > dist/sha256sums.txt
cat > dist/release.env <<EOF
APK_NAME="$APK_NAME"
APK_PATH="$APK_PATH"
VERSION_NAME="$VERSION_NAME"
VERSION_CODE="$VERSION_CODE"
SHA256="$SHA256"
SIZE_BYTES="$SIZE_BYTES"
APK_URL="$APK_URL"
RELEASED_AT="$RELEASED_AT"
EOF

# --- Página de descarga --------------------------------------------------------
md_section_to_html() {
    # imprime el cuerpo del primer bloque "## " de docs/CHANGELOG.md como <li>
    awk '
        /^## / { n++; next }
        n > 1  { exit }
        n == 1 && /^- / {
            line = substr($0, 3)
            gsub(/&/, "\\&amp;", line); gsub(/</, "\\&lt;", line); gsub(/>/, "\\&gt;", line)
            gsub(/\*\*/, "", line); gsub(/`/, "", line)
            print "        <li>" line "</li>"
        }
    ' docs/CHANGELOG.md
}

if [[ "$RENDER_PAGE" == 1 ]]; then
    mkdir -p docs/download
    CHANGELOG_HTML="$(md_section_to_html)"
    [[ -n "$CHANGELOG_HTML" ]] || CHANGELOG_HTML='        <li>Notas pendientes: añade la sección de esta versión a docs/CHANGELOG.md</li>'
    awk -v ver="$VERSION_NAME" -v code="$VERSION_CODE" -v url="$APK_URL" \
        -v file="$APK_NAME" -v sha="$SHA256" -v size="$SIZE_MB MB" -v date="$RELEASED_AT" \
        -v changelog="$CHANGELOG_HTML" '
        {
            gsub(/{{VERSION_NAME}}/, ver, $0);       gsub(/{{VERSION_CODE}}/, code, $0);
            gsub(/{{APK_URL}}/, url, $0);             gsub(/{{APK_FILE}}/, file, $0);
            gsub(/{{SHA256}}/, sha, $0);              gsub(/{{APK_SIZE}}/, size, $0);
            gsub(/{{RELEASE_DATE}}/, date, $0);
        }
        /\{\{CHANGELOG_ITEMS\}\}/ { print changelog; next }
        { print }
    ' scripts/templates/download-page.html.tmpl > docs/download/index.html
    cp -f dist/version.json docs/download/version.json
    info "Página de descarga renderizada en docs/download/index.html"
fi

# --- Resumen -------------------------------------------------------------------
echo
echo "════════════════════════════════════════════════════════"
echo " La Madriguera v${VERSION_NAME} (build ${VERSION_CODE})"
echo " APK     : ${APK_PATH}"
echo " Tamaño  : ${SIZE_MB} MB"
echo " SHA-256 : ${SHA256}"
echo " URL     : ${APK_URL}"
echo " Firmado : $( [[ "$RELEASE_SIGNED" == 1 ]] && echo 'sí (keystore configurado)' || echo 'NO ⚠ — usa scripts/build-apk.sh --gen-keystore' )"
echo "════════════════════════════════════════════════════════"
echo
echo "Siguientes pasos:"
echo "  1) Publicar el release en GitHub (la URL anterior lo resuelve):"
echo "       gh release create v${VERSION_NAME} ${APK_PATH} dist/version.json dist/sha256sums.txt -t \"v${VERSION_NAME}\""
echo "  2) Anunciar en el canal de Telegram:"
echo "       scripts/telegram-announce.sh"
echo "  3) Verificar la web de descarga (puede servirse desde GitHub Pages/docs):"
echo "       docs/download/index.html  (autores: commitéala con el release)"
