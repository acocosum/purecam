import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// 签名相关（**仓库内不含任何 keystore**：签名密钥不该进版本库）
//
// - 只想编译安装：直接 `./gradlew assembleRelease`，会自动回退到 debug 签名，开箱即用；
// - 想用自己的正式签名：把 jks 放到 `keystore/release.jks`，并在（已被 gitignore 的）
//   `local.properties` 里补上三行：
//       purecam.storePassword=你的口令
//       purecam.keyAlias=你的别名
//       purecam.keyPassword=你的口令
//
// 注意：这里不能写 `java.util.Properties()` —— Gradle Kotlin DSL 里 `java` 会被解析成
// JavaPluginExtension 扩展，`java.util` 会报 Unresolved reference，必须用上面的 import。
// ---------------------------------------------------------------------------
val purecamProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val releaseKeystore = rootProject.file("keystore/release.jks")
val hasReleaseKeystore = releaseKeystore.exists()
val keystoreStorePassword = purecamProps.getProperty("purecam.storePassword") ?: "android"
val keystoreKeyAlias = purecamProps.getProperty("purecam.keyAlias") ?: "purecam"
val keystoreKeyPassword = purecamProps.getProperty("purecam.keyPassword") ?: "android"

android {
    namespace = "com.purecam.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.purecam.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // 只打包中文/英文资源，减小体积
        resourceConfigurations += setOf("zh", "en")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystoreStorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // 没有 keystore/release.jks 时回退 debug 签名，保证 clone 后能直接构建出可安装的包
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/AL2.0",
                "/META-INF/LGPL2.1",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // 注意：**不使用 CameraX**。
    // 预览直接用 Camera2 + TextureView，原因有两个：
    // 1) CameraX 的 CameraSelector 只能选中逻辑摄像头，无法把画面路由到物理子摄
    //    （超广角 / 长焦），会表现为「切了镜头画面不变」；
    // 2) 自己持有 TextureView 时，旋转 / 填充 / 镜像 / 拉伸补偿完全由我们自己的矩阵决定，
    //    不受 CameraX 实现模式（SurfaceView 会无视应用层变换）的限制。
}
