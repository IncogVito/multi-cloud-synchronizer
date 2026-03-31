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

    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut.data:micronaut-data-jdbc")
    implementation("io.micronaut.security:micronaut-security")
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari")

    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.xerial:sqlite-jdbc:3.45.3.0")
    runtimeOnly("io.micronaut.sql:micronaut-jdbc-hikari")

    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
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
