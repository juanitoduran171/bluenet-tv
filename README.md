# BLUEnet TV

App para Android TV (y teléfonos) que se conecta al servicio de TV de BLUEnet: inicio de sesión, TV en vivo y películas.

- **Reproductor interno:** ExoPlayer (AndroidX Media3, Apache 2.0) con decodificador de audio FFmpeg
  (`org.jellyfin.media3:media3-ffmpeg-decoder`, LGPL), para AC3/E-AC3/MP2/DTS en equipos sin decodificador propio.
- **Servidor:** fijo en `app/src/main/java/ec/net/blue/tv/Config.java`; el cliente no lo ve.
- **Modo técnico (oculto):** en la pantalla principal, pulsa OK 7 veces seguidas sobre el logo.
  Muestra "Diagnóstico" (códecs de cada canal) y la opción de abrir canales en VLC.

## Compilar

- **GitHub Actions:** cada push a `main` compila el APK firmado. Se descarga desde la pestaña *Actions*
  (artefacto `BLUEnet-TV`) o desde la rama `builds`.
- **Local / Android Studio:** `./gradlew assembleRelease` → `app/build/outputs/apk/release/`.

## Firma

`app/bluenet.keystore` (alias `bluenet`). Las actualizaciones deben firmarse con esta llave para instalarse
encima de la versión anterior. Mantén este repositorio **privado**, o mueve la llave a *Secrets*
(`BLUENET_KS_FILE`, `BLUENET_KS_PASS`).

## Pruebas sin dispositivo (`tools/`)

Servidor Xtream simulado y analizador de streams probados en JVM; ver comentarios en cada archivo.
