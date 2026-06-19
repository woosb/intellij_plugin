import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

kotlin {
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

group = "com.github.wooju"
version = "1.0.6"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA Ultimate — DB Tools API 포함
        intellijIdeaUltimate("2024.3.5")
        // Database plugin (com.intellij.database) 번들 플러그인 의존성
        bundledPlugin("com.intellij.database")

        pluginVerifier()
        zipSigner()
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Oracle Dictionary Inspector"
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }

    // ./gradlew verifyPlugin 실행 시 검사할 IDE 목록.
    //  - recommended() 는 2024.3 ~ 최신 EAP까지 6개를 끌어와 매우 오래 걸린다.
    //  - 우리는 sinceBuild = "243"(2024.3) 부터 호환을 보장하면 충분하므로
    //    빌드 타깃과 동일한 2024.3.5 한 개만 검사한다. 출시 직전에 더 추가할 수 있음.
    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaUltimate, "2024.3.5")
        }
    }
}

tasks {
    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
}
