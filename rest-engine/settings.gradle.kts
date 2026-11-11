// 판단 로직만 담은 순수 Kotlin 빌드. 루트 빌드가 includeBuild로 가져다 쓴다.
// 이 폴더만 따로 빌드·테스트할 수 있다: ./gradlew -p rest-engine test (루트에서)
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
rootProject.name = "rest-engine"
// coach: 엔진을 차에서 돌리는 실행 계층(내비 추적, 정차 후보, 질문 상태기계, 시연/실제 전환, 코치 액터).
// 안드로이드 없이 포트(Ports.kt) 뒤에서 돌아서 이 서버에서 바로 테스트한다. 앱은 포트를 Pleos SDK로 구현한다
include(":coach")
