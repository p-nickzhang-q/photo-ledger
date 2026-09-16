pluginManagement {
    repositories {
        // 票13：阿里云对 KSP 2.2.21-2.0.5 返回 502（Bad Gateway 持续），
        // mavenCentral 直连可用（实测 200），Kotlin 插件与 KSP 从这里走。
        mavenCentral()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://dl.google.com/dl/android/maven2/")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://dl.google.com/dl/android/maven2/")
        mavenCentral()
    }
}

rootProject.name = "photo-ledger"

include(":engine")
include(":engine-llamacpp")
include(":cli")
include(":app")
