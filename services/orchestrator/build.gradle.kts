plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.mgmt)
}

// Spring Dependency-Management overrides Gradle's native platform() BOM import (see Iter 2
// fix-up). Re-declare the testcontainers BOM here so version.ref pins resolve correctly.
// Second module to do this (gateway is the first). Intentionally not extracted into a
// convention plugin yet — two ≠ pattern; three is where extraction earns its complexity.
dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${libs.versions.testcontainers.get()}")
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("${project.name}.jar")
}

dependencies {
    implementation(project(":shared:api-contracts"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.kafka)

    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.wiremock)
}
