// Copyright 2026  Koda Studio contributors. Licensed under the Apache License, Version 2.0.
//
// Formatting with ktlint. ktlint bundles its own Kotlin compiler (kotlin-compiler-embeddable), which can't share a
// classpath with the server's, so this module ships in the server's `formatter/` folder and is loaded in a class
// loader of its own (see org.kotlinlsp.actions.Formatting).
plugins {
    kotlin("jvm") version "2.3.10"
}

kotlin {
    jvmToolchain(17)
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
