plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.mgmt)
}

// Spring Dependency-Management overrides Gradle's native platform() BOM import (see Iter 2
// fix-up). Re-declare BOMs here so version.ref pins resolve correctly.
dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${libs.versions.testcontainers.get()}")
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.versions.spring.ai.get()}")
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

    // Spring AI 1.1.5 (Iter 4b.1 FR-A-COMMON, FR-A-R/C/RT). BOM imported above pins versions.
    implementation(libs.spring.ai.starter.model.anthropic)
    implementation(libs.spring.ai.starter.mcp.client)
    // FR-A-RT-002: routing-metrics RAG over pgvector. The starter pulls the autoconfig, which
    // wires PgVectorStore off the existing DataSource + our hand-rolled VoyageEmbeddingModel
    // (Spring AI 1.1.5 ships no Voyage starter).
    implementation(libs.spring.ai.starter.vector.store.pgvector)

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
