plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

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
        rider("2026.1")
    }

    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    patchPluginXml {
        sinceBuild.set("261")
        untilBuild.set("261.*")
    }

    buildSearchableOptions {
        enabled = false
    }

    instrumentCode {
        enabled = false
    }
}
