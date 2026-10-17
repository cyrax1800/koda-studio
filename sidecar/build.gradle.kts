import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm") version "2.3.10"
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
  implementation("org.gradle:gradle-tooling-api:9.8.0")
  // Keep in step with the newest AGP: the client's ModelBuilderParameter must know every method AGP calls on it.
  implementation("com.android.tools.build:builder-model:9.4.1")
  implementation("com.google.code.gson:gson:2.13.2")
  testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

// A single runnable jar. Packages are not relocated: the Tooling API matches model and parameter types by class name.
tasks.jar {
  archiveFileName.set("koda-sync.jar")
  manifest { attributes("Main-Class" to "koda.sync.MainKt") }
  dependsOn(configurations.runtimeClasspath)
  from(configurations.runtimeClasspath.map { files -> files.map { if (it.isDirectory) it else zipTree(it) } })
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
}

val copyToServer by tasks.registering(Copy::class) {
  from(tasks.jar)
  into(layout.projectDirectory.dir("../server"))
}
tasks.assemble { dependsOn(copyToServer) }
