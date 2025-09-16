plugins {
    id("org.jetbrains.intellij.platform") version "2.9.0"
    kotlin("jvm") version "2.2.0"
}

group = "eligibbs.ive"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform.defaultRepositories()
}

dependencies {
    intellijPlatform {
        create("IC-2025.2.1")

        instrumentationTools()
    }
}

tasks {
    patchPluginXml {
        sinceBuild.set("242.0")
        untilBuild.set("252.*")
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    withType<JavaCompile>().configureEach {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.release.set(17)
    }
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
kotlin { jvmToolchain(17) }