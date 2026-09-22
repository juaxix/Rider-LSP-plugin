plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

group = "com.jxapps"
version = "1.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Rider SDK selection:
//  * -PriderLocalPath="C:/Program Files/JetBrains/JetBrains Rider 2026.2" compiles against an installed IDE
//    (fast, offline, and guaranteed to match what you run).
//  * otherwise the Rider distribution given by -PriderVersion (default below) is downloaded.
val riderLocalPath: String? = providers.gradleProperty("riderLocalPath").orNull
val riderVersion: String = providers.gradleProperty("riderVersion").getOrElse("2026.2.2")

dependencies {
    intellijPlatform {
        if (!riderLocalPath.isNullOrBlank()) {
            local(riderLocalPath)
        } else {
            rider(riderVersion) { useInstaller = false }
        }
    }

    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1")
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262"
            // No upper bound: the plugin only uses stable platform APIs directly and reaches Rider
            // internals through guarded reflection, so it keeps loading on future Rider releases.
            untilBuild = provider { null }
        }
    }

    buildSearchableOptions = false
    instrumentCode = false
}

// Smoke testing: ./gradlew runIde -PrunIdeProject="C:/path/to/project" opens that project in the sandbox IDE.
tasks.runIde {
    providers.gradleProperty("runIdeProject").orNull?.let { args(it) }
}
