<div align="center">

<img src="../../assets/brand/banner.png" width="100%" alt="Camora: Ваши камеры и карта захвата: запись и любой экран.">

  <a href="../../LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-B8A4FF?style=flat-square"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
  <a href="https://github.com/DrummingBird1/camora/actions"><img alt="Build" src="https://github.com/DrummingBird1/camora/actions/workflows/android.yml/badge.svg"></a>

<a href="../../README.md">English</a> · <a href="../../docs/readme/README.he.md">עברית</a> · <a href="../../docs/readme/README.ar.md">العربية</a> · <b>Русский</b> · <a href="../../docs/readme/README.es.md">Español</a> · <a href="../../docs/readme/README.fr.md">Français</a> · <a href="../../docs/readme/README.de.md">Deutsch</a> · <a href="../../docs/readme/README.pt.md">Português</a>

</div>

Camora превращает телефон на Android в небольшой видеоцентр для ваших камер: смотрите IP-камеры и HDMI-карты захвата, записывайте их, получайте уведомления о движении и выводите на любой экран дома. Без аккаунта, рекламы и слежки.

<p align="center">
  <img src="../../assets/store/screenshots/en-1-cameras.png" width="23%" alt="">
  <img src="../../assets/store/screenshots/en-2-stream.png" width="23%" alt="">
  <img src="../../assets/store/screenshots/en-3-recordings.png" width="23%" alt="">
  <img src="../../assets/store/screenshots/en-4-settings.png" width="23%" alt="">
</p>

## Возможности

| | | |
|---|---|---|
| 📷 | **IP-камеры** | RTSP по TCP или UDP, поиск ONVIF, автоматическое переподключение. Пароли шифруются на телефоне. |
| 🎮 | **Карты захвата** | USB HDMI-карты захвата (UVC) со звуком, выбором режима и измерением частоты кадров. |
| ⏺️ | **Запись** | MP4 со звуком, запись по движению, очистка по возрасту или объёму, сохранение в галерею. |
| 🔔 | **Движение** | Три уровня чувствительности, уведомления, работа в фоне и с выключенным экраном. |
| 📺 | **На любой экран** | Браузер с малой задержкой, live TS для DLNA-телевизоров, Chromecast, HLS для VLC. |
| ✨ | **Качество** | До 4K, пресеты качества, H.264 или H.265 для каждой камеры. |
| 🔒 | **Приватность** | Всё остаётся в вашей сети. HTTPS по желанию, удалённый доступ только если включить. |
| 🌍 | **8 языков** | Английский, иврит, арабский, русский, испанский, французский, немецкий, португальский. |

## 📥 Установка

Скоро в Google Play. А пока можно собрать из исходников (ниже).

**Сайт:** https://drummingbird1.github.io/camora/

## 📺 Просмотр на других экранах

| Где | Как |
|---|---|
| Браузер на компьютере или телефоне | Минимальная задержка (около полсекунды) |
| Samsung и другие DLNA-телевизоры | Live TS, несколько секунд задержки на стороне ТВ |
| Chromecast / Google TV | HLS, несколько секунд задержки на стороне приёмника |
| VLC, OBS и другие плееры | Адрес HLS или live TS |

## 🔧 Требования

Android 8.0 или новее. Для карты захвата нужен USB OTG. Для долгой записи используйте USB-C хаб с зарядкой.

## 🛠️ Сборка из исходников

Нужны JDK 17+ и Android SDK 36.

```bash
git clone https://github.com/DrummingBird1/camora.git
cd camora
./gradlew assembleDebug
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

## 📚 Документация

- [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md): Как видео идёт от источника к записям и зрителям
- [docs/TESTING.md](../../docs/TESTING.md): Тестирование без железа: фейковые RTSP-камеры и инструменты
- [docs/RELEASE.md](../../docs/RELEASE.md): Подпись и релизные сборки
- [.github/CONTRIBUTING.md](../../.github/CONTRIBUTING.md): Как помочь проекту

## 🔒 Приватность

В Camora нет аккаунта, рекламы и аналитики, и она ничего не отправляет разработчику, если вы сами не выберете «Отправить отзыв». [Политика конфиденциальности](https://drummingbird1.github.io/camora/privacy.html)

## 💜 Поддержать проект

Camora бесплатна и с открытым кодом. Если она вам полезна, можно поддержать разработку:

<p align="center">
  <a href="https://github.com/sponsors/DrummingBird1"><img alt="GitHub Sponsors" src="https://img.shields.io/badge/GitHub%20Sponsors-EA4AAA?style=for-the-badge&logo=githubsponsors&logoColor=white"></a>
  <a href="https://www.patreon.com/c/Solvaris"><img alt="Patreon" src="https://img.shields.io/badge/Patreon-F96854?style=for-the-badge&logo=patreon&logoColor=white"></a>
  <a href="https://ko-fi.com/solvaris1"><img alt="Ko-fi" src="https://img.shields.io/badge/Ko--fi-FF5E5B?style=for-the-badge&logo=kofi&logoColor=white"></a>
  <a href="https://buymeacoffee.com/solvaris"><img alt="Buy Me a Coffee" src="https://img.shields.io/badge/Buy%20Me%20a%20Coffee-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=black"></a>
</p>

## 🤝 Участие

Сообщения об ошибках, идеи и pull request приветствуются. Сначала прочитайте CONTRIBUTING.md.

## 📄 Лицензия

Apache License 2.0. Сторонние компоненты и их лицензии перечислены в приложении (Настройки, Инструменты, Лицензии открытого ПО).
