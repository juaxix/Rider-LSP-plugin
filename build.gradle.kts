plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
}

// Kotlin configuration is handled by the plugin

group = "com.jxapps"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        rider("2026.2")
    }

    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1")
}

tasks {
    patchPluginXml {
        sinceBuild.set("262")
        untilBuild.set("262.*")
    }

    buildSearchableOptions {
        enabled = false
    }

    instrumentCode {
        enabled = false
    }
}
