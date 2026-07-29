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
    }
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
