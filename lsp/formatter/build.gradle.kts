// Copyright 2026  Koda Studio contributors. Licensed under the Apache License, Version 2.0.
//
// Formatting with ktlint. ktlint bundles its own Kotlin compiler (kotlin-compiler-embeddable), which can't share a
// classpath with the server's, so this module ships in the server's `formatter/` folder and is loaded in a class
// loader of its own (see org.kotlinlsp.actions.Formatting).
plugins {
    kotlin("jvm") version "2.3.10"
}

// Compiled by the JDK Gradle runs on (25), for Java 17 like the server.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

val ktlintVersion = "1.8.0"

dependencies {
    implementation("com.pinterest.ktlint:ktlint-rule-engine:$ktlintVersion")
    implementation("com.pinterest.ktlint:ktlint-ruleset-standard:$ktlintVersion")
    // ktlint logs through SLF4J; without a binding it prints a warning on every start.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}
