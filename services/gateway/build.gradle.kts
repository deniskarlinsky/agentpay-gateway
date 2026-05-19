plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.mgmt)
}

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
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.data.redis)

    implementation(libs.nimbus.jose.jwt)

    implementation(libs.bucket4j.core)
    implementation(libs.bucket4j.redis)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.testcontainers.junit)
    // Iter 5 hotfix: GatewayOrchestratorIntegrationIT pins the gateway↔orchestrator HTTP contract
    // (request body in snake_case, response surface propagated verbatim, unreachable → 502 not a
    // fake success). The existing GatewayApplicationTest stubs OrchestratorClient at the bean
    // level, so it could not have caught the live-demo wire bug.
    testImplementation(libs.wiremock)
}
