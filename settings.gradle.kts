pluginManagement {
    repositories {
        // Android Gradle Plugin、Google 与 AndroidX 依赖优先使用 Google Maven。
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }

        // 当前网络环境下，Maven Central 直连会出现 403。
        // 阿里云镜像作为 Maven Central / Google Maven 的优先回退来源。
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }

        // 官方仓库保留为镜像缺失时的最终回退。
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        // 普通 Android 依赖下载顺序：
        // 阿里云镜像优先，官方 Google / Maven Central 作为回退。
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "MyApplication"
include(":app")