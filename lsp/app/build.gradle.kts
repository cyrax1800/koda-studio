// Modified 2026 by Koda Studio contributors: Analysis API 2.4.20 with the IntelliJ platform it is built against,
// following KSP's dependency set (github.com/google/ksp, kotlin-analysis-api/build.gradle.kts, Apache-2.0).
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.commons.ClassRemapper
import org.jetbrains.org.objectweb.asm.commons.Remapper

plugins {
    val kotlinVersion = "2.3.10"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion
    application
}

// Compiled by the JDK Gradle runs on (25) into JVM 17 bytecode, against the Java 17 API.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
        // The server is an Analysis API platform, so it uses the platform-level and experimental APIs throughout.
        optIn.addAll(
            "org.jetbrains.kotlin.analysis.api.KaPlatformInterface",
            "org.jetbrains.kotlin.analysis.api.KaExperimentalApi",
            "org.jetbrains.kotlin.analysis.api.KaImplementationDetail",
            "org.jetbrains.kotlin.analysis.api.KaIdeApi",
            "org.jetbrains.kotlin.analysis.api.KaNonPublicApi",
            "org.jetbrains.kotlin.analysis.api.KaSpiExtensionPoint",
            "org.jetbrains.kotlin.analysis.api.platform.KaCachedService",
            "org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals",
            "org.jetbrains.kotlin.config.CompilerConfiguration.Internals",
            "org.jetbrains.kotlin.CoreEnvironmentDeprecation",
            "org.jetbrains.kotlin.K1Deprecation",
        )
    }
}

application {
    applicationName = "kotlin-lsp"
    mainClass = "org.kotlinlsp.MainKt"
    version = "2.0"
}

/** The Kotlin front end the server analyses with. The IntelliJ version is the one this Kotlin release is built with. */
val analysisApiVersion = "2.4.20"
val intellijVersion = "251.27812.49" // github.com/JetBrains/kotlin/blob/v2.4.20/gradle/versions.properties

repositories {
    mavenCentral()
    maven(url = "https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven(url = "https://www.jetbrains.com/intellij-repository/releases")
    maven(url = "https://cache-redirector.jetbrains.com/intellij-third-party-dependencies")
    maven(url = "https://repo.gradle.org/gradle/libs-releases")
}

val intellijOriginal: Configuration by configurations.creating { isTransitive = false }
/**
 * Kotlin compiler plugins built for this server's compiler version. A project's own plugin jars target its Kotlin
 * version (e.g. 2.3.x) and can't run in this front end, so the server maps them by plugin to these. They ship in
 * `plugins/`, outside the classpath, and each module loads only its own.
 */
val compilerPlugins: Configuration by configurations.creating { isTransitive = false }
/** ktlint and its own Kotlin compiler, shipped in `formatter/` and loaded apart from the server's classpath. */
val formatter: Configuration by configurations.creating

/**
 * The IntelliJ platform jars call `kotlinx.coroutines.internal.intellij.IntellijCoroutines`, which only exists in
 * IntelliJ's patched coroutines. Rewriting those references to `com.intellij.util.IntelliJCoroutinesFacade` lets them run
 * with the regular kotlinx-coroutines, as KSP does.
 */
abstract class TransformIntellijDeps : DefaultTask() {
    @get:InputFiles abstract val inputJars: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun transform() {
        val remapper = object : Remapper() {
            override fun map(internalName: String): String =
                if (internalName == "kotlinx/coroutines/internal/intellij/IntellijCoroutines") "com/intellij/util/IntelliJCoroutinesFacade"
                else super.map(internalName)
        }
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        inputJars.forEach { jarFile ->
            ZipOutputStream(out.resolve(jarFile.name).outputStream()).use { zos ->
                ZipFile(jarFile).use { zip ->
                    for (entry in zip.entries()) {
                        zos.putNextEntry(ZipEntry(entry.name))
                        if (!entry.isDirectory) {
                            val bytes = zip.getInputStream(entry).readBytes()
                            zos.write(
                                if (entry.name.endsWith(".class")) {
                                    val writer = ClassWriter(0)
                                    ClassReader(bytes).accept(ClassRemapper(writer, remapper), 0)
                                    writer.toByteArray()
                                } else bytes
                            )
                        }
                        zos.closeEntry()
                    }
                }
            }
        }
    }
}

val transformedIntellijDeps = tasks.register<TransformIntellijDeps>("transformedIntellijDeps") {
    inputJars.from(intellijOriginal)
    outputDir.set(layout.buildDirectory.dir("transformedIntellijDeps"))
}

dependencies {
    listOf(
        "com.jetbrains.intellij.platform:util-rt",
        "com.jetbrains.intellij.platform:util-class-loader",
        "com.jetbrains.intellij.platform:util-text-matching",
        "com.jetbrains.intellij.platform:util",
        "com.jetbrains.intellij.platform:util-base",
        "com.jetbrains.intellij.platform:util-coroutines",
        "com.jetbrains.intellij.platform:util-xml-dom",
        "com.jetbrains.intellij.platform:util-diff", // DocumentImpl edits need it; KSP never edits documents
        "com.jetbrains.intellij.platform:core",
        "com.jetbrains.intellij.platform:core-impl",
        "com.jetbrains.intellij.platform:extensions",
        "com.jetbrains.intellij.platform:diagnostic",
        "com.jetbrains.intellij.platform:diagnostic-telemetry",
        "com.jetbrains.intellij.java:java-frontback-psi",
        "com.jetbrains.intellij.java:java-frontback-psi-impl",
        "com.jetbrains.intellij.java:java-psi",
        "com.jetbrains.intellij.java:java-psi-impl",
    ).forEach { intellijOriginal("$it:$intellijVersion") }
    implementation(files(transformedIntellijDeps.map { it.outputDir.asFileTree }))

    listOf(
        "org.jetbrains.kotlin:analysis-api-k2-for-ide",
        "org.jetbrains.kotlin:analysis-api-for-ide",
        "org.jetbrains.kotlin:low-level-api-fir-for-ide",
        "org.jetbrains.kotlin:analysis-api-platform-interface-for-ide",
        "org.jetbrains.kotlin:symbol-light-classes-for-ide",
        "org.jetbrains.kotlin:analysis-api-standalone-for-ide",
        "org.jetbrains.kotlin:analysis-api-impl-base-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-common-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-fir-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-fe10-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-ir-for-ide",
    ).forEach { implementation("$it:$analysisApiVersion") { isTransitive = false } }
    listOf(
        // The IDE builds: Maven Central's plugin jars target the relocated (embeddable) compiler.
        "org.jetbrains.kotlin:compose-compiler-plugin-for-ide",
        "org.jetbrains.kotlin:kotlinx-serialization-compiler-plugin-for-ide",
        "org.jetbrains.kotlin:parcelize-compiler-plugin-for-ide",
    ).forEach { compilerPlugins("$it:$analysisApiVersion") }
    formatter(project(":formatter"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$analysisApiVersion")
    // Go to definition into a library without sources shows the class decompiled with bodies (Fernflower's fork).
    implementation("org.vineflower:vineflower:1.12.0")

    // Libraries the IntelliJ platform and the Analysis API need at runtime (versions as in KSP).
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.5.1") // github.com/JetBrains/kotlin/blob/v2.4.20/gradle/versions.properties
    implementation("com.google.guava:guava:33.2.0-jre")
    implementation("one.util:streamex:0.7.2")
    implementation("org.jetbrains.intellij.deps:asm-all:9.0")
    implementation("org.codehaus.woodstox:stax2-api:4.2.1") { isTransitive = false }
    implementation("com.fasterxml:aalto-xml:1.3.0") { isTransitive = false }
    implementation("com.github.ben-manes.caffeine:caffeine:2.9.3")
    implementation("org.jetbrains.intellij.deps.jna:jna:5.9.0.26") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps.jna:jna-platform:5.9.0.26") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps:trove4j:1.0.20200330") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps:log4j:1.2.17.2") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps:jdom:2.0.6") { isTransitive = false }
    implementation("javax.inject:javax.inject:1")
    implementation("org.lz4:lz4-java:1.7.1") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps.fastutil:intellij-deps-fastutil:8.5.14-jb1") { isTransitive = false }
    implementation("org.jetbrains:annotations:24.1.0")
    implementation("io.opentelemetry:opentelemetry-api:1.34.1") { isTransitive = false }

    implementation("org.vineflower:vineflower:1.11.1")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.rocksdb:rocksdbjni:10.0.1")
    implementation("org.gradle:gradle-tooling-api:9.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.17.0")
}

java {
    // Compiled by the JDK Gradle runs on (25), for Java 17: the server still runs on an older JDK.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach { options.release = 17 }

tasks.test {
    // Debug aid for LibraryResolution: pass -Plibres.modules=… -Plibres.file=… to run it on a real project.
    listOf("libres.modules", "libres.file").forEach { key -> project.findProperty(key)?.let { systemProperty(key, it) } }
    // Tests run from class folders, not the installed layout, so the bundled plugins are passed explicitly.
    systemProperty("kotlinlsp.compilerPlugins", compilerPlugins.asPath)
    systemProperty("kotlinlsp.formatter", formatter.asPath)
    val isCI: String? by project
    if (!isCI.isNullOrBlank()) {
        useJUnitPlatform {
            includeTags("CI")
        }
    } else {
        useJUnitPlatform()
    }

    forkEvery = 1
    maxParallelForks = 1
    timeout = Duration.ofMinutes(8)
    jvmArgs("-XX:+EnableDynamicAgentLoading")

    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        showStandardStreams = true

        events("passed", "skipped", "failed")
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Version" to project.version
        )
    }
}

distributions {
    main {
        contents {
            into("license") {
                from("../LICENSE")
                from("../NOTICE")
            }
            into("license/thirdparty") {
                from("../licenses")
            }
            into("plugins") {
                from(compilerPlugins)
            }
            into("formatter") {
                from(formatter)
            }
        }
    }
}
