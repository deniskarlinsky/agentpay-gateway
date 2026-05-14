plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.mgmt)
}

// evals is a test module, not a runnable service
tasks.named("bootJar") { enabled = false }
tasks.named("jar") { enabled = true }

dependencies {
    testImplementation(libs.spring.boot.starter.test)
}
