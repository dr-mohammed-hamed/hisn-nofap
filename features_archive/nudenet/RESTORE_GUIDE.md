# دليل استرجاع محرك NudeNet و ONNX Runtime (Restore Guide)

تم حفظ وأرشفة هذا المكون كاملاً لتخفيض حجم حزمة التطبيق (APK) بمقدار **~37 ميجابايت** (توفير 55% من الحجم).
كافة الملفات البرمجية وملف النموذج تم حفظها هنا بأمان تام دون أي تعديل أو فقدان.

---

## محتويات هذا الأرشيف:
1. `assets/nudenet_320.onnx`: ملف النموذج العصبي (12.15 ميجابايت).
2. `code/NudeNetDetector.kt`: كود محرك استدلال ONNX ومعالجة الصور و NMS.
3. `code/NudeNetDetection.kt`: الفئات والبيانات التشريحية الـ 18 ومستويات الخطورة.
4. `tests/NudeNetDetectorTest.kt`: اختبارات الوحدة الرياضية لـ IoU و NMS والتصنيف.

---

## خطوات استرجاع الميزة مستقبلاً (في دقيقة واحدة):

### الخطوة 1: إعادة الملفات إلى مكانها في التطبيق
قم بتشغيل الأوامر التالية من المجلد الرئيسي للمشروع:
```bash
git mv features_archive/nudenet/assets/nudenet_320.onnx app/src/main/assets/nudenet_320.onnx
git mv features_archive/nudenet/code/NudeNetDetector.kt app/src/main/java/com/drmohammed/antiporn/ai/core/inference/NudeNetDetector.kt
git mv features_archive/nudenet/code/NudeNetDetection.kt app/src/main/java/com/drmohammed/antiporn/ai/core/model/NudeNetDetection.kt
git mv features_archive/nudenet/tests/NudeNetDetectorTest.kt app/src/test/java/com/drmohammed/antiporn/ai/core/inference/NudeNetDetectorTest.kt
```

### الخطوة 2: تفعيل المكتبة في ملف `app/build.gradle.kts`
قم بإلغاء التعليق عن سطر المكتبة:
```kotlin
implementation(libs.onnxruntime.android)
```
وفي كتلة `androidResources`، أعد السطر:
```kotlin
noCompress += "onnx"
```

### الخطوة 3: تفعيل القواعد في `app/proguard-rules.pro`
أعد قواعد Proguard:
```proguard
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
```

### الخطوة 4: تفعيل المنسق في `AiInferenceCoordinator.kt`
أعد تعريف وتشغيل `nudeNetDetector` عند الرغبة في دمجه مع تيار الإطارات.
