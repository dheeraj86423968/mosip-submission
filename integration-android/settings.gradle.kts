// Standalone build for integration-android, used to compile and unit-test vivid-android outside the
// registration client (see ../docs/testing/android-compile.md). Inside android-registration-client the
// module is included from ARC's own android/settings.gradle instead and this file is not used.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // io.mosip.vivid:* jars installed by `mvn install` in the monorepo root.
        mavenLocal {
            content { includeGroup("io.mosip.vivid") }
        }
        // Flutter embedding (io.flutter.*), the same repository the Flutter Gradle plugin adds in ARC.
        maven("https://storage.googleapis.com/download.flutter.io")
    }
}

rootProject.name = "integration-android"
include(":vivid-android")
