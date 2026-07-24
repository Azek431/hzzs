pluginManagement {
    repositories {
        // CI（GitHub Actions）与多数环境：先查官方源，避免镜像滞后/缺包导致插件解析失败。
        // KSP 坐标在 com.google.devtools.ksp，发布于 Maven Central / Plugin Portal，不在 Google Maven。
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                // 避免 com.google.* 过滤器把 KSP 误导向 Google 仓库（该仓库无 KSP 插件包）。
                excludeGroup("com.google.devtools.ksp")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        // 国内镜像作回退（勿置于官方源之前，否则 CI 上易解析到不完整元数据）。
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                excludeGroup("com.google.devtools.ksp")
            }
        }
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "hzzs"
include(":app")
