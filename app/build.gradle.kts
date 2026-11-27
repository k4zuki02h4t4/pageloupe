import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The org.json version for the test-only jar, named in exactly
// one place: the dependency below and the provenance property
// the suites assert are both derived from it.
val orgJsonVersion = "20260814"

android {
    namespace = "jp.kaleidpixel.pageloupe"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "jp.kaleidpixel.pageloupe"
        minSdk = 33
        targetSdk = 34
        versionCode = 114
        versionName = "0.44.1"

        // Only the owner's device ABI ships (docs/dependencies.md):
        // the ML Kit AARs carry four ABIs of native engines, and the
        // other three are deliberately not packaged.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // The debug key the manual pipeline generated and every
        // installed build since v0.41.0 is signed with. Keeping the
        // same key is what lets a new build install over the old one
        // without wiping app data. The keystore file itself is never
        // committed (see .gitignore).
        create("pageloupeDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("pageloupeDebug")
            // The manual pipeline's aapt2 link never set
            // debuggable, and the shipped builds were not
            // debuggable (a fact the v0.43.0 review relied on).
            // AGP's debug build type defaults to debuggable=true;
            // turn it off so the Gradle build ships the same
            // posture. This build type is the delivery vehicle
            // (debug-key signed), not a development sandbox.
            isDebuggable = false
        }
        release {
            // Unused (delivery is the debug build type above) and
            // deliberately unsigned; it exists so AGP's variant
            // model stays standard. The manifest gate below covers
            // its packaging too, so it cannot ship unverified
            // either.
        }
    }

    // AGP otherwise embeds an encrypted inventory of the
    // dependency set into the APK signing block (a Play SDK-index
    // feature). PageLoupe is not distributed through Play and its
    // dependency set is already public in docs/dependencies.md,
    // so the embedded copy is pure surface: leave it out.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    sourceSets {
        // The JVM unit tests live at the repository root in
        // tests/ (they predate the Gradle build and are shared
        // documentation of the pure classes' contracts). They are
        // plain main() check runners, not JUnit; the runUnitTests
        // tasks at the bottom of this file execute them.
        getByName("test") {
            java.srcDir("../tests")
        }
    }
}

// Resolved only as a file for the unit-test JavaExec classpaths
// (see the unit test section at the bottom of this file). It is
// file-only input for those tasks, never an offered artifact.
val testJsonJar by configurations.creating {
    isCanBeConsumed = false
}

// The resolved dependency set is itself part of the approved
// posture: constraints in the dependencies block are floors, not
// pins, so the set that actually resolves is asserted against
// app/dependency-closure.txt, the same allowlist discipline as
// the manifest components. A deliberate dependency change updates
// the list in the same commit (docs/dependencies.md). The check
// runs BEFORE packaging (the package tasks depend on it), so a
// closure failure means no new APK is produced at all; an APK
// already on disk under its shippable name is always one that
// passed every gate when it was built.
// (Reading configurations inside doLast is not configuration-
// cache compatible; if that cache is ever enabled, this task
// needs to capture its inputs at configuration time instead.)
val verifyDependencyClosure = tasks.register("verifyDependencyClosure") {
    group = "verification"
    description = "Asserts the resolved runtime modules equal app/dependency-closure.txt."
    val closureFile = file("dependency-closure.txt")
    inputs.file(closureFile)
    outputs.upToDateWhen { false }
    doLast {
        val expected = closureFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSortedSet()
        // Both variants share one declaration today; both are
        // checked so a future variant-specific dependency cannot
        // slip past on the unchecked side.
        listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").forEach { configName ->
            val configuration = configurations.getByName(configName)
            val resolved = configuration
                .incoming.resolutionResult.allComponents
                // Module components only: the root project
                // component reports a module version too and is
                // not a dependency.
                .filter { it.id is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                .mapNotNull { it.moduleVersion }
                .map { "${it.group}:${it.name}:${it.version}" }
                .toSortedSet()
            if (resolved != expected) {
                throw GradleException(
                    "dependency closure mismatch in $configName" +
                        " (docs/dependencies.md)\nresolved only:\n" +
                        (resolved - expected).joinToString("\n") { "  $it" } +
                        "\nexpected only:\n" +
                        (expected - resolved).joinToString("\n") { "  $it" } +
                        "\nIf the change is deliberate, update" +
                        " app/dependency-closure.txt in the same commit.",
                )
            }
            // A file dependency (implementation(files(...)) and
            // friends) appears in neither the module graph above
            // nor the verification metadata, so it would bypass
            // both gates; refuse any artifact that did not come
            // from a module.
            val foreign = configuration.incoming.artifacts.artifacts
                .filter {
                    it.id.componentIdentifier !is
                        org.gradle.api.artifacts.component.ModuleComponentIdentifier
                }
                .map { it.file.name }
            if (foreign.isNotEmpty()) {
                throw GradleException(
                    "non-module artifacts on $configName bypass the" +
                        " dependency gates: ${foreign.joinToString(", ")}",
                )
            }
        }
    }
}

// The merged manifest is part of the security posture (the ML
// Kit components must be present with the right exported flags,
// the permissions and the manifest's structural inventory must
// stay the approved sets, the datatransport upload path must
// stay absent, and the APK must not be debuggable), so every
// packaged variant is asserted against
// app/manifest-components.txt, app/manifest-permissions.txt
// and app/manifest-structure.txt right after packaging — the
// gate the manual pipeline ran, on the package step itself,
// with the install tasks ordered after it. On any failure the
// verifier renames the APK to .rejected and the task fails, so
// a bad artifact never sits under its shippable name.
listOf("debug", "release").forEach { variant ->
    val capitalized = variant.replaceFirstChar { it.uppercase() }
    val verify = tasks.register("verifyShippedManifest$capitalized") {
        dependsOn("package$capitalized")
        val apkDir = layout.buildDirectory.dir("outputs/apk/$variant")
        val aapt2 = File(android.sdkDirectory, "build-tools/${android.buildToolsVersion}/aapt2")
        // The output directory is deliberately NOT declared
        // as an input: a renamed or missing output must reach
        // the task body (and its sweep), not fail input
        // validation beforehand — a declared directory input
        // does exactly that when the directory is absent. The
        // task always runs (upToDateWhen false below), and
        // the allowlists are its declared inputs.
        inputs.file(file("manifest-components.txt"))
        inputs.file(file("manifest-permissions.txt"))
        inputs.file(file("manifest-structure.txt"))
        outputs.upToDateWhen { false }
        doLast {
            val dir = apkDir.get().asFile
            // What AGP actually produced, per its own
            // metadata: the applicationId and the APK file
            // names. Nothing about the output is assumed from
            // this build script's configuration, so a future
            // change (signing config, applicationIdSuffix,
            // archivesName) is verified as-is, not as-expected.
            var failure: String? = null
            var listedApks = listOf<String>()
            var applicationId: String? = null
            val metadataFile = File(dir, "output-metadata.json")
            try {
                val metadata = metadataFile.readText()
                applicationId = Regex(""""applicationId"\s*:\s*"([^"]+)"""")
                    .find(metadata)?.groupValues?.get(1)
                listedApks = Regex(""""outputFile"\s*:\s*"([^"]+)"""")
                    .findAll(metadata).map { it.groupValues[1] }.toList()
                if (applicationId == null || listedApks.isEmpty()) {
                    failure = "output-metadata.json is incomplete"
                }
            } catch (e: Exception) {
                failure = "output-metadata.json unreadable: ${e.message}"
            }
            if (failure == null && listedApks.any {
                    it.contains('/') || it.contains('\\') || it.contains("..")
                }) {
                // A listed name that is not a plain file name
                // cannot be the APK AGP produced here; treat
                // the metadata as corrupt rather than letting
                // File(dir, name) wander outside the directory.
                failure = "output-metadata.json lists a non-plain" +
                    " file name: $listedApks"
            }
            if (failure == null) {
                // -I: isolated mode; the script's own
                // directory stays off sys.path, so a stray
                // module file in app/ cannot shadow the
                // standard library for the verifier.
                for (name in listedApks) {
                    var process: Process? = null
                    try {
                        process = ProcessBuilder(
                            "python3", "-I",
                            file("verify_manifest.py").absolutePath,
                            File(dir, name).absolutePath,
                            file("manifest-components.txt").absolutePath,
                            file("manifest-permissions.txt").absolutePath,
                            file("manifest-structure.txt").absolutePath,
                            aapt2.absolutePath,
                            applicationId!!,
                        ).redirectErrorStream(true).start()
                        // The verifier's own lines (its OK and
                        // its problem list) are the useful
                        // output; relay them through the task
                        // logger instead of inheriting the
                        // daemon's streams, where they would
                        // not reach the build log. The read
                        // runs on its own thread so a hung
                        // verifier (or aapt2) hits the deadline
                        // instead of hanging the build with an
                        // unverified APK under its final name.
                        val outputRef = AtomicReference<String?>(null)
                        val reader = Thread {
                            outputRef.set(process.inputStream
                                .bufferedReader().readText())
                        }
                        reader.isDaemon = true
                        reader.start()
                        reader.join(120_000)
                        if (reader.isAlive) {
                            // Kill the whole tree: the
                            // verifier's own child (aapt2) is
                            // the likeliest thing to be hung,
                            // and killing python alone would
                            // orphan it.
                            process.descendants().forEach { child ->
                                child.destroyForcibly()
                            }
                            process.destroyForcibly()
                            reader.join(5_000)
                            val partial = outputRef.get()
                            failure = "verifier timed out on $name" +
                                if (partial.isNullOrBlank()) "" else
                                    "; output so far: ${partial.trimEnd()}"
                        } else {
                            val output = outputRef.get() ?: ""
                            if (output.isNotBlank()) {
                                logger.lifecycle(output.trimEnd())
                            }
                            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                                process.descendants().forEach { child ->
                                    child.destroyForcibly()
                                }
                                process.destroyForcibly()
                                failure = "verifier did not exit on $name"
                            } else if (process.exitValue() != 0) {
                                failure = "verifier rejected $name" +
                                    " (exit ${process.exitValue()})"
                            }
                        }
                    } catch (e: InterruptedException) {
                        process?.descendants()?.forEach { child ->
                            child.destroyForcibly()
                        }
                        process?.destroyForcibly()
                        Thread.currentThread().interrupt()
                        failure = "verifier interrupted on $name"
                    } catch (e: Exception) {
                        failure = "verifier could not run: ${e.message}"
                    }
                    if (failure != null) {
                        break
                    }
                }
            }
            if (failure == null) {
                // Success means: the directory holds exactly
                // the APKs that were just verified, no more.
                val onDisk = dir.listFiles { f -> f.name.endsWith(".apk") }
                    ?.map { it.name }?.toSet() ?: emptySet()
                if (onDisk != listedApks.toSet()) {
                    failure = "unverified APKs in the output directory:" +
                        " on disk $onDisk, verified $listedApks"
                }
            }
            if (failure != null) {
                // Every failure path — the verifier's verdict,
                // its failure to launch, unreadable metadata, a
                // stray artifact — ends here: nothing keeps a
                // shippable name. Every file is attempted (a
                // failed rename falls back to deletion) before
                // anything is thrown, so one stubborn file
                // cannot leave the others behind, and the
                // report carries the original failure as well
                // as any sweep failures.
                val sweepFailures = mutableListOf<String>()
                dir.listFiles { f ->
                    f.name.endsWith(".apk") || f.name.endsWith(".apk.idsig")
                }?.forEach { f ->
                    if (!f.renameTo(File(f.path + ".rejected")) &&
                        !f.delete()
                    ) {
                        sweepFailures += f.name
                    }
                }
                throw GradleException(
                    "verifyShippedManifest$capitalized failed: $failure" +
                        if (sweepFailures.isEmpty()) "" else
                            "; could not clear: ${sweepFailures.joinToString(", ")}",
                )
            }
        }
    }
    tasks.configureEach {
        // configureEach, not tasks.named: at script evaluation
        // time AGP has not registered these tasks yet.
        if (name == "package$capitalized") {
            // The closure gate runs before packaging, and the
            // manifest gate right after it. The manifest
            // verifier's self-test also runs before packaging:
            // as a dependency of the finalizer it would only
            // be reached after the APK exists, and a failed
            // finalizer dependency leaves the packaged APK
            // under its shipped name with the sweep never run.
            dependsOn(verifyDependencyClosure)
            dependsOn("verifyManifestSelfTest")
            finalizedBy(verify)
        }
        if (name == "install$capitalized") {
            // A finalizer alone does not order verify before the
            // tasks that depend on the packaged APK; the install
            // tasks must wait for verification explicitly, or an
            // unverified APK could reach the device before the
            // gate fails the build.
            dependsOn(verify)
        }
    }
}

// No instrumented tests exist in this project (the suites are
// JVM unit tests, wired below). AGP's default androidTest
// component would otherwise add a second, ungated artifact: a
// test APK signed with the shipping key, in an output
// directory neither gate sweeps, plus connected* tasks that
// install to a device without going through install<Variant>
// (and its explicit verification dependency). Disable the
// component outright.
androidComponents {
    beforeVariants(selector().all()) { variant ->
        // Fail closed: if a future AGP changes the builder
        // API so this cast no longer holds, the build must
        // stop rather than silently shipping with the
        // androidTest component enabled. (The task-graph
        // refusal below is the second layer.)
        val builder = variant as? com.android.build.api.variant.HasAndroidTestBuilder
            ?: throw GradleException(
                "variant builder does not expose androidTest;" +
                    " cannot disable the androidTest component",
            )
        builder.androidTest.enable = false
    }
}

dependencies {
    // The only two owner-approved dependency exceptions, plus the
    // ML Kit language-id entry point (docs/dependencies.md records
    // the decisions, the exclusions and the telemetry analysis).
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    // In the vendored closure but not reachable through the
    // roots' Gradle metadata (language-id's metadata scopes its
    // common module away, and concurrent-futures' metadata does
    // not surface listenablefuture): both are referenced by the
    // shipped bytecode (jdeps-verified), so they are declared
    // explicitly to keep the resolved set equal to the closure.
    implementation("com.google.mlkit:language-id-common:16.1.0")
    implementation("com.google.guava:listenablefuture:1.0")

    // Compile-classpath declarations for the class-retention
    // annotation jars the libraries' POMs list with compile
    // scope. All three also reach the runtime classpath through
    // the libraries' own metadata and are dexed (measured in
    // the shipped APK's dex; an accepted difference from the
    // manual pipeline, which kept every one of them out of the
    // APK — docs/dependencies.md records it).
    compileOnly("androidx.annotation:annotation:1.8.1")
    compileOnly("org.jspecify:jspecify:1.0.0")
    compileOnly("com.google.firebase:firebase-annotations:16.0.0")

    // Test-only: the real org.json for the recorder/history
    // tests that exercise JSON code paths (android.jar's copy is
    // a stub). Resolved as a file for the JavaExec classpaths
    // below; it never enters any app or standard test classpath
    // configuration, and never the APK.
    add("testJsonJar", "org.json:json:$orgJsonVersion")

    constraints {
        // Floors for the whole transitive closure, set to the
        // versions the manual pipeline vendored and hash-verified.
        // A constraint alone does not pin (a higher transitive
        // request would win), so the resolved set is additionally
        // held exact by gradle/verification-metadata.xml (artifact
        // hashes) and by the verifyDependencyClosure task above
        // (the module set itself). Any version move is a
        // deliberate edit here, reviewed like a vendoring update
        // was.
        implementation("androidx.core:core:1.9.0")
        implementation("androidx.lifecycle:lifecycle-runtime:2.3.1")
        implementation("androidx.lifecycle:lifecycle-common:2.3.1")
        implementation("androidx.arch.core:core-runtime:2.1.0")
        implementation("androidx.arch.core:core-common:2.1.0")
        implementation("androidx.versionedparcelable:versionedparcelable:1.1.1")
        implementation("androidx.annotation:annotation:1.8.1")
        implementation("org.jspecify:jspecify:1.0.0")
        implementation("com.google.mlkit:common:18.11.0")
        implementation("com.google.mlkit:language-id-common:16.1.0")
        implementation("com.google.android.gms:play-services-base:18.5.0")
        implementation("com.google.android.gms:play-services-basement:18.4.0")
        implementation("com.google.android.gms:play-services-tasks:18.2.0")
        implementation("com.google.firebase:firebase-components:16.1.0")
        implementation("com.google.firebase:firebase-encoders:16.1.0")
        implementation("com.google.firebase:firebase-encoders-json:17.1.0")
        implementation("com.google.firebase:firebase-annotations:16.0.0")
        implementation("com.google.android.datatransport:transport-api:2.2.1")
        implementation("com.google.android.datatransport:transport-runtime:2.2.6")
        implementation("com.google.android.datatransport:transport-backend-cct:2.3.3")
        implementation("com.google.guava:listenablefuture:1.0")
        implementation("javax.inject:javax.inject:1")
        // Deviation from translate's POM (which declares okhttp
        // 3.0.0 with known CVEs): 3.12.13 is the final 3.12.x and
        // okio 1.17.6 fixes CVE-2023-3635. CVE-2021-0341 remains
        // open on 3.12.13 by necessity (4.x removed the
        // MediaType.parse API ML Kit calls); the full reasoning
        // and the run-3 narrowing are in docs/dependencies.md.
        implementation("com.squareup.okhttp3:okhttp:3.12.13")
        implementation("com.squareup.okio:okio:1.17.6")
        implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    }
}

configurations.all {
    // POM-reachable but deliberately not shipped (the manual
    // pipeline's exclusions, jdeps-verified; see
    // docs/dependencies.md): appcompat and its chain enter only
    // through mlkit:common's metadata, and the fragment chain
    // only through play-services-basement, where both serve
    // error-dialog helpers PageLoupe never invokes. The two
    // annotation modules are class-retention only and were never
    // on any classpath of the manual build.
    exclude(group = "androidx.appcompat")
    exclude(group = "androidx.fragment")
    exclude(group = "androidx.activity")
    exclude(group = "androidx.lifecycle", module = "lifecycle-livedata")
    exclude(group = "androidx.lifecycle", module = "lifecycle-livedata-core")
    exclude(group = "androidx.lifecycle", module = "lifecycle-viewmodel")
    exclude(group = "androidx.loader")
    exclude(group = "androidx.savedstate")
    exclude(group = "androidx.viewpager")
    exclude(group = "androidx.customview")
    exclude(group = "androidx.annotation", module = "annotation-experimental")

    resolutionStrategy {
        // The vendored collection is 1.0.0 (webkit's floor);
        // androidx.core 1.9.0's metadata asks for 1.1.0 and
        // Gradle's highest-wins would drift from the shipped,
        // device-verified set, so the vendored version is forced
        // (the one member of the closure a floor cannot hold).
        force("androidx.collection:collection:1.0.0")
    }
}

// ---------------------------------------------------------------------------
// JVM unit tests (tests/*.kt, plain main() check runners).
//
// The manual pipeline compiled the pure app classes plus these
// files with kotlinc and ran each main class on a hand-built
// classpath (tests/run_tests.sh, retired). The Gradle form runs
// each suite on AGP's debug unit-test runtime classpath plus the
// compiled class directories, with two manual-layout details
// preserved per suite:
//   - the suites the manual harness ran against the real
//     platform android.jar (the org.json suites, RecorderText,
//     ThumbnailStore, FaviconStore) get that jar appended last;
//     the other suites run with no android.jar at all, exactly
//     as before, so a pure class that starts touching Android
//     fails with NoClassDefFoundError instead of being masked by
//     a stub. (One recorded difference: the library jars are on
//     every suite's classpath now; the manual harness compiled
//     the pure classes alone. See REVIEW.md.)
//   - the org.json suites additionally get the real org.json jar
//     FIRST on the classpath (ahead of android.jar's stubs) and
//     the pageloupe.test.jsonJar property naming the jar they
//     must find themselves loaded from.
// A suite fails its task by exiting non-zero. runUnitTests
// depends on every suite; Gradle stops at the first failure
// unless invoked with --continue, which runs them all and
// reports every failure (the manual harness's behaviour).
// ---------------------------------------------------------------------------

// AGP registers the variant configurations (including this one)
// only in afterEvaluate, so everything below that touches it is
// itself inside afterEvaluate.
val unitTestRuntimeClasspath = providers.provider {
    configurations.getByName("debugUnitTestRuntimeClasspath")
}

data class UnitTestSuite(
    val suffix: String,
    val mainClassName: String,
    val needsJson: Boolean,
    val needsAndroidJar: Boolean,
)

// (task suffix, main class, needs the real org.json first,
// ran against the real android.jar in the manual harness)
val unitTestSuites = listOf(
    UnitTestSuite("WsMessageTap", "WsMessageTapTestKt", false, false),
    UnitTestSuite("UrlInput", "tests.UrlInputTestKt", false, false),
    UnitTestSuite("TargetSearch", "tests.TargetSearchTestKt", false, false),
    UnitTestSuite("DebugLogCounter", "tests.DebugLogCounterTestKt", false, false),
    UnitTestSuite("RecorderText", "tests.RecorderTextTestKt", false, true),
    UnitTestSuite("SecretProfilePolicy", "tests.SecretProfilePolicyTestKt", false, false),
    UnitTestSuite("ProfileNames", "tests.ProfileNamesTestKt", false, false),
    UnitTestSuite("DevToolsProfilePolicy", "tests.DevToolsProfilePolicyTestKt", false, false),
    UnitTestSuite("PageFeaturePolicy", "tests.PageFeaturePolicyTestKt", false, false),
    UnitTestSuite("PendingSlot", "tests.PendingSlotTestKt", false, false),
    UnitTestSuite("DevToolsSessionState", "tests.DevToolsSessionStateTestKt", false, false),
    UnitTestSuite("AtomicFileWrite", "tests.AtomicFileWriteTestKt", false, false),
    UnitTestSuite("RelayChunks", "tests.RelayChunksTestKt", false, false),
    UnitTestSuite("RecorderJson", "tests.RecorderJsonTestKt", true, true),
    UnitTestSuite("RecorderRequestsJson", "tests.RecorderRequestsJsonTestKt", true, true),
    UnitTestSuite("StartPageHtml", "tests.StartPageHtmlTestKt", false, false),
    UnitTestSuite("HistoryStore", "tests.HistoryStoreTestKt", true, true),
    UnitTestSuite("TabSessionStore", "tests.TabSessionStoreTestKt", true, true),
    UnitTestSuite("QueuedFileWriter", "tests.QueuedFileWriterTestKt", false, false),
    UnitTestSuite("ThumbnailStore", "tests.ThumbnailStoreTestKt", false, true),
    UnitTestSuite("ConsoleLogRecorderGate", "tests.ConsoleLogRecorderGateTestKt", true, true),
    UnitTestSuite("MenuSpec", "tests.MenuSpecTestKt", false, false),
    UnitTestSuite("ExportSelection", "tests.ExportSelectionTestKt", false, false),
    UnitTestSuite("PanelHeight", "tests.PanelHeightTestKt", false, false),
    UnitTestSuite("PopupProbeRegistry", "tests.PopupProbeRegistryTestKt", false, false),
    UnitTestSuite("DownloadInitiatorTracker", "tests.DownloadInitiatorTrackerTestKt", false, false),
    UnitTestSuite("CommitUrl", "tests.CommitUrlTestKt", false, false),
    UnitTestSuite("LoadStartLatch", "tests.LoadStartLatchTestKt", false, false),
    UnitTestSuite("ErrorPageTracker", "tests.ErrorPageTrackerTestKt", false, false),
    UnitTestSuite("NewDocumentLatch", "tests.NewDocumentLatchTestKt", false, false),
    UnitTestSuite("RecordedLogsPolicy", "tests.RecordedLogsPolicyTestKt", false, false),
    UnitTestSuite("ExportPolicy", "tests.ExportPolicyTestKt", false, false),
    UnitTestSuite("ExportMove", "tests.ExportMoveTestKt", false, false),
    UnitTestSuite("ShareText", "tests.ShareTextTestKt", false, false),
    UnitTestSuite("TitleAttribution", "tests.TitleAttributionTestKt", false, false),
    UnitTestSuite("HistoryTitle", "tests.HistoryTitleTestKt", false, false),
    UnitTestSuite("TranslatePlan", "tests.TranslatePlanTestKt", false, false),
    UnitTestSuite("FirstLoadGate", "tests.FirstLoadGateTestKt", false, false),
    UnitTestSuite("BookmarkStore", "tests.BookmarkStoreTestKt", true, true),
    UnitTestSuite("FaviconStore", "tests.FaviconStoreTestKt", false, true),
    UnitTestSuite("DevToolsProxyPolicy", "tests.DevToolsProxyPolicyTestKt", false, false),
)

// The suite list above is hand-maintained; this guard keeps it
// from silently drifting away from the files on disk (a new
// tests/*.kt with a main() that nobody registered would compile
// into the test source set and never run).
val verifyUnitTestSuiteList = tasks.register("verifyUnitTestSuiteList") {
    group = "verification"
    description = "Asserts every tests/*.kt main() is registered in unitTestSuites."
    val testsDir = rootProject.file("tests")
    inputs.dir(testsDir)
    outputs.upToDateWhen { false }
    doLast {
        // Recursive: the test source set compiles ../tests
        // recursively, so the guard must look everywhere the
        // compiler does, not just the top level.
        // Anchored at a line start (after indentation), so a
        // mention in a comment or a string does not register
        // as a suite entry point; annotations and the common
        // modifiers before `fun` are allowed, and spacing
        // variants are. (A @file:JvmName would change the
        // compiled class name away from the file-derived one
        // the guard compares against, so such a file shows up
        // as drift and fails here rather than passing
        // silently.)
        val mainRe = Regex(
            """(?m)^\s*(?:@\w+(?:\([^)]*\))?\s+)*""" +
                """(?:(?:public|private|internal|protected|suspend|inline)\s+)*""" +
                """fun\s+main\s*\(""",
        )
        val suiteFiles = testsDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val text = f.readText()
                if (!mainRe.containsMatchIn(text)) {
                    null
                } else {
                    val pkg = Regex("""(?m)^package\s+([\w.]+)""")
                        .find(text)?.groupValues?.get(1)
                    val cls = f.name.removeSuffix(".kt") + "Kt"
                    (if (pkg != null) "$pkg.$cls" else cls) to f
                }
            }
            .toMap()
        val onDisk = suiteFiles.keys.toSortedSet()
        val registered = unitTestSuites.map { it.mainClassName }.toSortedSet()
        if (onDisk != registered) {
            throw GradleException(
                "unit test suite list drift (app/build.gradle.kts)\n" +
                    "on disk only:\n" +
                    (onDisk - registered).joinToString("\n") { "  $it" } +
                    "\nregistered only:\n" +
                    (registered - onDisk).joinToString("\n") { "  $it" },
            )
        }
        // The needsJson flag has a one-way in-file witness: a
        // suite that checks its org.json provenance reads the
        // pageloupe.test.jsonJar property, and the property is
        // only set for needsJson suites — so a file that reads
        // it must be registered with needsJson=true. (The
        // reverse does not hold: three org.json suites use the
        // classes without asserting provenance. needsAndroidJar
        // has no in-file witness at all; it stays a reviewed
        // list entry, and a wrong value fails the suite loudly
        // in one direction and is a list-review matter in the
        // other.)
        unitTestSuites.forEach { suite ->
            val file = suiteFiles[suite.mainClassName]
            if (file != null &&
                file.readText().contains("pageloupe.test.jsonJar") &&
                !suite.needsJson
            ) {
                throw GradleException(
                    "unit test suite ${suite.suffix} reads the" +
                        " pageloupe.test.jsonJar property but is not" +
                        " registered with needsJson=true",
                )
            }
        }
    }
}

// AGP lint is disabled wholesale: the manual pipeline ran no
// lint, and this project's static review is the Claude Code
// review loop recorded in REVIEW.md, whose findings register
// is review/FINDINGS.md. AGP's default lint ruleset was never
// adopted, and its pre-existing findings would otherwise gate
// `check` and slow every assemble (lintVital). Re-enabling lint
// is a deliberate future decision, not a migration side effect.
// The disable matches AGP's lint implementation package: the
// lint task family spans several sibling classes in AGP 8.5.2
// (AndroidLintTask, AndroidLintGlobalTask,
// AndroidLintAnalysisTask, AndroidLintTextOutputTask,
// LintModelWriterTask), so no single type covers it. Matching
// the package rather than task names means an AGP rename of a
// lint task cannot silently re-enable it; if AGP ever moves
// the family out of this package, lint runs again and fails
// the build on the never-adopted findings — visible, not
// silent.
tasks.configureEach {
    if (this::class.java.name.startsWith(
            "com.android.build.gradle.internal.lint."
        )
    ) {
        enabled = false
    }
}

// Fail a bad dependency closure at the earliest anchor, not
// only when packaging is reached.
tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(verifyDependencyClosure)
    }
}

// The manifest verifier's own negative tests (synthetic
// xmltree fixtures for the parser and placement rules), saved
// next to the verifier and run as part of check and before
// every packaging, so the gate's regression suite is
// mechanical rather than a manual ritual after an AGP or
// verifier change.
val verifyManifestSelfTest = tasks.register<Exec>("verifyManifestSelfTest") {
    outputs.upToDateWhen { false }
    // A hung self-test must not hang every packaging with it;
    // the verifier task's own 120-second discipline, applied
    // to its health check too.
    timeout.set(Duration.ofMinutes(2))
    commandLine("python3", "-I", file("verify_manifest_test.py"))
}
tasks.configureEach {
    if (name == "check") {
        dependsOn(verifyManifestSelfTest)
    }
}

// This project ships APKs only. An AAB is built from the same
// runtime classpath and merged manifest but passes neither
// gate, and a debug AAB carries the same signing key — an
// unverified side door around both checks. The refusal is at
// the task-graph level, before anything executes: guarding
// the bundle<Variant> anchors alone would still let their
// dependencies (package<Variant>Bundle, sign<Variant>Bundle)
// produce a signed AAB first, and invoking those tasks
// directly would skip the guard entirely. The name pattern
// deliberately covers the producing and extracting tasks
// while leaving AGP's internal bundle*ClassesTo*Jar tasks
// (part of normal compilation) alone.
gradle.taskGraph.whenReady {
    val offender = allTasks.firstOrNull { task ->
        // bundle<Variant>, package<Variant>Bundle,
        // sign<Variant>Bundle, extractApksFromBundleFor* —
        // but not AGP's internal bundle*ClassesTo*Jar tasks.
        task.name.contains("undle") && !task.name.contains("ClassesTo")
    }
    if (offender != null) {
        throw GradleException(
            "bundle tasks are disabled (${offender.name}): PageLoupe" +
                " ships APKs only, and an AAB would bypass the" +
                " dependency and manifest gates (app/build.gradle.kts)",
        )
    }
    // Second layer for the androidTest disable above: if the
    // component ever comes back (an AGP change the cast somehow
    // survives, a future flavor re-enabling it), its tasks in
    // the graph are a build failure, not a silent artifact.
    val testOffender = allTasks.firstOrNull { task ->
        task.name.contains("AndroidTest")
    }
    if (testOffender != null) {
        throw GradleException(
            "androidTest tasks are disabled (${testOffender.name}):" +
                " the project has no instrumented tests, and a test" +
                " APK would bypass the gates (app/build.gradle.kts)",
        )
    }
}

afterEvaluate {
    // The unit-test runtime configuration carries the library
    // jars, but AGP adds the compiled class directories only to
    // its own Test tasks, so they are named here from the Kotlin
    // compile tasks. (AGP's mockable android.jar is NOT part of
    // the runtime configuration; the suites that need platform
    // stubs get the real platform jar below, as the manual
    // harness supplied it.)
    val appKotlinClasses = tasks
        .named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileDebugKotlin")
        .flatMap { it.destinationDirectory }
    val testKotlinClasses = tasks
        .named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileDebugUnitTestKotlin")
        .flatMap { it.destinationDirectory }
    val platformAndroidJar = providers.provider {
        File(android.sdkDirectory, "platforms/android-${android.compileSdk}/android.jar")
    }

    val runUnitTests = tasks.register("runUnitTests") {
        group = "verification"
        description = "Runs every JVM unit test suite in tests/."
        dependsOn(verifyUnitTestSuiteList)
    }
    // The suites are part of the standard verification lifecycle:
    // `gradle check` (and therefore `build`) runs them.
    tasks.named("check") { dependsOn(runUnitTests) }

    unitTestSuites.forEach { suite ->
        val task = tasks.register<JavaExec>("unitTest${suite.suffix}") {
            group = "verification"
            description = "Runs the ${suite.suffix} unit test suite."
            dependsOn("compileDebugUnitTestKotlin")
            val jars = files(
                testKotlinClasses, appKotlinClasses, unitTestRuntimeClasspath,
            )
            classpath = when {
                suite.needsJson -> files(testJsonJar) + jars + files(platformAndroidJar)
                suite.needsAndroidJar -> jars + files(platformAndroidJar)
                else -> jars
            }
            mainClass.set(suite.mainClassName)
            workingDir = rootProject.projectDir
            if (suite.needsJson) {
                // The suites assert their org.json classes loaded
                // from a jar with exactly this name (provenance
                // check carried over from the manual harness).
                systemProperty("pageloupe.test.jsonJar", "json-$orgJsonVersion.jar")
            }
        }
        runUnitTests.configure { dependsOn(task) }
    }
}

configurations.matching { it.name.endsWith("RuntimeClasspath") }.all {
    // org.jetbrains:annotations rides in as a kotlin-stdlib
    // transitive, but it is class-retention annotation metadata
    // the manual build never dexed. It is excluded from the
    // RUNTIME classpaths only: the Kotlin compiler itself needs
    // the jar on its own classpath (its codegen loads
    // org.jetbrains.annotations.NotNull), so a global exclude
    // breaks compilation with a NoClassDefFoundError.
    exclude(group = "org.jetbrains", module = "annotations")
}
