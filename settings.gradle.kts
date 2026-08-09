import java.util.Locale

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "multipaper"

for (name in listOf("multipaper-api", "multipaper-server", "MultiPaper-MasterMessagingProtocol", "MultiPaper-Master")) {
    val projName = name.lowercase(Locale.ENGLISH)
    include(projName)
    if (name != projName) {
        // Keep module dirs matching the historical casing (MultiPaper-*)
        findProject(":$projName")!!.projectDir = file(name)
    }
}