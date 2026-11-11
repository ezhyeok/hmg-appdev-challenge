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
