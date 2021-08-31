import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.10"
    application
}
group = "com.andrewzurn"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.kohsuke:github-api:1.116")
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.11.3")
    testImplementation(kotlin("test-junit5"))
}
tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "11"
}
application {
    mainClassName = "MainKt"
}

// https://medium.com/@deepak_v/kotlin-beginner-to-advance-build-real-command-line-tool-using-kotlinx-cli-608bbc6d9a3
// ensure the jar can execute the main class
val jar by tasks.getting(Jar::class) {
    manifest {
        attributes["Main-Class"] = "MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
    }
}

// package it as an executable (ie. ./executable-name)
tasks.register<Copy>("packageDistribution") {
    dependsOn("jar")
    from("${project.rootDir}/scripts/github-utilities")
    from("${project.projectDir}/build/libs/${project.name}-${version}.jar") {
        into("lib")
    }
    into("${project.rootDir}/dist")
}
