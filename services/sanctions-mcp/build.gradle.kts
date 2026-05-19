plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.mgmt)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("${project.name}.jar")
}

dependencies {
    implementation(platform(libs.spring.ai.bom))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.ai.starter.mcp.server.webmvc)
    implementation(libs.mcp.annotations)

    // Iter 6 (NFR-O-001/002, NFR-M-006): OTLP traces to otel-collector so case_id propagates
    // through the MCP tool invocation as a child span of the orchestrator's case span.
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.opentelemetry.exporter.otlp)

    testImplementation(libs.spring.boot.starter.test)
}
