import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releasePropertiesFile = rootProject.file("keystore.properties")
val releaseProperties = Properties().apply {
    if (releasePropertiesFile.exists()) {
        releasePropertiesFile.inputStream().use(::load)
    }
}
val isReleaseSigningConfigured = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !releaseProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.masahhisabat.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.masahhisabat.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 68
        versionName = "1.2.65"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // أجهزة Android الفعلية المستهدفة تعمل بمعماريات ARM؛ استبعاد x86 يزيل نسخ OpenCV غير اللازمة.
        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }
        resourceConfigurations += setOf("ar", "en")
    }

    signingConfigs {
        if (isReleaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseProperties.getProperty("storeFile")))
                storePassword = requireNotNull(releaseProperties.getProperty("storePassword"))
                keyAlias = requireNotNull(releaseProperties.getProperty("keyAlias"))
                keyPassword = requireNotNull(releaseProperties.getProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (isReleaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        doFirst {
            check(isReleaseSigningConfigured) {
                "تعذر بناء Release: أنشئ keystore.properties محليًا من القالب وأدخل بيانات مفتاح التوقيع."
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // المصادقة بالبصمة تعمل محلياً عبر طبقة AndroidX المتوافقة مع أجهزة هواوي، مع بقاء PIN بديلاً دائماً.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.code.gson:gson:2.10.1")
    // محرك Tesseract يعمل محلياً؛ لا يحتاج إلى خدمات Google أو اتصال أثناء القراءة.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    // حزمة OpenCV الرسمية: تُضمّن محلياً في APK ولا تعتمد على خدمات Google وقت التشغيل.
    implementation("org.opencv:opencv:4.9.0")
    // WebRTC: قناة صوت/فيديو مباشرة، مع تبادل الإشارة داخل الشبكة المحلية.
    implementation("io.github.webrtc-sdk:android:144.7559.12")

    // اختبارات منطق محلي خفيفة لتغطية مسارات المصادقة دون اعتماد على جهاز أو GMS.
    testImplementation("junit:junit:4.13.2")
    // اختبارات واجهة تعمل عبر AndroidX/Espresso ولا تعتمد على خدمات Google.
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.5.1")
}
