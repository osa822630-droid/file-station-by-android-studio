# File Station

تطبيق أندرويد لإدارة ملفات الوسائط، وفحص روابط التنزيل، وتشغيل الملفات وتحويلها. واجهة التطبيق باللغة العربية ومبنية باستخدام Kotlin وJetpack Compose.

> **حالة المشروع:** قيد التطوير. يعتمد جلب بيانات بعض المواقع على خدمات خارجية، وميزات تنزيل المواقع وتحويل/خفض جودة الوسائط ما زالت بحاجة إلى اختبار عملي على أجهزة حقيقية. لا تعتمد على الملفات الناتجة قبل التحقق من محتواها، ولا يُنصح حاليًا باعتبار التطبيق إصدارًا إنتاجيًا.

## الوظائف الموجودة في المشروع

- فحص الروابط المباشرة وبعض روابط الفيديو وقوائم التشغيل.
- البحث في مصادر ويب خارجية وعرض نتائج قابلة للتمرير إلى شاشة التنزيل.
- اختيار ملف محلي من الجهاز لمعاينته أو بدء تحويله.
- مشغل مصغر مع التحكم بالتشغيل والإيقاف والبحث في المقطع والسرعة والتكرار.
- مكتبة داخل التطبيق للملفات التي أُضيفت أو حُوّلت، مع المشاركة وفتح الملف بتطبيق آخر.
- تحويل صوتي إلى MP3 بمرمّز LAME Java، أو AAC داخل M4A، أو PCM WAV. في M4A تُفحص قدرات AAC المتاحة ويُختار معدل العينة/القنوات/البت المدعوم؛ ويُعرض المعدل الفعلي الناتج.
- واجهة عاملة لاختيار حد دقة الفيديو (1080p، 720p، 480p، 360p، 240p) والسرعة (0.5x–2x)، مع إعادة ترميز حقيقية عبر Media3 Transformer ومشفرات Android؛ يحافظ التحويل على تناسب الأبعاد والصوت والصورة، ولا يرفع دقة المصدر الأصغر من الهدف.
- يدعم مسار إعادة الترميز إخراج MP4، وإخراج MKV عبر Kotmpeg على Android API 26+ بعد إعادة الترميز. أما WebM فيظل خيارًا في نموذج البيانات ومسار إعادة التعبئة القديم فقط؛ لا يدعم هذا الإصدار إعادة ترميز WebM لتغيير الدقة/السرعة. يفشل أي طلب تحويل غير مدعوم بأمان بدل الادعاء بتطبيق إعداد لم يُنفذ. تظل صيغ الصوت وMP3 محفوظة.

ترخيص مكتبة jump3r/LAME المستخدمة لترميز MP3 موجود في `app/src/main/assets/licenses/jump3r-LICENSE.txt` (LGPL-2.1-or-later)، وترخيص Kotmpeg موجود في `app/src/main/assets/licenses/kotmpeg-MIT-LICENSE.txt` (MIT). توافر الوظائف الأخرى ونتائجها الفعلية يتوقف على صيغة الملف والجهاز ومصدر الرابط. تنزيل المحتوى من خدمة خارجية قد يخضع لشروطها؛ استخدم مصادر تملك حق الوصول إليها والتنزيل منها.

## المتطلبات

- Android Studio بإصدار يدعم إعدادات Android Gradle Plugin المستخدمة في المشروع.
- JDK 17 أو أحدث لتشغيل إصدار Gradle المحدد في Wrapper.
- Android SDK وSDK Platform المطابقان لإعداد `compileSdk` في `app/build.gradle.kts`، مع قبول تراخيص SDK المطلوبة.
- اتصال بالإنترنت عند أول بناء لتنزيل Gradle واعتماديات المشروع (ومنها Kotmpeg عبر JitPack)، وعند استخدام ميزات جلب الروابط والبحث.

إعداد التطبيق الحالي يستهدف Android API 36 ويتيح التثبيت من API 24 فما فوق.

## فتح المشروع وبناؤه

```bash
git clone https://github.com/osa822630-droid/file-station-by-android-studio.git
cd file-station-by-android-studio

# بناء نسخة Debug
./gradlew :app:assembleDebug
```

على Windows استخدم:

```bat
gradlew.bat :app:assembleDebug
```

ينتج ملف APK عادةً في:

```text
app/build/outputs/apk/debug/app-debug.apk
```

يمكن فتح مجلد المشروع مباشرةً في Android Studio؛ سيستخدم Android Studio ملفات Gradle Wrapper الموجودة في المستودع.

> **ملاحظة توقيع:** إعداد Debug الحالي يشير إلى ملف `debug.keystore` في جذر المشروع، لكنه غير مضمن في Git؛ لذلك يحتاج بناء APK في هذا الإعداد إلى توفيره محليًا أو تعديل الإعداد ليستعمل Debug keystore الذي يديره Android Gradle Plugin. بناء Release يحتاج keystore وكلمات مرور صحيحة عبر متغيرات البيئة (`KEYSTORE_PATH` و`STORE_PASSWORD` و`KEY_PASSWORD`). لا ترفع أي keystore أو كلمات مرور إلى المستودع.

## الفحص والاختبارات

```bash
# اختبارات JVM وRobolectric
./gradlew :app:testDebugUnitTest

# فحص Android Lint
./gradlew :app:lintDebug

# اختبارات الأجهزة — تتطلب محاكيًا أو جهاز Android متصلًا
./gradlew :app:connectedDebugAndroidTest
```

لتشغيل جميع فحوصات الوحدة وLint معًا:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

تغطي اختبارات JVM/Robolectric الصوت وWAV وWSOLA وMP3 ومعدل البت وresampling وسياسة الدقة وفشل التنزيل، إضافةً إلى النشر الذري للصوت وتخطيط أسماء التنزيلات والتعارض لكل عنصر. في 18 أكتوبر 2026 نجح `:app:testDebugUnitTest` مع **36 اختبارًا، 0 فشل، 0 أخطاء، 0 تخطٍ**، ونجح `:app:lintDebug` (**0 أخطاء، 57 تحذيرًا**) و`:app:compileDebugAndroidTestKotlin` و`:app:assembleDebug` و`:app:assembleDebugAndroidTest`. أُعيد تفعيل خيارات دقة/سرعة الفيديو وتوصيلها بـMedia3 Transformer لإعادة ترميز MP4/MKV؛ تفاصيل التنفيذ وحدوده في تقرير التدقيق. APK التطبيق الناتج نحو 26 MB وAPK الاختبارات نحو 943 KB. تشمل اختبارات Android اختبارًا إيجابيًا يُنشئ مقطع AVC ويطلب تصدير 240p/1.5x، إضافةً إلى اختبارات الفشل الآمن؛ لكنها **جُمّعت وبُني APK لها ولم تُشغّل**، إذ إن `adb devices -l` لا يعرض جهازًا و`/dev/kvm` غير موجود. لذلك لا يُدّعى نجاح اختبارات Instrumented أو تحقق توافق MediaCodec/Transformer على جهاز.

## التكامل المستمر على GitHub

يعرّف `.github/workflows/android-ci.yml` فحصًا تلقائيًا عند كل `push` أو Pull Request إلى `main`، ويمكن تشغيله يدويًا من تبويب Actions. يشغّل اختبارات JVM وLint ويبني APK الاختبارات، ثم يشغّل Android Instrumented على محاكي API 36. ينشئ المفتاح المحلي المؤقت أثناء الـworkflow ويحذفه بانتهاء الـrunner؛ لا يحتاج workflow إلى PAT أو keystore محفوظ كـSecret.

## بنية المشروع

```text
app/src/main/java/com/example/
├── audio/       # مشغل الصوت، أدوات WAV، ومعالجة الصوت والفيديو
├── data/        # مكتبة الوسائط والتخزين المحلي
├── downloader/  # تحليل روابط الوسائط والتنزيل
├── model/       # نماذج البيانات والحالات
├── ui/          # ViewModel وشاشات وCompose components
└── MainActivity.kt

gradle/
└── wrapper/     # إعداد Gradle Wrapper
```

## الإعدادات والأسرار

- لا تضع كلمات مرور أو مفاتيح API أو رموز وصول داخل Git.
- ملف `.env.example` توضيحي. ملف `.env` المحلي مستثنى من Git عبر `.gitignore`، ولا ينبغي رفعه.
- إعداد Google Services مضبوط حاليًا على إصدار تحذير عند غياب `google-services.json`. إذا أُضيف استخدام فعلي لخدمات Firebase مستقبلًا، أضف إعداد Firebase المناسب محليًا ولا ترفع بيانات اعتماد خاصة.
- لا يحتاج بناء التطبيق الحالي إلى إضافة مفتاح GitHub أو تخزينه في المشروع.

## ملاحظات التطوير

- نقطة الدخول: `app/src/main/java/com/example/MainActivity.kt`.
- إعدادات التطبيق والاعتماديات: `app/build.gradle.kts` و`gradle/libs.versions.toml`.
- صلاحيات Android وتعريف الأنشطة ومزوّد الملفات: `app/src/main/AndroidManifest.xml` و`app/src/main/res/xml/file_paths.xml`.
- ملفات Gradle Wrapper (`gradlew` و`gradlew.bat` و`gradle/wrapper/gradle-wrapper.jar`) مضمّنة لتوحيد إصدار Gradle بين المطورين.
- إذا فشل البناء بسبب SDK غير موجود، ثبّت منصة Android SDK المطلوبة من SDK Manager، ثم أعد تشغيل أمر البناء. لا ترفع ملف `local.properties`؛ ينشئه Android Studio محليًا.
