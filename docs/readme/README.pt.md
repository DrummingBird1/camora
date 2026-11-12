<div align="center">

<img src="../../assets/brand/banner.png" width="100%" alt="Camora: Suas câmeras e sua placa de captura, gravadas e em qualquer tela.">

  <a href="../../LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-B8A4FF?style=flat-square"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
  <a href="https://github.com/DrummingBird1/camora/actions"><img alt="Build" src="https://github.com/DrummingBird1/camora/actions/workflows/android.yml/badge.svg"></a>

<a href="../../README.md">English</a> · <a href="../../docs/readme/README.he.md">עברית</a> · <a href="../../docs/readme/README.ar.md">العربية</a> · <a href="../../docs/readme/README.ru.md">Русский</a> · <a href="../../docs/readme/README.es.md">Español</a> · <a href="../../docs/readme/README.fr.md">Français</a> · <a href="../../docs/readme/README.de.md">Deutsch</a> · <b>Português</b>

</div>

Camora transforma um celular Android em uma pequena central de vídeo para suas câmeras: assista câmeras IP e placas de captura HDMI, grave, receba alertas de movimento e leve tudo para qualquer tela da casa. Sem conta, sem anúncios, sem rastreamento.

<p align="center">
  <img src="../../assets/store/screenshots/en-1-cameras.png" width="23%" alt="">
  <img src="../../assets/store/screenshots/en-2-stream.png" width="23%" alt="">
  <img src="../../assets/store/screenshots/en-3-recordings.png" width="23%" alt="">
  <img src="../../assets/store/screenshots/en-4-settings.png" width="23%" alt="">
</p>

## Recursos

| | | |
|---|---|---|
| 📷 | **Câmeras IP** | RTSP por TCP ou UDP, busca ONVIF, reconexão automática. Senhas criptografadas no celular. |
| 🎮 | **Placas de captura** | Placas de captura HDMI USB (UVC) com som, escolha de modo e medição de quadros. |
| ⏺️ | **Gravação** | MP4 com som, gravar com movimento, limpeza por idade ou espaço, salvar na galeria. |
| 🔔 | **Alertas de movimento** | Três sensibilidades, notificações, continua vigiando em segundo plano e com a tela apagada. |
| 📺 | **Em qualquer tela** | Navegador com baixo atraso, TS ao vivo para TVs DLNA, Chromecast, HLS para VLC. |
| ✨ | **Qualidade** | Até 4K, níveis de qualidade, H.264 ou H.265 por câmera. |
| 🔒 | **Privacidade** | Tudo fica na sua rede. HTTPS opcional e acesso remoto só se você ativar. |
| 🌍 | **8 idiomas** | Inglês, hebraico, árabe, russo, espanhol, francês, alemão e português. |

## 📥 Baixar

Em breve no Google Play. Até lá você pode compilar a partir do código (abaixo).

**Site:** https://drummingbird1.github.io/camora/

## 📺 Assistir em outras telas

| Onde | Como |
|---|---|
| Navegador no computador ou celular | Menor atraso (cerca de meio segundo) |
| TVs Samsung e outras DLNA | TS ao vivo, alguns segundos de atraso da TV |
| Chromecast / Google TV | HLS, alguns segundos de atraso do receptor |
| VLC, OBS e outros players | Endereço HLS ou TS ao vivo |

## 🔧 Requisitos

Android 8.0 ou mais recente. A placa de captura precisa de USB OTG. Para gravações longas use um hub USB-C com carregamento.

## 🛠️ Compilar a partir do código

Você precisa do JDK 17+ e do Android SDK 36.

```bash
git clone https://github.com/DrummingBird1/camora.git
cd camora
./gradlew assembleDebug
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

## 📚 Documentação

- [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md): Como o vídeo vai da fonte às gravações e espectadores
- [docs/TESTING.md](../../docs/TESTING.md): Testes sem hardware: câmeras RTSP falsas e ferramentas
- [docs/RELEASE.md](../../docs/RELEASE.md): Assinatura e versões de publicação
- [.github/CONTRIBUTING.md](../../.github/CONTRIBUTING.md): Como contribuir

## 🔒 Privacidade

Camora não tem conta, anúncios nem análises, e não envia nada ao desenvolvedor, a menos que você escolha “Enviar feedback”. [Política de privacidade](https://drummingbird1.github.io/camora/privacy.html)

## 💜 Apoie o projeto

Camora é gratuito e de código aberto. Se for útil para você, apoie o desenvolvimento:

<p align="center">
  <a href="https://github.com/sponsors/DrummingBird1"><img alt="GitHub Sponsors" src="https://img.shields.io/badge/GitHub%20Sponsors-EA4AAA?style=for-the-badge&logo=githubsponsors&logoColor=white"></a>
  <a href="https://www.patreon.com/c/Solvaris"><img alt="Patreon" src="https://img.shields.io/badge/Patreon-F96854?style=for-the-badge&logo=patreon&logoColor=white"></a>
  <a href="https://ko-fi.com/solvaris1"><img alt="Ko-fi" src="https://img.shields.io/badge/Ko--fi-FF5E5B?style=for-the-badge&logo=kofi&logoColor=white"></a>
  <a href="https://buymeacoffee.com/solvaris"><img alt="Buy Me a Coffee" src="https://img.shields.io/badge/Buy%20Me%20a%20Coffee-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=black"></a>
</p>

## 🤝 Contribuir

Relatos de bugs, ideias e pull requests são bem-vindos. Leia o CONTRIBUTING.md primeiro.

## 📄 Licença

Apache License 2.0. Componentes de terceiros e suas licenças estão no app (Configurações, Ferramentas, Licenças de código aberto).
