
plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("application")
}

group = "com.webhook"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

configure<JavaApplication> {
    mainClass.set("ApplicationAladinWebhookKt")
}

val ktorVersion = "2.3.12"
dependencies {
    testImplementation(kotlin("test"))
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
    implementation("ch.qos.logback:logback-classic:1.5.16")
    implementation("io.ktor:ktor-server-status-pages:${ktorVersion}")
    testImplementation("io.mockk:mockk:1.13.12")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

