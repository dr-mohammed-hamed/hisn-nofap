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
     * ملاحظة: يُحفظ نص الحزمة بحالة الأحرف الأصلية (Exact Case) لضمان التوافق مع PackageManager و BankingCloakManager.
     */
    val ALLOWED_BANKING_PACKAGES: Set<String> = setOf(
        // 1. شبكة المدفوعات اللحظية المصرية (IPN)
        "com.egyptianbanks.instapay", // InstaPay Egypt

        // 2. البنوك المصرية الحكومية والخاصة (معرفات Google Play المحدثة + المعرفات السابقة للتوافق العكسي)
        "com.nbe.mobile", // National Bank of Egypt - NBE Mobile
        "com.nbe.alahly", // National Bank of Egypt - AlAhly Mobile
        "com.nbe.mobilebanking", // National Bank of Egypt (NBE - legacy alias)
        "com.BanqueMisr.MobileBanking", // Banque Misr - BM Online
        "com.banquemisr.bmtonline", // Banque Misr - BM Online variant
        "com.banquemisr.bmonline", // Banque Misr (BM Online - legacy alias)
        "com.cib.mobilebanking", // CIB Mobile Banking
        "com.cib.cibpay", // CIB Pay
        "com.cib.cibmobilebanking", // Commercial International Bank (CIB - legacy alias)
        "com.cibeg.cib", // CIB Smart Wallet / Alternative (legacy alias)
        "com.qnb.qnbegypt", // QNB Egypt Mobile Banking
        "com.qnb.alahli", // QNB AlAhli Egypt (legacy alias)
        "com.qnb.egypt", // QNB Egypt alternative (legacy alias)
        "hr.asseco.android.intesa.isbd.mbanking.alex", // ALEXBANK Mobile Banking
        "com.alexbank.mobilebanking", // Bank of Alexandria (AlexBank - legacy alias)
        "com.intesasanpaolo.alexbank", // AlexBank Intesa Sanpaolo (legacy alias)
        "com.bdc.mobilebanking", // Banque du Caire
        "com.adib.egypt", // Abu Dhabi Islamic Bank Egypt
        "com.adibegypt.mobile", // ADIB Egypt Mobile
        "com.faisal.fibe", // Faisal Islamic Bank of Egypt - FIBE
        "com.faisalbank.mobilebanking", // Faisal Islamic Bank of Egypt (legacy alias)
        "com.emiratesnbd.egypt", // Emirates NBD Egypt
        "com.enbd.egypt", // Emirates NBD Egypt (legacy alias)
        "com.ca.egypt.mobile", // Credit Agricole Egypt - banki Mobile
        "com.cae.mobilebanking", // Credit Agricole Egypt (legacy alias)
        "com.aaib.mobilebanking", // AAIB Mobile Banking
        "com.aaib.mobile", // Arab African International Bank (AAIB - legacy alias)
        "com.hsbc.hbegmobile", // HSBC Egypt
        "com.hsbc.hsbcegypt", // HSBC Bank Egypt (legacy alias)
        "com.abk.egypt", // Al Ahli Bank of Kuwait (ABK Egypt)
        "com.scb.mobilebanking", // Suez Canal Bank - SCB Mobile
        "com.scbank.mobile", // Suez Canal Bank (legacy alias)
        "com.hdb.mobilebanking", // Housing and Development Bank - HDB
        "com.hdbegy.mobilebanking", // Housing and Development Bank (HDB - legacy alias)
        "com.egbank.mobilebanking", // EGBank Mobile Banking
        "com.egbank.mobile", // Egyptian Gulf Bank (EGBank - legacy alias)
        "com.saib.mobilebanking", // SAIB Bank
        "com.attijariwafa.egypt", // Attijariwafa Bank Egypt
        "com.attijariwafabank.egypt", // Attijariwafa Bank Egypt (legacy alias)
        "com.albaraka.egypt.mobile", // Al Baraka Bank Egypt
        "com.albaraka.egypt", // Al Baraka Bank Egypt (legacy alias)
        "com.fabmisr.mobile", // FABMISR Mobile
        "com.mashreq.egypt.mb", // Mashreq Egypt
        "com.arabbank.arabilifegp", // Arab Bank Egypt - Arabi Mobile
        "com.nbk.egypt", // NBK Egypt Mobile Banking
        "com.ebank.mobilebanking", // EBank - Export Development Bank of Egypt
        "com.banknxt.mobile", // Bank NXT Mobile
        "com.adcb.egypt.mobile", // ADCB Egypt Mobile
        "com.midbank.mobilebanking", // MIDBANK Mobile Banking
        "com.ubc.mobilebanking", // The United Bank

        // 3. المحافظ البنكية ومحافظ الاتصالات والبريد المصري وشركات التقنية المالية (FinTech)
        "com.TE.WEWallet", // WE Pay
        "com.te.mywe", // WE Pay (My WE - legacy alias)
        "com.nbe.phonecash", // NBE PhoneCash Wallet
        "com.nbe.tapandpay", // NBE Tap & Pay
        "com.banquemisr.wallet", // BM Wallet
        "com.BDC.QaheraCash", // Qahera Cash
        "com.emeint.android.mwallet.smartwallet", // CIB Smart Wallet
        "com.qnb.bebasata", // QNB bebasata
        "com.fabmisr.cash2go", // FABMISR Cash2Go Wallet
        "com.emeint.android.myservices", // Vodafone Cash (Ana Vodafone)
        "com.orange.eg.smarthub", // Orange Max it - Egypt
        "com.orange.eg", // My Orange Egypt
        "com.orange.orangecash", // Orange Cash Egypt (legacy alias)
        "com.etisalat.flous", // etisalat money - EG
        "com.its.android.myetisalat", // Etisalat Cash (My Etisalat - legacy alias)
        "com.paysky.yalla", // Yalla Super App - Egypt Post
        "com.efinance.easypay", // EasyPay - Egypt Post
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
     * مجموعة الحزم المعتمدة بأحرف صغيرة للتحقق السريع (O(1)) دون التأثر بحالة الأحرف.
     */
    private val ALLOWED_PACKAGES_LOWERCASE: Set<String> =
        ALLOWED_BANKING_PACKAGES.map { it.lowercase() }.toSet()

    /**
     * التحقق مما إذا كانت الحزمة تنتمي للقائمة المرجعية المعتمدة.
     */
    fun isAllowed(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return packageName.trim().lowercase() in ALLOWED_PACKAGES_LOWERCASE
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
