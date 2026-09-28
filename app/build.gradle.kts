plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * 版本号来源：优先用 gradle -PversionName=x.y.z，其次用环境变量 GIT_TAG（CI 里由 tag 提供），
 * 最后退回默认值。versionCode 由 versionName 派生，保证单调递增。
 */
val appVersionName: String = run {
    val fromProp = (project.findProperty("versionName") as String?)?.trim().orEmpty()
    val fromEnv = System.getenv("GIT_TAG")?.trim().orEmpty()
    val raw = fromProp.ifBlank { fromEnv }.ifBlank { "1.0.0" }
    raw.removePrefix("v")
}

/** 1.2.3 -> 10203；保证每次发版 versionCode 都比上一个大。 */
val appVersionCode: Int = run {
    val parts = appVersionName.split('.', '-', '+')
        .map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    major * 10000 + minor * 100 + patch
}

android {
    namespace = "com.beian.tracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.beian.tracker"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        // 用 debug 签名，保证 release APK 可直接安装（本地自用，非上架）
        create("shared") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // APK 名字带上版本号，方便识别与更新
    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "huahua-${variant.versionName}-${variant.buildType.name}.apk"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ⚠️ 不要加回 play-services-location。
    //
    // 定位一律走系统原生 android.location.LocationManager：
    // 国行 ROM 上 GMS 常缺失或被冻结，FusedLocationProviderClient 会
    // 「调用成功但永不回调」，一个轨迹点都收不到且无异常可查。
    // 系统原生 API 在高德等所有第三方地图上都验证可用。
    implementation(libs.osmdroid.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
}