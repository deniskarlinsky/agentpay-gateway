plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.mgmt) apply false
    alias(libs.plugins.spotless)
}

spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    java {
        target("services/**/*.java", "shared/**/*.java", "evals/**/*.java")
        googleJavaFormat()
        removeUnusedImports()
    }
}

subprojects {
    apply(plugin = "java")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 21
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
