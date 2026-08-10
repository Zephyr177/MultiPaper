import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import io.papermc.paperweight.core.tasks.RunNestedBuild

plugins {
    java // TODO java launcher tasks
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.21"
    // Master modules apply the shadow plugin without a version; declaring it
    // here (apply false) makes the version resolvable build-wide.
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

paperweight {
    // MultiPaper's direct upstream is Purpur. The upstream config MUST be named
    // "paper" (not "purpur") because paperweight-core hardcodes the parent
    // upstream checkout to <upstreams>/paper (CoreTasks.kt:151-152).
    upstreams.paper {
        repo.convention(github("PurpurMC", "Purpur"))
        ref = providers.gradleProperty("purpurRef")
        // Purpur is itself a fork of Paper; run its own pipeline inside the
        // checkout so purpur-server/build.gradle.kts + purpur-api/build.gradle.kts
        // are materialized before our patches apply.
        applyUpstreamNested.convention(true)

        patchFile {
            path = "purpur-server/build.gradle.kts"
            outputFile = file("multipaper-server/build.gradle.kts")
            patchFile = file("multipaper-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "purpur-api/build.gradle.kts"
            outputFile = file("multipaper-api/build.gradle.kts")
            patchFile = file("multipaper-api/build.gradle.kts.patch")
        }
        patchDir("paperApi") {
            upstreamPath = "paper-api"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("multipaper-api/paper-patches")
            outputDir = file("paper-api")
        }
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
        options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test> {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
    }
}

tasks.register("printMinecraftVersion") {
    doLast {
        println(providers.gradleProperty("mcVersion").get().trim())
    }
}

tasks.register("printMultiPaperVersion") {
    doLast {
        println(project.version)
    }
}

// The nested build (Purpur's own pipeline, run inside the purpur checkout) would
// otherwise check out Paper into <outer upstreams>/paper -- which IS its own
// project directory -- destroying the purpur working tree mid-build (paperweight
// 2.0.0-beta.21 fork-of-fork bug). Redirect the nested upstreams dir so Paper
// lands in a sibling directory.
afterEvaluate {
    tasks.named<RunNestedBuild>("applyUpstream") {
        workDir.set(layout.projectDirectory.dir(".gradle/caches/paperweight/upstreams/paper/nested-upstreams"))
        // The nested (Purpur) pipeline must also run its server-side patch
        // application: the api-only applyForDownstream leaves
        // <upstreams>/paper/paper-server empty, which starves our fork's
        // base tree (net.minecraft.* sources never materialize).
        tasks.addAll("purpur-server:applyAllServerPatches")
    }
}

// fork-of-fork: paper's own minecraft patches live in the nested build's paper
// checkout (nested-upstreams/paper/paper-server/patches). The fork project's
// root-level ("paper") minecraft pipeline defaults to rootDirectory/paper-server/
// patches -- the fork output, which has no patches -- so the pipeline would stay
// vanilla. Point it at the checkout, then the multipaper fork's own
// minecraft-patches (purpur's, inherited) apply on the paper-patched base.
gradle.projectsEvaluated {
    val forkCore = project(":multipaper-server").extensions.getByType(
        io.papermc.paperweight.core.extension.PaperweightCoreExtension::class.java
    )
    val paperPatches = project.layout.projectDirectory
        .dir(".gradle/caches/paperweight/upstreams/paper/nested-upstreams/paper/paper-server/patches")
    val paperBuildData = project.layout.projectDirectory
        .dir(".gradle/caches/paperweight/upstreams/paper/nested-upstreams/paper/build-data")
    forkCore.paper.run {
        sourcePatchDir.set(paperPatches.dir("sources"))
        resourcePatchDir.set(paperPatches.dir("resources"))
        featurePatchDir.set(paperPatches.dir("features"))
        // the fork's paper chain defaults to the purpur clone's build-data,
        // which has no paper.at -- without it the source tree misses ~302
        // access-transformer changes and the paper feature patches fail.
        additionalAts.set(paperBuildData.file("paper.at"))
        devImports.set(paperBuildData.file("dev-imports.txt"))
    }

    // Gradle 9 fails the build on implicit dependencies: the generated
    // build script's sourceSets point at patch-task outputs without
    // declaring the edge. Wire the compile to the producing tasks.
    project(":multipaper-server").tasks.named("compileJava") {
        dependsOn(
            "applyMinecraftFeaturePatches",
            "applyPaperServerFilePatches",
            "applyPaperServerFeaturePatches",
        )
    }
}
