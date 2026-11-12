<div align="center">

<img src="../../assets/brand/banner.png" width="100%" alt="Camora: Vos caméras et votre carte de capture, enregistrées et sur tous les écrans.">

  <a href="../../LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-B8A4FF?style=flat-square"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
  <a href="https://github.com/DrummingBird1/camora/actions"><img alt="Build" src="https://github.com/DrummingBird1/camora/actions/workflows/android.yml/badge.svg"></a>

<a href="../../README.md">English</a> · <a href="../../docs/readme/README.he.md">עברית</a> · <a href="../../docs/readme/README.ar.md">العربية</a> · <a href="../../docs/readme/README.ru.md">Русский</a> · <a href="../../docs/readme/README.es.md">Español</a> · <b>Français</b> · <a href="../../docs/readme/README.de.md">Deutsch</a> · <a href="../../docs/readme/README.pt.md">Português</a>

</div>

Camora transforme un téléphone Android en petit centre vidéo pour vos caméras : regardez vos caméras IP et cartes de capture HDMI, enregistrez-les, recevez des alertes de mouvement et diffusez-les sur n’importe quel écran de la maison. Sans compte, sans pub, sans pistage.

<p align="center">
  <img src="../../assets/store/screenshots/en-1-cameras.png" width="23%" alt="">
  <img src="../../assets/store/screenshots/en-2-stream.png" width="23%" alt="">
  <img src="../../assets/store/screenshots/en-3-recordings.png" width="23%" alt="">
  <img src="../../assets/store/screenshots/en-4-settings.png" width="23%" alt="">
</p>

## Fonctionnalités

| | | |
|---|---|---|
| 📷 | **Caméras IP** | RTSP en TCP ou UDP, découverte ONVIF, reconnexion automatique. Mots de passe chiffrés sur le téléphone. |
| 🎮 | **Cartes de capture** | Cartes de capture HDMI USB (UVC) avec le son, choix du mode et mesure des images par seconde. |
| ⏺️ | **Enregistrement** | MP4 avec le son, enregistrement au mouvement, nettoyage par âge ou espace, export vers la galerie. |
| 🔔 | **Alertes de mouvement** | Trois sensibilités, notifications, surveillance en arrière-plan et écran éteint. |
| 📺 | **Sur tous les écrans** | Lecteur navigateur à faible latence, TS en direct pour TV DLNA, Chromecast, HLS pour VLC. |
| ✨ | **Qualité** | Jusqu’à la 4K, niveaux de qualité, H.264 ou H.265 par caméra. |
| 🔒 | **Vie privée** | Tout reste sur votre réseau. HTTPS en option, accès distant seulement si vous l’activez. |
| 🌍 | **8 langues** | Anglais, hébreu, arabe, russe, espagnol, français, allemand et portugais. |

## 📥 Obtenir Camora

Bientôt sur Google Play. En attendant, vous pouvez la compiler depuis les sources (ci-dessous).

**Site web:** https://drummingbird1.github.io/camora/

## 📺 Regarder sur d’autres écrans

| Où | Comment |
|---|---|
| Navigateur d’ordinateur ou de téléphone | Latence la plus faible (environ une demi-seconde) |
| TV Samsung et autres DLNA | TS en direct, quelques secondes de latence côté TV |
| Chromecast / Google TV | HLS, quelques secondes de latence côté récepteur |
| VLC, OBS et autres lecteurs | Adresse HLS ou TS en direct |

## 🔧 Prérequis

Android 8.0 ou plus récent. Une carte de capture nécessite l’USB OTG. Pour les longs enregistrements, utilisez un hub USB-C avec charge.

## 🛠️ Compiler depuis les sources

Il faut JDK 17+ et le SDK Android 36.

```bash
git clone https://github.com/DrummingBird1/camora.git
cd camora
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 📚 Documentation

- [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md): Comment la vidéo va de la source aux enregistrements et aux spectateurs
- [docs/TESTING.md](../../docs/TESTING.md): Tester sans matériel : fausses caméras RTSP et outils de mesure
- [docs/RELEASE.md](../../docs/RELEASE.md): Signature et versions de publication
- [.github/CONTRIBUTING.md](../../.github/CONTRIBUTING.md): Comment contribuer

## 🔒 Vie privée

Camora n’a ni compte, ni publicité, ni statistiques, et n’envoie rien à son développeur, sauf si vous choisissez « Envoyer un avis ». [Politique de confidentialité](https://drummingbird1.github.io/camora/privacy.html)

## 💜 Soutenir le projet

Camora est gratuite et open source. Si elle vous est utile, vous pouvez soutenir son développement :

<p align="center">
  <a href="https://github.com/sponsors/DrummingBird1"><img alt="GitHub Sponsors" src="https://img.shields.io/badge/GitHub%20Sponsors-EA4AAA?style=for-the-badge&logo=githubsponsors&logoColor=white"></a>
  <a href="https://www.patreon.com/c/Solvaris"><img alt="Patreon" src="https://img.shields.io/badge/Patreon-F96854?style=for-the-badge&logo=patreon&logoColor=white"></a>
  <a href="https://ko-fi.com/solvaris1"><img alt="Ko-fi" src="https://img.shields.io/badge/Ko--fi-FF5E5B?style=for-the-badge&logo=kofi&logoColor=white"></a>
  <a href="https://buymeacoffee.com/solvaris"><img alt="Buy Me a Coffee" src="https://img.shields.io/badge/Buy%20Me%20a%20Coffee-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=black"></a>
</p>

## 🤝 Contribuer

Rapports de bugs, idées et pull requests sont les bienvenus. Lisez d’abord CONTRIBUTING.md.

## 📄 Licence

Apache License 2.0. Les composants tiers et leurs licences sont listés dans l’appli (Réglages, Outils, Licences open source).
