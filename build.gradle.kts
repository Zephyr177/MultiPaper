import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import io.papermc.paperweight.core.tasks.RunNestedBuild

plugins {
    java // TODO java launcher tasks
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.21"
    // Master modules apply the shadow plugin without a version; declaring it
    // here (apply false) makes the version resolvable build-wide.
    // com.github.johnrengelman.shadow 8.x is incompatible with Gradle 9
    // (uses removed FileCopyDetails.mode); the maintained fork is
    // com.gradleup.shadow.
    id("com.gradleup.shadow") version "9.0.0" apply false
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
    val upstreamPaperDir = layout.projectDirectory.dir(".gradle/caches/paperweight/upstreams/paper")
    val nestedRootScript = upstreamPaperDir.file("build.gradle.kts").asFile

    // The nested build is run via Gradle's internal NestedRootBuildRunner,
    // which does not load init scripts, and its build scripts are
    // upstream-owned, so the only hook is to inject dependency wiring into
    // the purpur root script between the checkout and each nested run. The
    // marker keeps the injection idempotent; doLast removes it again so the
    // checkout output snapshot stays stable and the pipeline can go
    // UP-TO-DATE on the next run.
    fun injectNestedWiring() {
        val marker = "// MULTIPAPER-NESTED-WIRING-BEGIN"
        if (nestedRootScript.exists() && !nestedRootScript.readText().contains(marker)) {
            nestedRootScript.appendText(
                    "// MULTIPAPER-NESTED-WIRING-BEGIN\n" +
                    "// Configure :purpur-api before :purpur-server so the project\n" +
                    "// dependency (implementation(project(\":purpur-api\"))) has its\n" +
                    "// full variant metadata (api platform constraints such as\n" +
                    "// adventure-bom). With configuration caching, an unconfigured\n" +
                    "// project dependency resolves to an empty variant and\n" +
                    "// versionless dependencies like adventure-text-serializer-ansi\n" +
                    "// fail with 'Could not find ...:' on a cold cache (CI).\n" +
                    "subprojects {\n" +
                    "    if (name == \"purpur-server\") {\n" +
                    "        evaluationDependsOn(\":purpur-api\")\n" +
                    "    }\n" +
                    "}\n" +
                    "gradle.projectsEvaluated {\n" +
                    "    // Belt-and-braces: pin the adventure-bom (version read from\n" +
                    "    // purpur-api's adventureVersion) directly on :purpur-server so\n" +
                    "    // a broken project-dependency variant cannot leave adventure\n" +
                    "    // components versionless on a cold cache (CI).\n" +
                    "    // purpur-api/build.gradle.kts is itself generated by the nested\n" +
                    "    // pipeline (applyPurpurPaperApiFilePatches), so on a cold cache it\n" +
                    "    // does not exist during configuration; fall back to the version\n" +
                    "    // tracked here. Keep it in sync with purpur-api's adventureVersion\n" +
                    "    // when purpurRef changes.\n" +
                    "    val ansiVersion = try {\n" +
                    "        Regex(\"adventureVersion = \\\"([^\\\"]+)\\\"\").find(rootProject.file(\"purpur-api/build.gradle.kts\").readText())?.groupValues?.get(1)\n" +
                    "    } catch (e: Exception) {\n" +
                    "        null\n" +
                    "    } ?: \"5.2.0\"\n" +
                    "    project(\":purpur-server\").dependencies {\n" +
                    "        add(\"implementation\", platform(\"net.kyori:adventure-bom:\" + ansiVersion))\n" +
                    "    }\n" +
                    "    logger.lifecycle(\"[multipaper] pinned adventure-bom:\" + ansiVersion + \" on :purpur-server\")\n" +
                    "    project(\":purpur-server\").tasks.configureEach {\n" +
                    "        if (name != \"checkoutPaperRepo\") {\n" +
                    "            dependsOn(rootProject.tasks.named(\"checkoutPaperRepo\"))\n" +
                    "        }\n" +
                    "    }\n" +
                    "}\n" +
                    "// MULTIPAPER-NESTED-WIRING-END\n"
            )
            logger.lifecycle("[multipaper] injected nested-build dependency wiring into purpur build.gradle.kts")
        }
    }

    fun removeNestedWiring() {
        val markerBegin = "// MULTIPAPER-NESTED-WIRING-BEGIN"
        val markerEnd = "// MULTIPAPER-NESTED-WIRING-END"
        if (nestedRootScript.exists()) {
            val text = nestedRootScript.readText()
            val start = text.indexOf(markerBegin)
            val end = text.indexOf(markerEnd)
            if (start >= 0 && end > start) {
                nestedRootScript.writeText(text.removeRange(start, end + markerEnd.length))
                logger.lifecycle("[multipaper] removed nested-build wiring after applyUpstream")
            }
        }
    }

    val applyUpstream = tasks.named<RunNestedBuild>("applyUpstream")
    applyUpstream.configure {
        workDir.set(upstreamPaperDir.dir("nested-upstreams"))
        // The nested (Purpur) pipeline must also run its server-side patch
        // application: the api-only applyForDownstream leaves
        // <upstreams>/paper/paper-server empty, which starves our fork's
        // base tree (net.minecraft.* sources never materialize).
        tasks.addAll("purpur-server:applyAllServerPatches")

        // Gradle 9.4 fails the nested build: :paper:purpur-server's patch
        // tasks (applyPaperMinecraftResourcePatches & co) read
        // nested-upstreams/.../paper-server/patches -- the output of
        // :paper:checkoutPaperRepo -- without declaring the edge. The nested
        // build is run via Gradle's internal NestedRootBuildRunner, which
        // does not load init scripts (neither ~/.gradle/init.d nor -I), and
        // its build scripts are upstream-owned, so the only hook is to inject
        // dependency wiring into the purpur root script between the checkout
        // and the nested run. The checkout resets the script on every ref
        // change, and the marker keeps the injection idempotent.
        // purpur-server/build.gradle.kts is a generated artifact (patched
        // from paper's script) that Purpur does not track, so a cold checkout
        // has no tasks in :paper:purpur-server and nested task selection for
        // purpur-server:applyAllServerPatches fails. The nested build cannot
        // be asked to generate it first -- a second nested invocation of the
        // same directory collides with the included-build registry -- so seed
        // it from the frozen upstream artifact before the nested run
        // configures. Keep nested/purpur-server-build.gradle.kts in sync when
        // purpurRef changes (regenerate after a warm applyUpstream, then
        // copy the generated script into nested/).
        doFirst {
            val nestedServerScript = upstreamPaperDir.file("purpur-server/build.gradle.kts").asFile
            if (!nestedServerScript.exists()) {
                rootProject.layout.projectDirectory
                    .file("nested/purpur-server-build.gradle.kts").asFile
                    .copyTo(nestedServerScript)
                logger.lifecycle("[multipaper] seeded purpur-server/build.gradle.kts from frozen upstream artifact")
            }
        }
        doFirst { injectNestedWiring() }
        // The wiring lives in the checkout output; remove it again so
        // checkoutPaperRepo's output snapshot stays stable and the whole
        // pipeline can go UP-TO-DATE on the next run.
        doLast { removeNestedWiring() }
    }

    // The nested build's applyPurpurPaperApiPatches output is a git worktree,
    // so upstreams/paper/paper-api carries a .git of its own. The outer
    // filterPaperApiFromPaper copies that directory (including .git) and then
    // copies the upstream .git on top of it, which fails on Windows with
    // FileAlreadyExistsException (.git/config). The upstream-owned structure
    // keeps .git only at the checkout root. Stash the nested .git for the
    // duration of the task and restore it afterwards so the nested pipeline's
    // own consumers and UP-TO-DATE checks stay stable.
    // On a cold cache (CI) upstreams/paper/paper-api does not exist yet:
    // it is produced by the nested build's applyPurpurPaperApiPatches.
    // Without an explicit edge, Gradle schedules filterPaperApiFromPaper
    // before applyUpstream and fails input validation. Declare the order.
    tasks.named("filterPaperApiFromPaper") {
        dependsOn("applyUpstream")
        doFirst {
            val nestedApiGit = project.layout.projectDirectory
                .dir(".gradle/caches/paperweight/upstreams/paper/paper-api")
                .file(".git").asFile
            val stash = nestedApiGit.resolveSibling(".git.multipaper-bak")
            if (nestedApiGit.exists() && !stash.exists()) {
                check(nestedApiGit.renameTo(stash)) { "could not stash $nestedApiGit" }
                logger.lifecycle("[multipaper] stashed nested paper-api .git before filtering")
            }
        }
        doLast {
            val stash = project.layout.projectDirectory
                .dir(".gradle/caches/paperweight/upstreams/paper/paper-api")
                .file(".git.multipaper-bak").asFile
            if (stash.exists()) {
                val target = stash.resolveSibling(".git")
                check(stash.renameTo(target)) { "could not restore $stash" }
                logger.lifecycle("[multipaper] restored nested paper-api .git")
            }
        }
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
    // declaring the edge. Wire every JavaCompile (compileJava,
    // compileTestJava, compileLog4jPluginsJava) to the producing tasks.
    project(":multipaper-server").tasks.withType<JavaCompile>().configureEach {
        dependsOn(
            "applyMinecraftFeaturePatches",
            "applyPaperServerFilePatches",
            "applyPaperServerFeaturePatches",
        )
    }

    // The fork's patch tasks read the nested build's paper checkout under
    // <outer checkouts>/paper/nested-upstreams/paper/... -- a subtree Gradle
    // snapshots as output of the outer checkoutPaperRepo whenever that task
    // re-runs while the nested checkout already exists (e.g. cold-cache
    // simulation deleting a generated file inside the upstreams tree). The
    // paperweight-managed fork edges don't cover the redirected
    // source/resource/feature patch dirs, so declare the dependency
    // explicitly for every task that reads that subtree.
    listOf(
        "applyPaperServerFilePatches",
        "applyPaperServerFilePatchesFuzzy",
        "applyPaperServerFeaturePatches",
        "applyPaperMinecraftFeaturePatches",
        "applyPaperMinecraftFilePatches",
        "applyPaperMinecraftResourcePatches",
        "applyPaperMinecraftSourcePatches",
        "applyPaperMinecraftSourcePatchesFuzzy",
        "collectPaperATsFromPatches",
        "importPaperLibraryFiles",
    ).forEach { taskName ->
        project(":multipaper-server").tasks.named(taskName) {
            dependsOn(rootProject.tasks.named("checkoutPaperRepo"))
        }
    }
    // processResources reads paper-server/src/main/resources (output of
    // applyPaperServerFeaturePatches) and src/minecraft/resources (output
    // of applyMinecraftResourcePatches).
    project(":multipaper-server").tasks.named("processResources") {
        dependsOn("applyPaperServerFeaturePatches", "applyMinecraftResourcePatches")
    }

    // The fork's base tree is the *filtered* purpur-server output
    // (upstreams/paper/purpur-server/.gradle/caches/paperweight/taskCache/
    // filterPaperServerFromPaper), which paperweight leaves as a git
    // repository. The fork's filter copies it (including .git) and then
    // copies gitDir on top -> Windows FileAlreadyExistsException on
    // .git/config. The nested pipeline's own consumers still need that
    // .git afterwards, so stash it for the duration of the task and point
    // gitDir at the real upstream checkout .git (the outer purpur clone).
    project(":multipaper-server").tasks.withType<io.papermc.paperweight.core.tasks.FilterRepo>()
        .named("filterPaperServerFromPaper") {
        // The filter's input tree is the nested server pipeline's output
        // (upstreams/paper/purpur-server/.gradle/caches/paperweight/taskCache/
        // filterPaperServerFromPaper), produced by applyUpstream's nested run.
        dependsOn(rootProject.tasks.named("applyUpstream"))
        gitDir.set(project.rootProject.layout.projectDirectory.dir(".gradle/caches/paperweight/upstreams/paper/.git"))
        doFirst {
            val nestedGit = inputDir.get().asFile.resolve(".git")
            val stash = nestedGit.resolveSibling(".git.multipaper-bak")
            if (nestedGit.exists() && !stash.exists()) {
                check(nestedGit.renameTo(stash)) { "could not stash $nestedGit" }
                logger.lifecycle("[multipaper] stashed nested filter .git before filtering")
            }
        }
        doLast {
            val stash = inputDir.get().asFile.resolve(".git.multipaper-bak")
            if (stash.exists()) {
                val target = stash.resolveSibling(".git")
                check(stash.renameTo(target)) { "could not restore $stash" }
                logger.lifecycle("[multipaper] restored nested filter .git")
            }
        }
    }
}
