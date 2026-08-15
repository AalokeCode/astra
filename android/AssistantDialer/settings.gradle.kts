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
        // Checked before the remote below, and restricted to the Linphone group
        // so it cannot shadow anything else. download.linphone.org serves the
        // 47 MB SDK at roughly 17 KB/s per connection (~45 minutes), so the AAR
        // is fetched once with parallel range requests, verified against the
        // publisher's SHA-256, and installed here. Delete
        // ~/.m2/repository/org/linphone to force a fresh download.
        mavenLocal {
            content {
                includeGroup("org.linphone.no-video")
            }
        }
        maven {
            name = "Linphone"
            url = uri("https://download.linphone.org/maven_repository")
            content {
                includeGroup("org.linphone.no-video")
            }
        }
    }
}

rootProject.name = "AssistantDialer"
include(":app")
