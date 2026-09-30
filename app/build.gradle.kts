plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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

        // ── 只打包需要的语言和密度 ────────────────────────────────────────
        //
        // 默认会把依赖库里所有语言的字符串全带上 —— 一个 AndroidX 库动辄
        // 几十种语言，本项目只面向中文用户，其余全是死重量。
        //
        // resConfigs("zh", "en")：保留中文（主）和英文（兜底，
        // 系统语言是英文时不会整片空白）。注意 "zh" 会一并覆盖 zh-rCN/
        // zh-rTW 等所有中文变体。
        //
        // ⚠️ 本项目**没有**多语言需求，这是自用侧载 App。
        //    将来若要支持别的语言，在这里补上语言代码即可。
        resourceConfigurations += listOf("zh", "en")
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
            // ── 开混淆 / 资源压缩 ────────────────────────────────────────────
            //
            // 之前是 false，APK 12.2MB 里一大半是没被裁掉的无用代码和资源。
            //
            // 安全性已逐项核实（不是凭感觉开的）：
            //   · 全项目**零反射** —— 没有 Class.forName / getDeclaredMethod，
            //     只有 ::class.java 这种类型引用（R8 不碰）
            //   · 没有 @Serializable / kotlinx.serialization（依赖已移除）
            //   · 没有 enum class
            //   · Room 的实体和 DAO 由 KSP 生成代码访问，自带 consumer rules
            //   · Compose / osmdroid 的 keep 规则见 proguard-rules.pro
            //
            // shrinkResources 依赖 minify，两者必须同时开。
            isMinifyEnabled = true
            isShrinkResources = true
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
}