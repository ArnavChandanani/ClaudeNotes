pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // Official Onyx SDK repo. It is plain HTTP, so insecure protocol must be allowed.
        maven {
            url = uri("http://repo.boox.com/repository/maven-public/")
            isAllowInsecureProtocol = true
        }
    }
}

rootProject.name = "BooxNotes"
include(":app")
