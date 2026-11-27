// Repository hygiene, in two layers.
//
// pluginManagement: content filters narrow which groups each
// repository is ASKED for. They are not a hard binding — the
// unfiltered Plugin Portal remains as the fallback for
// anything the first two do not serve (in practice, the
// plugin markers and parts of the plugins' own classpaths).
// The hard guarantee for the build classpath is the same as
// for the app: artifact hashes pinned in
// gradle/verification-metadata.xml.
//
// dependencyResolutionManagement: the shipped groups are bound
// to their home repository with exclusiveContent, so a shipped
// module can only ever be served from there. AGP's internal
// tooling configurations resolve through the same repository
// set and need groups no list here should have to enumerate
// (org.codehaus.groovy, com.google.jimfs, ...); exclusiveContent
// leaves those untouched, which a plain content allowlist did
// not (it broke lint's classpath resolution). The shipped set's
// integrity does not rest on this binding alone: hashes are
// pinned by the verification metadata and the resolved module
// set is asserted by verifyDependencyClosure
// (app/build.gradle.kts).
//
// The group patterns below are the single source for both the
// exclusiveContent filters and a coverage assertion: every
// group in app/dependency-closure.txt must match one side, so
// a future shipped group cannot silently fall through to the
// unfiltered repositories at the end of the list. (Exact group
// names need includeGroup: a regex like "com.google.firebase..*"
// requires a subgroup and does NOT match the group itself —
// that is how the firebase modules once slipped the binding.)
val shippedGoogleExact = setOf("com.google.mlkit", "com.google.firebase")
val shippedGoogleRegex = listOf(
    "androidx\\..*",
    "com\\.google\\.android\\..*",
    "com\\.google\\.mlkit\\..*",
    "com\\.google\\.firebase\\..*",
)
val shippedCentralExact = setOf(
    "org.jetbrains", "com.google.guava", "javax.inject",
    "org.json", "org.jspecify",
)
val shippedCentralRegex = listOf(
    "org\\.jetbrains\\..*",
    "com\\.squareup\\..*",
)

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral {
            content {
                includeGroup("org.jetbrains")
                includeGroupByRegex("org\\.jetbrains\\..*")
                // The Kotlin Gradle plugin's own classpath carries
                // the JUnit platform BOMs.
                includeGroupByRegex("org\\.junit.*")
            }
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { google() }
            filter {
                shippedGoogleExact.forEach { includeGroup(it) }
                shippedGoogleRegex.forEach { includeGroupByRegex(it) }
            }
        }
        exclusiveContent {
            forRepository { mavenCentral() }
            filter {
                shippedCentralExact.forEach { includeGroup(it) }
                shippedCentralRegex.forEach { includeGroupByRegex(it) }
            }
        }
        google()
        mavenCentral()
    }
}

// The assertion's scope is the runtime closure — the modules
// that ship. Compile-only and test-only groups (the annotation
// jars, org.json) are not in the closure file; they are pinned
// by the exact lists above by construction, and their hashes
// by the verification metadata.
val closureFile = File(rootDir, "app/dependency-closure.txt")
if (!closureFile.isFile) {
    throw GradleException(
        "app/dependency-closure.txt is missing; the repository" +
            " binding assertion has nothing to check against",
    )
}
val googleSide = { group: String ->
    group in shippedGoogleExact ||
        shippedGoogleRegex.any { group.matches(Regex(it)) }
}
val centralSide = { group: String ->
    group in shippedCentralExact ||
        shippedCentralRegex.any { group.matches(Regex(it)) }
}
val closureGroups = closureFile.readLines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .map { it.split(":")[0] }
    .toSet()
val uncovered = closureGroups.filterNot { googleSide(it) || centralSide(it) }
if (uncovered.isNotEmpty()) {
    throw GradleException(
        "shipped groups not bound to a home repository in" +
            " settings.gradle.kts: ${uncovered.sorted().joinToString(", ")}",
    )
}
val ambiguous = closureGroups.filter { googleSide(it) && centralSide(it) }
if (ambiguous.isNotEmpty()) {
    throw GradleException(
        "shipped groups claimed by both repositories in" +
            " settings.gradle.kts: ${ambiguous.sorted().joinToString(", ")}",
    )
}

rootProject.name = "PageLoupe"
include(":app")
