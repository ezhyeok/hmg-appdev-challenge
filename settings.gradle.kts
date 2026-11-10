pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Connect SDK (로그인 없이 접근 가능)
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}
rootProject.name = "hmg-appdev-challenge"
// pleos-kit: Pleos SDK를 코루틴/Flow로 감싼 공통 모듈, app: 공모전 앱
include(":pleos-kit", ":app")
