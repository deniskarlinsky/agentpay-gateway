plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.mgmt)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("${project.name}.jar")
}

dependencies {
    // RestClient (the blocking HTTP client we use against the gateway) lives in spring-boot-starter-web.
    // No web server is started — application.yml sets web-application-type=none.
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)

    // Nimbus JOSE is used for buyer-side RSA signing over the canonical payment-form bytes
    // (NFR-S-005). Already pinned at the catalog level.
    implementation(libs.nimbus.jose.jwt)

    // Iter 6 (NFR-O-001): the CLI starts the trace; the outgoing HTTP call to the gateway carries
    // W3C trace context so Langfuse sees one continuous span tree from buyer → gateway → ... .
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.opentelemetry.exporter.otlp)

    testImplementation(libs.spring.boot.starter.test)
}
