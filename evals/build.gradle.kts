plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.mgmt)
}

// evals is a test module, not a runnable service.
tasks.named("bootJar") { enabled = false }
tasks.named("jar") { enabled = true }

// Spring Dependency-Management overrides Gradle's native platform() BOM import — re-declare the
// Spring AI BOM here so spring-ai-starter-model-anthropic resolves to 1.1.5.
dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.versions.spring.ai.get()}")
    }
}

// Iter 6 (fix-up): Copy the three specialist prompt files into the eval test classpath. The eval
// module deliberately does NOT depend on :services:orchestrator (that drags in the pgvector and
// MCP-client autoconfigs whose autowiring fails without a DataSource), so we copy the prompts
// instead of resolving them at runtime through a sibling-module classpath.
val copyAgentPrompts by tasks.registering(Copy::class) {
    from("../services/orchestrator/src/main/resources/prompts")
    include("risk.md", "compliance.md", "routing.md")
    into(layout.buildDirectory.dir("generated-test-resources/prompts"))
}

sourceSets {
    named("test") {
        resources.srcDir(copyAgentPrompts.map { it.destinationDir.parentFile })
    }
}

tasks.named("processTestResources") {
    dependsOn(copyAgentPrompts)
}

dependencies {
    testImplementation(libs.spring.boot.starter.test)
    // Iter 6: ChatClient against the real Anthropic API for both the agent calls and the LLM
    // judge. No orchestrator dep — eval module stays minimal so its context starts clean.
    testImplementation(libs.spring.ai.starter.model.anthropic)
}
