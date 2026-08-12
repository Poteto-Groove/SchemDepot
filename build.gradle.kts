import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

group = "io.github.potetogroove"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    // NOTE: https://mvn.intellectualsites.com/content/repositories/releases/ is listed in
    // the design doc but its host no longer resolves (DNS failure, confirmed 2026-08-11) -
    // the IntellectualSites Maven repo was sunset and FAWE now publishes to Maven Central
    // under the `com.fastasyncworldedit` group. It is intentionally omitted here since it
    // does not exist anymore and FAWE itself is not a compile-time dependency of this project.
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.worldedit.bukkit)

    implementation(libs.sqlite.jdbc)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // AssetServiceTest constructs real ClipboardService/PasteService instances (only their
    // constructors - no WorldEdit platform method is actually invoked). Those classes have
    // catch clauses referencing com.sk89q.worldedit.* exception types, so the JVM verifier
    // needs those types resolvable merely to *load* the class, even though worldedit-bukkit is
    // compileOnly for the plugin itself (SS4.2: never shaded/bundled). Widening scope to
    // testRuntimeOnly only affects the test JVM classpath, not the shipped shadowJar.
    testRuntimeOnly(libs.worldedit.bukkit)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    jvmToolchain(25)

    compilerOptions {
        // See report: Kotlin 2.4.10 jvmTarget support for 25 was verified empirically.
        jvmTarget = JvmTarget.JVM_25
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.sqlite", "io.github.potetogroove.schemdepot.libs.sqlite")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.runServer {
    minecraftVersion("26.1.2")

    downloadPlugins {
        // FastAsyncWorldEdit 2.15.3, verified to exist on Modrinth
        // (https://modrinth.com/plugin/fastasyncworldedit/version/2.15.3, version id Ad3NnAQP,
        // supports Paper 26.1.x / 26.2) at the time this build was configured (2026-08-11).
        modrinth("fastasyncworldedit", "Ad3NnAQP")
    }
}
