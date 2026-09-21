import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 发布签名（keystore.properties 不入库；.jks 丢失则无法再发布同签名更新）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.pnickzhangq.photoledger"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.pnickzhangq.photoledger"
        minSdk = 29
        targetSdk = 36
        versionCode = 15
        versionName = "0.4.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // 票 04：冒烟阶段只出 arm64（目标机 V2183A）；后续需要再扩
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // ONNX Runtime 的 jni 里可能有同名 native so 冲突提示；先按需排除
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":engine"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.activity:activity-compose:1.10.1")

    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // 票 07：队列状态图标（Schedule/Error/ContentCopy 在 extended 包）
    implementation("androidx.compose.material:material-icons-extended")

    // 票 06：列表/确认界面图片加载（缩略图与原图对照）
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // 票 13：LiteRT-LM 推理后端（速度破局评估——vivo 同厂基准 GPU 21 tok/s vs llama.cpp 1）
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.0")

    // 票 06：账目库（Room schema 在本票定型）
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // 票 11：备份 JSON 编解码
    implementation(libs.kotlinx.serialization.json)

    // S3 接缝测试：JVM + Room 内存库（Robolectric 是 JUnit4 runner，须用 junit4）
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
