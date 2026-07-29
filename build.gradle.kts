plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        // 플랫폼 테스트 프레임워크 미사용 — 순수 로직 테스트라 JUnit5 만으로 충분
        // (추가하면 com.intellij.tests.JUnit5TestSessionListener 가 테스트 실행을 막는다)
    }
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // 플랫폼 플러그인이 테스트 태스크에 자기 설정을 주입해 JUnit4 클래스를 요구한다
    testRuntimeOnly("junit:junit:4.13.2")
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    // Java 폼/@NotNull 계측 미사용 — 끄면 빌드가 짧아진다
    instrumentCode = false

    pluginConfiguration {
        id = "dev.roon.taskqueue"
        name = "Task Queue"
        version = providers.gradleProperty("pluginVersion")
        vendor {
            name = "roon"
        }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // untilBuild 미지정 — IDE 업그레이드로 사용 불가가 되는 것을 방지 (설계 5.3.4)
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(21)
}
