plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":engine-llamacpp"))
    implementation(libs.kotlinx.coroutines.core)
    // 票 13：LiteRT-LM 桌面（JVM）实现，闸门用它与端侧同后端对比质量
    implementation("com.google.ai.edge.litertlm:litertlm-jvm:0.17.0")
    // --ocrdebug：端侧同款 OcrEngine 在桌面复现（与 app 的 onnxruntime-android 同 Java API）
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    testImplementation(libs.junit.jupiter)
}

// 票 13：litertlm-jvm 需 Java 21 class 文件，cli 的 JVM 目标同步提到 21
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

application {
    mainClass.set("com.pnickzhangq.photoledger.cli.MainKt")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
