plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("io.micronaut.application") version "4.4.2"
    id("io.micronaut.graalvm") version "4.4.2"
    id("io.micronaut.aot") version "4.4.2"
}

version = "1.0.0"
group = "com.cloudsync"

repositories {
    mavenCentral()
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    annotationProcessor("io.micronaut.security:micronaut-security-annotations")
    annotationProcessor("io.micronaut.data:micronaut-data-processor")
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")

    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut.data:micronaut-data-jdbc")
    implementation("io.micronaut.security:micronaut-security")
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari")
    implementation("io.micronaut.flyway:micronaut-flyway")
    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("io.micronaut.reactor:micronaut-reactor-http-client")
    implementation("io.micronaut.validation:micronaut-validation")

    // Flyway 9.x for SQLite support (SQLite moved to teams in v10)
    implementation("org.flywaydb:flyway-core:9.22.3")

    // Thumbnail generation
    implementation("net.coobird:thumbnailator:0.4.20")

    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.xerial:sqlite-jdbc:3.45.3.0")
    runtimeOnly("org.yaml:snakeyaml")

    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.45.3.0")
}

application {
    mainClass.set("com.cloudsync.Application")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

graalvmNative.toolchainDetection.set(false)

micronaut {
    version("4.7.6")
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.cloudsync.*")
    }
    aot {
        // Please review carefully the optimizations enabled below
        // https://micronaut-projects.github.io/micronaut-aot/latest/guide/
        optimizeServiceLoading.set(false)
        convertYamlToJava.set(false)
        precomputeOperations.set(true)
        cacheEnvironment.set(true)
        optimizeClassLoading.set(true)
        deduceEnvironment.set(true)
        optimizeNetty.set(true)
    }
}
