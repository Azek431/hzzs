pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "hzzs"
include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:preferences")
include(":core:update")
include(":domain:vision")
include(":domain:automation")
include(":data:vision")
include(":feature:home")
include(":feature:runtime")
include(":feature:settings")
include(":feature:about")
include(":service:automation")
include(":service:capture")
include(":service:overlay")
include(":native:vision")
