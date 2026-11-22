pluginManagement {
    repositories {
        val isCi = System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null
        if (!isCi) {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val isCi = System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null
        if (!isCi) {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
        }
        google()
        mavenCentral()
        if (!isCi) {
            maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
        }
    }
}

rootProject.name = "APKAgent"
include(":app")
