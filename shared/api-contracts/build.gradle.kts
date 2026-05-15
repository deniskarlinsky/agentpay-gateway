plugins {
    `java-library`
    alias(libs.plugins.avro)
}

// Force the Avro plugin to use our explicitly pinned runtime — without this it would
// resolve its own default version transitively (see libs.versions.toml `avro` pin).
avro {
    isCreateSetters.set(false)
    fieldVisibility.set("PRIVATE")
    stringType.set("String")
}

dependencies {
    api(libs.avro)
}
