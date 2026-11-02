plugins {
    kotlin("jvm") version "2.1.20"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":submodule"))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

application {
    mainClass = "org.app.AppKt"
}
