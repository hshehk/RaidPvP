plugins {
    java
}

group = "me.tsukieru"
version = "1.0.0"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.citizensnpcs.co/repo")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    // SkinTrait lives in citizens-main (not citizensapi); the server provides it at runtime.
    compileOnly("net.citizensnpcs:citizens-main:2.0.37-SNAPSHOT") {
        isTransitive = false
    }
    // Optional PlaceholderAPI expansion (only loaded when PlaceholderAPI is installed).
    compileOnly("me.clip:placeholderapi:2.11.6") {
        isTransitive = false
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName.set("RaidPvP")
}

// The plugin has no runtime-bundled dependencies.
// Paper and Citizens (when NPC mode is enabled) are provided by the server.
