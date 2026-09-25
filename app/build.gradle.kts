import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 版本号由 CI 通过 -P 传入；本地构建回退到默认值
val appVersionName: String = (findProperty("appVersionName") as String?) ?: "0.1.0"
val appVersionCode: Int = ((findProperty("appVersionCode") as String?) ?: "1").toInt()

// CI 中由 workflow 解码 keystore 后写入该环境变量。
// 本地没有密钥时回退到 debug 签名，保证开发期也能出可安装的包。
val releaseKeystore = System.getenv("SIGNING_KEYSTORE_PATH")
    ?.let { file(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "com.haoli.swipegallery"
    // 最新 AndroidX（Compose 1.12.x / Lifecycle 2.11 / core-ktx 1.19）通过 AAR 元数据
    // 强制要求 compileSdk >= 37，不能降到 36。
    // 注意 API 37 起平台包按小版本命名（android-37.0 / 37.1 / 37.2），CI 中需安装这些包。
    compileSdk = 37

    defaultConfig {
        applicationId = "com.haoli.swipegallery"
        minSdk = 30
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storeType = "PKCS12"
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // M0 阶段不让 lint 阻断流水线，先产出报告；M1 起收紧为阻断
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

base {
    archivesName.set("SwipeGallery-$appVersionName")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":feature:gallery"))
    implementation(project(":feature:viewer"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
