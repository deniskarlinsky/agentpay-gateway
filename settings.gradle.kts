pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "agentpay-gateway"

include(
    "services:gateway",
    "services:orchestrator",
    "services:sanctions-mcp",
    "services:mock-psp",
    "services:buyer-client",
    "shared:api-contracts",
    "evals",
)
