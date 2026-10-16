pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "BmwEnetTest"
include(":app", ":core:data", ":core:diagnostics", ":feature:analysis", ":feature:benchmark")
