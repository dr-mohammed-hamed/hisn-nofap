package com.drmohammed.antiporn.banking

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import timber.log.Timber

/**
 * نموذج بيانات يمثل تطبيقاً بنكياً مثبتاً على جهاز المستخدم.
 */
data class InstalledBankingApp(
    val packageName: String,
    val appName: String,
    val icon: Drawable
)

/**
 * السجل المرجعي المعتمد والحصري لحزم التطبيقات البنكية والمصرفية الموثوقة (Banking App Whitelist Registry).
 * 
 * يمنع حدوث أي ثغرة أمنية أو التفاف عبر تقييد وضع التخفي المؤقت (Banking Cloak)
 * وحصره حصرياً في الحزم المدرجة هنا في الكود (Hardcoded Immutable Set).
 */
object BankingAppRegistry {

    private const val TAG = "AntiPorn_BankingRegistry"

    /**
     * القائمة البيضاء الثابتة للحزم البنكية والمحافظ الرقمية المعتمدة (مصر والدول العربية).
     */
    val ALLOWED_BANKING_PACKAGES: Set<String> = setOf(
        // 1. شبكة المدفوعات اللحظية المصرية (IPN)
        "com.egyptianbanks.instapay", // InstaPay Egypt

        // 2. البنوك المصرية الحكومية والخاصة الكبرى
        "com.nbe.mobilebanking", // National Bank of Egypt (NBE)
        "com.banquemisr.bmonline", // Banque Misr (BM Online)
        "com.cib.cibmobilebanking", // Commercial International Bank (CIB)
        "com.cibeg.cib", // CIB Smart Wallet / Alternative
        "com.qnb.alahli", // QNB AlAhli Egypt
        "com.qnb.egypt", // QNB Egypt alternative
        "com.alexbank.mobilebanking", // Bank of Alexandria (AlexBank)
        "com.intesasanpaolo.alexbank", // AlexBank Intesa Sanpaolo
        "com.bdc.mobilebanking", // Banque du Caire
        "com.adib.egypt", // Abu Dhabi Islamic Bank Egypt
        "com.adibegypt.mobile", // ADIB Egypt Mobile
        "com.faisalbank.mobilebanking", // Faisal Islamic Bank of Egypt
        "com.enbd.egypt", // Emirates NBD Egypt
        "com.cae.mobilebanking", // Credit Agricole Egypt
        "com.aaib.mobile", // Arab African International Bank (AAIB)
        "com.hsbc.hsbcegypt", // HSBC Bank Egypt
        "com.abk.egypt", // Al Ahli Bank of Kuwait (ABK Egypt)
        "com.scbank.mobile", // Suez Canal Bank
        "com.hdbegy.mobilebanking", // Housing and Development Bank (HDB)
        "com.egbank.mobile", // Egyptian Gulf Bank (EGBank)
        "com.saib.mobilebanking", // SAIB Bank
        "com.attijariwafabank.egypt", // Attijariwafa Bank Egypt
        "com.albaraka.egypt", // Al Baraka Bank Egypt

        // 3. المحافظ الرقمية وشركات الدفع والتقنية المالية (FinTech)
        "com.emeint.android.myservices", // Vodafone Cash (Ana Vodafone)
        "com.orange.orangecash", // Orange Cash Egypt
        "com.its.android.myetisalat", // Etisalat Cash (My Etisalat)
        "com.te.mywe", // WE Pay (My WE)
        "com.fawry.myfawry", // myFawry
        "com.telda.app", // Telda Egypt
        "com.klivvr.app", // Klivvr
        "com.thndr.app", // Thndr Investment

        // 4. كبرى المصارف العربية والخليجية
        "com.alrajhibank", // Al Rajhi Bank (Saudi Arabia)
        "com.alrajhicapital", // Al Rajhi Capital
        "com.alahli.mobile.banking", // SNB Mobile (AlAhli Saudi)
        "com.riyadbank.mobile", // Riyad Bank
        "com.alinma.mobile" // Alinma Bank
    )

    /**
     * التحقق مما إذا كانت الحزمة تنتمي للقائمة المرجعية المعتمدة.
     */
    fun isAllowed(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return packageName.trim().lowercase() in ALLOWED_BANKING_PACKAGES
    }

    /**
     * استخراج قائمة التطبيقات البنكية المثبتة فعلياً على جهاز المستخدم والمطابقة للقائمة المرجعية.
     */
    fun getInstalledBankingApps(context: Context): List<InstalledBankingApp> {
        val appContext = context.applicationContext ?: context
        val pm = appContext.packageManager ?: return emptyList()
        val installedApps = mutableListOf<InstalledBankingApp>()

        for (pkg in ALLOWED_BANKING_PACKAGES) {
            try {
                val launchIntent: Intent? = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val appName = pm.getApplicationLabel(appInfo)?.toString() ?: pkg
                    val icon: Drawable = pm.getApplicationIcon(appInfo)
                    installedApps.add(
                        InstalledBankingApp(
                            packageName = pkg,
                            appName = appName,
                            icon = icon
                        )
                    )
                }
            } catch (e: PackageManager.NameNotFoundException) {
                // Not installed on this device, skip silently
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error querying package info for banking package: $pkg")
            }
        }

        // ترتيب القائمة: إنستاباي في المقدمة، ثم ترتيب أبجدي لباقي البنوك
        return installedApps.sortedWith(
            compareBy<InstalledBankingApp> { it.packageName != BankingCloakManager.INSTAPAY_PACKAGE }
                .thenBy { it.appName }
        )
    }
}
