plugins {
    java
    application
}

group = "com.brutaltank"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.undertow:undertow-core:2.3.18.Final")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    // Lightweight logging backend for Undertow's slf4j usage.
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.brutaltank.net.BrutalTankServer")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
