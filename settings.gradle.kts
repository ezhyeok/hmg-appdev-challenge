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
// 노트북에서는 프로젝트 폴더 안의 gradle-home만 쓴다(CLAUDE.md 개발 환경 메모). 노트북 배치는 <프로젝트>\repo가 이 저장소이고
// 옆에 gradle-home\이 있다. env.ps1 없이 gradlew를 부르면 사용자 홈의 .gradle에 의존성을 내려받으므로 여기서 멈춘다
// (2026-11-11 SSH로 gradlew.bat을 직접 불러 그렇게 됐다. tools/laptop/gradle.ps1을 쓴다)
rootDir.parentFile?.resolve("gradle-home")?.takeIf { it.isDirectory }?.let { projectHome ->
    if (gradle.gradleUserHomeDir.canonicalFile != projectHome.canonicalFile) {
        throw GradleException("GRADLE_USER_HOME은 $projectHome 이어야 한다 (tools/laptop/gradle.ps1로 실행). 지금: ${gradle.gradleUserHomeDir}")
    }
}
// pleos-kit: Pleos SDK를 코루틴/Flow로 감싼 공통 모듈, app: 공모전 앱
// rest-engine: 판단 로직(순수 Kotlin). 독립 빌드로 두고 composite build로 가져온다
includeBuild("rest-engine")
include(":pleos-kit", ":app")
