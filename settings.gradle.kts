pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android (USB OTG link to the pan-tilt microcontroller)
        maven("https://jitpack.io")
    }
}
rootProject.name = "Ampera"
include(":app")
