pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
    maven("https://repo.gradle.org/gradle/libs-releases")
  }
}

rootProject.name = "android-bridge-sync"
