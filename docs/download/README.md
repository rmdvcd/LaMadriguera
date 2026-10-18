# Página de descarga (generada)

Este directorio es **salida**, no fuente: `scripts/build-apk.sh` genera aquí

- `index.html` — la página de descarga renderizada desde
  `scripts/templates/download-page.html.tmpl` (enlace al APK, SHA-256 copiable,
  instrucciones de «orígenes desconocidos» y changelog de la versión), y
- `version.json` — copia del `dist/version.json` del release (versionCode,
  versionName, url, sha256…), para que cualquier cliente compruebe si hay una
  versión nueva consultando solo esta carpeta.

En cada release se comitea aquí (o se publica en GitHub Pages desde la rama
`gh-pages`, que es lo que hace el workflow `android-release.yml` cuando la
variable `PUBLISH_DOWNLOAD_PAGE` está a `true`).

No edites `index.html` a mano: cambia la plantilla o el
[`docs/CHANGELOG.md`](../CHANGELOG.md) y vuelve a ejecutar el script.
