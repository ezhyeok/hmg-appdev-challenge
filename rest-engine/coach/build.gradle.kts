import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 엔진(루트 프로젝트)을 차에서 돌리는 실행 계층. 안드로이드·Pleos SDK에 의존하지 않는다 (docs/ARCHITECTURE.md)
// 앱은 dev.shimpyo:coach 좌표로 의존하고 composite build가 이 프로젝트로 대체한다
plugins {
    kotlin("jvm")
}

group = "dev.shimpyo"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":"))
    // 앱(pleos-kit)과 같은 버전
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.test {
    useJUnit()
}
