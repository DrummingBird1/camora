<div align="center">

<img src="../../assets/brand/banner.png" width="100%" alt="Camora: كاميراتك وبطاقة الالتقاط، مسجلة وعلى كل شاشة.">

  <a href="../../LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-B8A4FF?style=flat-square"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
  <a href="https://github.com/DrummingBird1/camora/actions"><img alt="Build" src="https://github.com/DrummingBird1/camora/actions/workflows/android.yml/badge.svg"></a>

<a href="../../README.md">English</a> · <a href="../../docs/readme/README.he.md">עברית</a> · <b>العربية</b> · <a href="../../docs/readme/README.ru.md">Русский</a> · <a href="../../docs/readme/README.es.md">Español</a> · <a href="../../docs/readme/README.fr.md">Français</a> · <a href="../../docs/readme/README.de.md">Deutsch</a> · <a href="../../docs/readme/README.pt.md">Português</a>

</div>

<div dir="rtl">

يحوّل Camora هاتف أندرويد إلى مركز فيديو صغير لكاميراتك: شاهد كاميرات IP وبطاقات التقاط HDMI، وسجّلها، واحصل على تنبيهات الحركة، واعرضها على أي شاشة في المنزل. بلا حساب ولا إعلانات ولا تتبع.

<p align="center">
  <img src="../../assets/store/screenshots/he-1-cameras.png" width="23%" alt="">
  <img src="../../assets/store/screenshots/he-2-stream.png" width="23%" alt="">
  <img src="../../assets/store/screenshots/he-3-recordings.png" width="23%" alt="">
  <img src="../../assets/store/screenshots/he-4-settings.png" width="23%" alt="">
</p>

## الميزات

| | | |
|---|---|---|
| 📷 | **كاميرات IP** | RTSP عبر TCP أو UDP، اكتشاف ONVIF، إعادة اتصال تلقائية. كلمات المرور مشفرة على الهاتف. |
| 🎮 | **بطاقات الالتقاط** | بطاقات التقاط HDMI عبر USB ‏(UVC) مع الصوت واختيار الوضع وقياس معدل الإطارات. |
| ⏺️ | **التسجيل** | MP4 مع الصوت، تسجيل عند الحركة، حذف حسب العمر أو المساحة، حفظ في المعرض. |
| 🔔 | **تنبيهات الحركة** | ثلاث درجات حساسية وإشعارات، ويستمر في المراقبة في الخلفية ومع إطفاء الشاشة. |
| 📺 | **على كل شاشة** | مشغّل متصفح بتأخير منخفض، ‏TS مباشر لتلفزيونات DLNA، ‏Chromecast، و-HLS لـ VLC. |
| ✨ | **الجودة** | حتى 4K، إعدادات جودة مسبقة، ‏H.264 أو H.265 لكل كاميرا. |
| 🔒 | **الخصوصية** | كل شيء يبقى على شبكتك. ‏HTTPS اختياري، والوصول عن بُعد فقط إن فعّلته. |
| 🌍 | **8 لغات** | الإنجليزية والعبرية والعربية والروسية والإسبانية والفرنسية والألمانية والبرتغالية. |

## 📥 الحصول على Camora

قريبًا على Google Play. حتى ذلك الحين يمكنك بناؤه من المصدر (أدناه).

**الموقع:** https://drummingbird1.github.io/camora/

## 📺 المشاهدة على شاشات أخرى

| أين | كيف |
|---|---|
| متصفح الكمبيوتر أو الهاتف | أقل تأخير (نحو نصف ثانية) |
| تلفزيونات Samsung وغيرها (DLNA) | TS مباشر، تأخير بضع ثوانٍ من التلفزيون |
| Chromecast / Google TV | HLS، تأخير بضع ثوانٍ من جهاز الاستقبال |
| VLC و-OBS ومشغلات أخرى | عنوان HLS أو TS مباشر |

## 🔧 المتطلبات

أندرويد 8.0 أو أحدث. تحتاج بطاقة الالتقاط إلى USB OTG. للتسجيلات الطويلة استخدم موزع USB-C مع الشحن.

## 🛠️ البناء من المصدر

تحتاج JDK 17 أو أحدث و-Android SDK 36.

</div>

```bash
git clone https://github.com/DrummingBird1/camora.git
cd camora
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

<div dir="rtl">

## 📚 التوثيق

- [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md): كيف ينتقل الفيديو من المصدر إلى التسجيلات والمشاهدين
- [docs/TESTING.md](../../docs/TESTING.md): الاختبار بلا أجهزة: كاميرات RTSP وهمية وأدوات قياس
- [docs/RELEASE.md](../../docs/RELEASE.md): التوقيع وإصدارات النشر
- [.github/CONTRIBUTING.md](../../.github/CONTRIBUTING.md): كيفية المساهمة

## 🔒 الخصوصية

لا حساب ولا إعلانات ولا تحليلات في Camora، ولا يرسل شيئًا إلى المطوّر إلا إذا اخترت "إرسال ملاحظات" بنفسك. [اقرأ سياسة الخصوصية](https://drummingbird1.github.io/camora/privacy.html)

## 💜 ادعم المشروع

Camora مجاني ومفتوح المصدر. إن كان مفيدًا لك يمكنك دعم تطويره:

<p align="center">
  <a href="https://github.com/sponsors/DrummingBird1"><img alt="GitHub Sponsors" src="https://img.shields.io/badge/GitHub%20Sponsors-EA4AAA?style=for-the-badge&logo=githubsponsors&logoColor=white"></a>
  <a href="https://www.patreon.com/c/Solvaris"><img alt="Patreon" src="https://img.shields.io/badge/Patreon-F96854?style=for-the-badge&logo=patreon&logoColor=white"></a>
  <a href="https://ko-fi.com/solvaris1"><img alt="Ko-fi" src="https://img.shields.io/badge/Ko--fi-FF5E5B?style=for-the-badge&logo=kofi&logoColor=white"></a>
  <a href="https://buymeacoffee.com/solvaris"><img alt="Buy Me a Coffee" src="https://img.shields.io/badge/Buy%20Me%20a%20Coffee-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=black"></a>
</p>

## 🤝 المساهمة

نرحب بتقارير الأخطاء والأفكار وطلبات الدمج. اقرأ CONTRIBUTING.md أولًا.

## 📄 الترخيص

Apache License 2.0. مكونات الطرف الثالث وتراخيصها مذكورة في التطبيق (الإعدادات، الأدوات، تراخيص المصادر المفتوحة).

</div>
