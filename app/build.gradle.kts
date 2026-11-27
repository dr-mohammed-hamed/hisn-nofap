plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.ksp)
}
import java.io.StringReader
import java.util.Properties

val localProps = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText
        .orNull
        ?.let { load(StringReader(it)) }
}
val otaBaseUrl = localProps.getProperty("ota.base.url")
    ?: providers.environmentVariable("OTA_BASE_URL").orNull
    ?: "https://example-releases.r2.dev"

val googleServicesFile = file("google-services.json")
if (!googleServicesFile.exists()) {
    googleServicesFile.writeText("""
        {
          "project_info": {
            "project_number": "1234567890",
            "project_id": "antiporn-mock",
            "storage_bucket": "antiporn-mock.appspot.com"
          },
          "client": [
            {
              "client_info": {
                "mobilesdk_app_id": "1:1234567890:android:mockappiddrmohammed",
                "android_client_info": {
                  "package_name": "com.drmohammed.antiporn"
                }
              },
              "oauth_client": [],
              "api_key": [
                {
                  "current_key": "fake_api_key_for_debug_builds"
                }
              ],
              "services": {}
            }
          ],
          "configuration_version": "1"
        }
    """.trimIndent())
}

val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

android {
    namespace = "com.drmohammed.antiporn"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.drmohammed.antiporn"
        minSdk = 24
        targetSdk = 36
        versionCode = 34
        versionName = "1.95"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("ar", "en")
        buildConfigField("String", "OTA_BASE_URL", "\"$otaBaseUrl\"")
        ndk {
            abiFilters.clear()
            abiFilters.addAll(supportedAbis)
        }
    }

    signingConfigs {
        // Local/dev key: load rotated key from keystore/key.properties if present, fallback to AOSP testkey or debug key.
        val keyPropsText = providers.fileContents(layout.projectDirectory.file("keystore/key.properties"))
            .asText
            .orNull
        if (keyPropsText != null) {
            val keyProps = Properties().apply {
                load(StringReader(keyPropsText))
            }
            create("rotatedDevKey") {
                storeFile = file("keystore/${keyProps.getProperty("storeFile", "antiporn-release.jks")}")
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
        val devTestKeyFile = file("keystore/testkey.p12")
        if (devTestKeyFile.exists()) {
            create("aospTestKey") {
                storeFile = devTestKeyFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // Production key comes exclusively from CI secrets (never committed).
        // Set KEYSTORE_PATH/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD to sign releases.
        val prodKeystorePath = providers.environmentVariable("KEYSTORE_PATH").orNull
        if (!prodKeystorePath.isNullOrBlank()) {
            create("prod") {
                storeFile = file(prodKeystorePath)
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("rotatedDevKey")
                ?: signingConfigs.findByName("aospTestKey")
                ?: signingConfigs.getByName("debug")
            providers.gradleProperty("devAbi").orNull?.trim()?.takeIf { it.isNotEmpty() }?.let { prop ->
                val devAbis = if (prop == "all") {
                    supportedAbis
                } else {
                    prop.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                }
                if (devAbis.isNotEmpty()) {
                    ndk {
                        abiFilters.clear()
                        abiFilters.addAll(devAbis)
                    }
                }
            }
        }
        release {
            // Uses "prod" when KEYSTORE_PATH is set (CI); otherwise falls back to
            // active dev signing so local assembleRelease still works but is NOT shippable.
            signingConfig = if (providers.environmentVariable("KEYSTORE_PATH").orNull.isNullOrBlank()) {
                signingConfigs.findByName("rotatedDevKey")
                    ?: signingConfigs.findByName("aospTestKey")
                    ?: signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("prod")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    androidResources {
        noCompress += "tflite"
        noCompress += "bin"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/INDEX.LIST"
        }
    }

    testOptions {
        unitTests.all {
            it.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
            it.forkEvery = 100
            it.maxHeapSize = "512m"
        }
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variantBuilder ->
        providers.gradleProperty("devMinSdk").orNull?.toIntOrNull()?.let {
            variantBuilder.minSdk = it
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.tensorflow.lite)
    // implementation(libs.onnxruntime.android) // Archived in features_archive/nudenet/ (reduces APK by ~37MB)

    // Firebase BoM & Cloud Infrastructure
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    // Coroutines & Play Services integration
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Wireless ADB & Device Owner Provisioning (Exclude external spake2-kt to use in-tree hardened API 26+ shim)
    implementation(libs.adb.kt) {
        exclude(group = "com.github.rhythmcache", module = "spake2-kt")
    }

    // QR Code Generation & Scanning (CameraX + ML Kit) & Printing
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.androidx.print)

    // Dependency Injection (Koin)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.workmanager)
    testImplementation(libs.koin.test)

    // AndroidX WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.androidx.work.testing)

    implementation(libs.timber)

    // Jetpack Room Persistence & Desugaring
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    testImplementation(libs.room.testing)

    testImplementation(libs.junit)
    testImplementation(libs.json.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

apply(from = "filter_generator.gradle.kts")

