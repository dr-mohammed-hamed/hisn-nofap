# 📡 دليل منظومة التحديثات الهوائية المستقلة (Independent OTA via Cloudflare R2)

يعتمد مشروع **«حِصْن» (Hisn)** على آلية تحديث هوائية مستقلة ومباشرة (Over-The-Air) خارج متجر Google Play. يهدف هذا التوثيق إلى شرح كيفية عمل المنظومة، وكيف يمكن لأي مطور أو جهة تقوم بعمل Fork للمشروع استخدامها أو استبدالها بسهولة.

---

## 🏗️ 1. كيف تعمل منظومة التحديث الحالية؟

1. **الخادم السحابي (Cloudflare R2):**
   - تُستخدم خدمة Cloudflare R2 (المتوافقة مع S3 وذات تكلفة خروج بيانات صفرية Egress Fees) لاستضافة ملفين أساسيين:
     - `antiporn-release.apk`: حزمة التطبيق الموقعة والمجهزة للتثبيت.
     - `version.json`: ملف البيانات الوصفية (Metadata) الذي يحتوي على رقم الإصدار، رابط التحميل، بصمة التوقيع، وتفاصيل التحديث.

2. **التثبيت الصامت بصلاحية Device Owner:**
   - يمتلك التطبيق صلاحية مسؤول الجهاز المالك (Device Owner)، مما يسمح له بتنزيل التحديث وتثبيته في الخلفية عبر واجهة `PackageInstaller` في أندرويد دون الحاجة لتفاعل المستخدم.

3. **شجرة دوران التوقيع (Lineage Key Rotation):**
   - يدعم التطبيق معيار APK Signature Scheme v2/v3 Lineage Rotation لضمان قبول التحديثات التراكمية بأمان بين المفاتيح المتعاقبة.

---

## ⚙️ 2. للمطورين: كيف تخصص مسار التحديثات لبيئتك الخاصة؟

تم تجريد كافة الروابط الحساسة وعزلها عن الكود العام المرفوع على GitHub. لتشغيل التحديثات لحسابك:

### الخطوة 1: ضبط ملف `local.properties`
أضف المتغيرات التالية إلى ملف `local.properties` في جذر المشروع (الملف محمي ومستبعد تلقائياً من Git):

```properties
# عنوان التوزيع العام لحاوية Cloudflare R2 الخاصة بك
ota.base.url=https://your-custom-bucket.r2.dev

# اسم الحاوية في حساب Cloudflare
r2.bucket.name=your-releases-bucket

# كلمة سر مفتاح التوقيع الإنتاجي (اختياري، لتشغيل سكربت النشر الآلي دون مطالبة)
release.keystore.password=YourSecurePassword
```

### الخطوة 2: النشر التلقائي عبر السكربتات
عند تشغيل السكربت:
```powershell
.\scripts\publish_new_release.ps1 -NewVersionCode 19 -NewVersionName "1.80" -ReleaseNotes "تحسينات عامة"
```
سيقوم السكربت تلقائياً بـ:
1. بناء الـ APK وإعادة توقيعه عبر مفتاح التدوير.
2. تحديث `version.json` محلياً وحقن الرابط الفعلي من `local.properties` أثناء الرفع إلى R2.
3. رفع الملفات إلى حاوية R2 المحددة عبر أداة `wrangler`.

---

## 🔄 3. كيفية التحويل إلى مسارات بديلة (GitHub Releases أو خادم خاص)

إذا كنت ترغب في التخلي عن Cloudflare R2 واستخدام مسار آخر:

### أ. التحويل إلى GitHub Releases:
1. في ملف [`app/src/main/java/com/drmohammed/antiporn/ota/remote/OtaRemoteSource.kt`](../app/src/main/java/com/drmohammed/antiporn/ota/remote/OtaRemoteSource.kt):
   - يمكنك تعديل `fetchLatestVersion()` لتقرأ من واجهة GitHub API:
     `https://api.github.com/repos/YOUR_USER/YOUR_REPO/releases/latest`
2. في الـ Assets المرفوعة على Release في GitHub، قم بتضمين ملف الـ APK وملف `version.json`.

### ب. التحويل إلى خادم ويب خاص (Nginx / Apache / S3):
- يكفي فقط تغيير قيمة `ota.base.url` في `local.properties` (أو تمرير المتغير البيئي `OTA_BASE_URL` أثناء البناء) ليشير إلى خادمك:
  ```properties
  ota.base.url=https://updates.yourdomain.com
  ```
- سيربط محرك التطبيق تلقائياً بين هذا العنوان وملف `version.json`.

---

## 💖 4. إدارة التكوين السحابي لقنوات دعم المطور (`waqf_config.json`)

بجانب استضافة التحديثات الهوائية (`version.json` وملفات الـ APK)، يستضيف خادم Cloudflare R2 ملف **`waqf_config.json`** لإدارة قنوات الدعم الشخصي الاختياري للمطور ديناميكياً دون الحاجة لإصدار تحديث جديد للتطبيق. يقرأ التطبيق هذا الملف عبر مسار `${ota.base.url}/waqf_config.json` بنمط **Stale-While-Revalidate (SWR)**.

### 🔒 سياسة عدم التسريب الصارمة (Zero-Leak Policy)
* لحماية خصوصية المطور وعدم رفع أي أرقام هواتف شخصية (مثل رقم محفظة فودافون كاش) على مستودع GitHub العام:
  - تم حظر `waqf_config.json` و `scripts/waqf_config.json` و `**/waqf_config.json` بالكامل في ملف `.gitignore`.
  - يحتوي القالب العام [`scripts/waqf_config.example.json`](../scripts/waqf_config.example.json) فقط على القيمة الوهمية `"010XXXXXXXX"` لـ Vodafone Cash، بينما يقتصر على رابط `InstaPay` العام المنشور مسبقاً في `README.md`.
  - يلتزم الكود المصدري للتطبيق (`WaqfRepository.coldOfflineFallbackConfig`) بعدم تضمين أي أرقام هواتف أو أكواد USSD محلياً كإجراء احتياطي.

### 🚀 خطوات النشر عبر `deploy_waqf_config.ps1`
1. انسخ ملف القالب إلى المسار المحلي المستبعد من Git:
   ```powershell
   Copy-Item .\scripts\waqf_config.example.json .\scripts\waqf_config.json
   ```
2. افتح `scripts\waqf_config.json` واستبدل القيمة `"010XXXXXXXX"` برقم محفظة **Vodafone Cash** الفعلي (في كلٍ من `displayValue` و `primaryAction.target` و `secondaryAction.target`).
3. شغّل سكربت النشر (يقرأ `r2.bucket.name` و `ota.base.url` من `local.properties` تلقائياً):
   ```powershell
   .\scripts\deploy_waqf_config.ps1
   ```
   - يتحقق السكربت من صحة صيغة JSON (UTF-8) ومن عدم بقاء القيمة الوهمية `"010XXXXXXXX"` بالخطأ (إلا إذا مرّرت `-AllowPlaceholder` لأغراض الاختبار).
   - يتحقق من جلسة `wrangler whoami` ويرفع الملف إلى `$BucketName/waqf_config.json` برأس `Cache-Control: public, max-age=60, must-revalidate` و `Content-Type: application/json; charset=utf-8`.
   - يدعم السكربت معاملات اختيارية: `-BucketName` و `-ConfigFile` و `-AllowPlaceholder`.

### 🧩 إضافة قنوات مستقبلية أو تعطيل قناة مؤقتاً
يدعم نموذج `WaqfConfig.kt` إضافة أي قنوات جديدة مستقبلاً (مثل **PayPal** أو **Ko-fi** أو **حساب بنكي IBAN**) أو تعطيل أي قناة لحظياً بتغيير `"enabled": false` دون كسر الإصدارات الأقدم من التطبيق:

```json
{
  "channels": [
    {
      "id": "vodafone_cash_eg",
      "enabled": false,
      "sortOrder": 2,
      "type": "MOBILE_WALLET",
      "title": "فودافون كاش (Vodafone Cash)"
    },
    {
      "id": "paypal_global",
      "enabled": true,
      "sortOrder": 3,
      "type": "WEB_LINK",
      "badge": "دولي — PayPal",
      "title": "الدعم عبر PayPal",
      "subtitle": "دعم شخصي اختياري بالبطاقات البنكية من خارج مصر",
      "displayValue": "paypal.me/mh22887565",
      "accentStyle": "BLUE",
      "primaryAction": {
        "label": "فتح PayPal",
        "type": "OPEN_URL",
        "target": "https://paypal.me/mh22887565"
      }
    },
    {
      "id": "kofi_global",
      "enabled": true,
      "sortOrder": 4,
      "type": "WEB_LINK",
      "badge": "دولي — Ko-fi",
      "title": "الدعم عبر Ko-fi",
      "subtitle": "دعم لمرة واحدة أو رعاية شهرية اختيارية للتطوير",
      "displayValue": "ko-fi.com/dr_mohammed_hamed",
      "accentStyle": "AMBER",
      "primaryAction": {
        "label": "فتح Ko-fi",
        "type": "OPEN_URL",
        "target": "https://ko-fi.com/dr_mohammed_hamed"
      }
    },
    {
      "id": "bank_iban_eg",
      "enabled": true,
      "sortOrder": 5,
      "type": "BANK_ACCOUNT",
      "badge": "تحويل بنكي — IBAN",
      "title": "الحساب البنكي للمطور (دعم اختياري)",
      "subtitle": "تحويل بنكي محلي أو دولي عبر رقم الآيبان",
      "displayValue": "EG000000000000000000000000000",
      "accentStyle": "TEAL",
      "primaryAction": {
        "label": "نسخ رقم IBAN",
        "type": "COPY_VALUE",
        "target": "EG000000000000000000000000000"
      }
    }
  ]
}
```
