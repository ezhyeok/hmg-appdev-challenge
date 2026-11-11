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
// rest-engine: 판단 로직(순수 Kotlin). 독립 빌드로 두고 composite build로 가져온다
includeBuild("rest-engine")
include(":pleos-kit", ":app")
