// AirTouch Android 客户端 —— Gradle 设置
// 使用 pluginManagement + dependencyResolutionManagement 集中管理仓库。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AirTouch"
include(":app")
