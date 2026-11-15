# 🤝 دليل المساهمة في مشروع «حِصْن» (Contributing Guidelines)

مرحباً بك وشكراً لاهتمامك بالمساهمة في تطوير مشروع **«حِصْن» (Hisn / AntiPorn DO)**.  
مشروع «حِصْن» هو مشروع وقفي إنساني وتقني يهدف إلى توفير أعلى درجات الحماية الذاتية والسيادية لمستخدمي أندرويد. نرحب بكافة المساهمات البرمجية، وتحسينات الأداء، والترجمات، وتصحيح الأخطاء.

---

## 🏛️ المرجع الإلزامي: دستور المشروع الهندسي
قبل البدء في كتابة أي سطر برمجي، **يجب قراءة [دستور المشروع الهندسي (PROJECT_CONSTITUTION.md)](PROJECT_CONSTITUTION.md) والالتزام الصارم ببنوده**.  
أي Pull Request يخرق أحد بنود الدستور (مثل استخدام `!!` أو تسريب أسرار أو استخدام `Thread.sleep` على الـ Main Thread) سيتم رفضه آلياً.

---

## 📜 اتفاقية ترخيص المساهمين (Contributor License Agreement - CLA)
بتقديمك لأي مساهمة أو فتح طلب دمج (Pull Request) في مشروع «حِصْن»، فإنك تقر وتوافق على شروط **[اتفاقية ترخيص المساهمين (CLA.md)](CLA.md)**.
* **النسخة العامة المجتمعية:** تظل مساهمتك مفتوحة المصدر ومتاحة للجميع ومحمية برخصة **GPL-3.0**.
* **الترخيص المزدوج (Dual Licensing):** تمنح مالك ومؤسس المشروع (Dr. Mohammed Hamed) ترخيصاً دائماً وغير حصري يجيز له استخدام مساهمتك في نسخ تجارية أو مغلقة المصدر أو خدمات سحابية دون تعارض مع بقاء النسخة الوقفية مفتوحة.
* **طريقة التوقيع:** عند فتح طلب دمج لأول مرة، سيطلب منك بوت التحقق تأكيد الموافقة عبر الرد بتعليق بسيط:
  ```text
  I have read the CLA Document and I hereby sign the CLA
  ```

---

## 🛠️ متطلبات بيئة التطوير (Prerequisites)
1. **JDK:** Java 17 فما فوق (نوصي بـ Eclipse Temurin 17).
2. **Android SDK:** Compile SDK 36 (Android 15 / 16) & Build-Tools الحديثة.
3. **Android Studio:** أحدث إصدار مستقر (Ladybug أو Meerkat).
4. **أداة ADB:** متوفرة على سطر الأوامر لتثبيت وتجربة صلاحية Device Owner.

---

## 🚀 دورة التطوير المحلية (Local Development Workflow)

### 1. استنساخ المستودع
```bash
git clone https://github.com/dr-mohammed-hamed/antiporn.git
cd antiporn
```

### 2. التجميع الأولي واختبار التجميع
التطبيق مجهز بمولد إعدادات وهمي تلقائي؛ لا تحتاج لأي مفاتيح سحابية أو ملفات خاصة للبناء التجريبي:
```bash
./gradlew assembleDebug
```

### 3. تشغيل حزمة الاختبارات المؤتمتة
يجب أن تجتاز جميع اختبارات الوحدات (Unit Tests) بنسبة 100%:
```bash
./gradlew testDebugUnitTest
```

---

## 📐 معايير جودة الكود (Code Standards)
* **Kotlin بنسبة 100%:** الاعتماد الكامل على النمط الاصطلاحي (Idiomatic Kotlin).
* **حظر الـ Force-Unwrap:** استخدام `!!` محظور قطعياً في كافة ملفات المشروع.
* **إدارة التزامن (Coroutines):** لا تستخدم أبداً `CoroutineScope(Dispatchers.IO).launch` عشوائياً؛ التزم بنطاقات دورة الحياة (`viewModelScope` / `applicationScope`).
* **السجلات (Logging):** الاعتماد حصراً على مكتبة `Timber` وحظر `Log.d` المباشر أو `println`.
* **عزل النصوص:** كافة النصوص البرمجية تُعزل في `res/values/strings.xml` مع ترجمتها الإنجليزية المقابلة في `res/values-en/strings.xml`.

---

## 📬 إرسال طلبات الدمج (Submitting a Pull Request)
1. أنشئ فرعاً جديداً لتعديلك: `git checkout -b feature/your-feature-name` أو `fix/your-fix-name`.
2. تأكد من نجاح أمر `./gradlew testDebugUnitTest` محلياً.
3. التزم بتنسيق رسائل الـ Commit القياسية (Conventional Commits)، مثل:
   - `feat(curfew): add adaptive nap schedule`
   - `fix(dns): prevent opportunistic fallback on vpn drop`
4. افتح طلب الدمج (PR) على فرع `main` واملأ نموذج الـ Pull Request المرفق.
5. تأكد من تحديد خانة الموافقة على [اتفاقية ترخيص المساهمين (CLA.md)](CLA.md) في نموذج الـ PR، وتأكيد التوقيع عند مطالبة بوت التحقق بذلك.
