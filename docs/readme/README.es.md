<div align="center">

<img src="../../assets/brand/banner.png" width="100%" alt="Camora: Tus cámaras y tu capturadora, grabadas y en cualquier pantalla.">

  <a href="../../LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-B8A4FF?style=flat-square"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
  <a href="https://github.com/DrummingBird1/camora/actions"><img alt="Build" src="https://github.com/DrummingBird1/camora/actions/workflows/android.yml/badge.svg"></a>

<a href="../../README.md">English</a> · <a href="../../docs/readme/README.he.md">עברית</a> · <a href="../../docs/readme/README.ar.md">العربية</a> · <a href="../../docs/readme/README.ru.md">Русский</a> · <b>Español</b> · <a href="../../docs/readme/README.fr.md">Français</a> · <a href="../../docs/readme/README.de.md">Deutsch</a> · <a href="../../docs/readme/README.pt.md">Português</a>

</div>

Camora convierte un teléfono Android en un pequeño centro de vídeo para tus cámaras: mira cámaras IP y capturadoras HDMI, grábalas, recibe avisos de movimiento y llévalas a cualquier pantalla de casa. Sin cuenta, sin anuncios, sin rastreo.

<p align="center">
  <img src="../../assets/store/screenshots/en-1-cameras.png" width="23%" alt="">
  <img src="../../assets/store/screenshots/en-2-stream.png" width="23%" alt="">
  <img src="../../assets/store/screenshots/en-3-recordings.png" width="23%" alt="">
  <img src="../../assets/store/screenshots/en-4-settings.png" width="23%" alt="">
</p>

## Funciones

| | | |
|---|---|---|
| 📷 | **Cámaras IP** | RTSP por TCP o UDP, búsqueda ONVIF, reconexión automática. Las contraseñas se cifran en el teléfono. |
| 🎮 | **Capturadoras** | Capturadoras HDMI por USB (UVC) con sonido, selección de modo y medición de fotogramas. |
| ⏺️ | **Grabación** | MP4 con sonido, grabar con movimiento, limpieza por antigüedad o espacio, guardar en la galería. |
| 🔔 | **Movimiento** | Tres sensibilidades, notificaciones, sigue vigilando en segundo plano y con la pantalla apagada. |
| 📺 | **En cualquier pantalla** | Navegador con poco retardo, TS en vivo para TVs DLNA, Chromecast, HLS para VLC. |
| ✨ | **Calidad** | Hasta 4K, niveles de calidad, H.264 o H.265 por cámara. |
| 🔒 | **Privacidad** | Todo queda en tu red. HTTPS opcional y acceso remoto solo si lo activas. |
| 🌍 | **8 idiomas** | Inglés, hebreo, árabe, ruso, español, francés, alemán y portugués. |

## 📥 Descargar

Pronto en Google Play. Mientras tanto puedes compilarla desde el código (abajo).

**Web:** https://drummingbird1.github.io/camora/

## 📺 Ver en otras pantallas

| Dónde | Cómo |
|---|---|
| Navegador de ordenador o móvil | El menor retardo (medio segundo aprox.) |
| TVs Samsung y otras DLNA | TS en vivo, unos segundos de retardo de la TV |
| Chromecast / Google TV | HLS, unos segundos de retardo del receptor |
| VLC, OBS y otros reproductores | Dirección HLS o TS en vivo |

## 🔧 Requisitos

Android 8.0 o posterior. La capturadora necesita USB OTG. Para grabaciones largas usa un hub USB-C con carga.

## 🛠️ Compilar desde el código

Necesitas JDK 17+ y Android SDK 36.

```bash
git clone https://github.com/DrummingBird1/camora.git
cd camora
./gradlew assembleDebug
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

## 📚 Documentación

- [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md): Cómo fluye el vídeo de la fuente a grabaciones y espectadores
- [docs/TESTING.md](../../docs/TESTING.md): Pruebas sin hardware: cámaras RTSP falsas y herramientas
- [docs/RELEASE.md](../../docs/RELEASE.md): Firma y versiones de publicación
- [.github/CONTRIBUTING.md](../../.github/CONTRIBUTING.md): Cómo contribuir

## 🔒 Privacidad

Camora no tiene cuenta, anuncios ni analítica, y no envía nada a su desarrollador salvo que elijas «Enviar comentarios». [Política de privacidad](https://drummingbird1.github.io/camora/privacy.html)

## 💜 Apoya el proyecto

Camora es gratuita y de código abierto. Si te resulta útil, puedes apoyar su desarrollo:

<p align="center">
  <a href="https://github.com/sponsors/DrummingBird1"><img alt="GitHub Sponsors" src="https://img.shields.io/badge/GitHub%20Sponsors-EA4AAA?style=for-the-badge&logo=githubsponsors&logoColor=white"></a>
  <a href="https://www.patreon.com/c/Solvaris"><img alt="Patreon" src="https://img.shields.io/badge/Patreon-F96854?style=for-the-badge&logo=patreon&logoColor=white"></a>
  <a href="https://ko-fi.com/solvaris1"><img alt="Ko-fi" src="https://img.shields.io/badge/Ko--fi-FF5E5B?style=for-the-badge&logo=kofi&logoColor=white"></a>
  <a href="https://buymeacoffee.com/solvaris"><img alt="Buy Me a Coffee" src="https://img.shields.io/badge/Buy%20Me%20a%20Coffee-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=black"></a>
</p>

## 🤝 Contribuir

Informes de errores, ideas y pull requests son bienvenidos. Lee CONTRIBUTING.md primero.

## 📄 Licencia

Apache License 2.0. Los componentes de terceros y sus licencias aparecen en la app (Ajustes, Herramientas, Licencias de código abierto).
