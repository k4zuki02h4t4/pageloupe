# Dependencies

PageLoupe's rule is no external dependencies (framework + Kotlin
stdlib only). The exceptions inside the APK, each decided by the
owner, are androidx.webkit (with its companion androidx.core),
decided 2026-11-20, and the ML Kit on-device translation stack,
decided 2026-11-27. This document is the record of those
decisions, of the exact artifact set that ships, and of the
deliberate exclusions. It replaces the vendored `app/libs/`
record kept while the build was a manual pipeline (until
v0.43.0); the set below is the same set, now declared in
`app/build.gradle.kts` and resolved by Gradle.

How the set is held exact:

- The three entry points are declared as `implementation`
  dependencies. `constraints` in `app/build.gradle.kts` set a
  floor at the version below for every member of the closure
  (and `force` holds the one member a floor cannot:
  androidx.collection), but a constraint alone is not a pin —
  so the resolved module set itself is asserted exactly
  against `app/dependency-closure.txt` by the
  `verifyDependencyClosure` Gradle task, and any version move
  is a deliberate, reviewed edit to both files.
- Every resolved artifact (these dependencies and the build
  plugins alike) is pinned by SHA-256 in
  `gradle/verification-metadata.xml`; a build whose downloads do
  not match fails. The metadata was generated on 2026-11-27
  from the resolved graph, and every artifact of the set below
  was cross-checked against the retired `app/libs/SHA256SUMS`
  before that file was removed — all matched. (The check
  detects corruption and mix-ups; it cannot detect a
  coordinated replacement of both an artifact source and the
  metadata, so changes to the metadata are eyeballed in diff
  review, as changes to SHA256SUMS were.)
- The exclusions below are `exclude` rules in
  `app/build.gradle.kts`, with the reasoning recorded here.

## Entry points

- `androidx.webkit:webkit:1.18.0` (Google Maven) — Google's
  official AndroidX library, actively maintained, and its
  multi-profile API is the only supported way to give secret
  tabs a browsing profile of their own. AAR SHA-256:
  f15a0bd7d51f4bb8dffdd3a2cf5829f9a7de9ad4ba2682fa85d619032050581f
  (cross-checked 2026-11-20 against Google Maven's published
  .sha1). License: Apache License 2.0.
- `com.google.mlkit:translate:17.0.3` (Google Maven) — the
  translation engine is bundled in the AAR
  (`jni/<abi>/libtranslate_jni.so` plus the models metadata).
  Language models themselves are downloaded per language at
  first use, with the user's consent.
- `com.google.mlkit:language-id:17.0.6` (Google Maven) —
  bundled variant: the TFLite language model
  (`assets/tflite_langid.tflite.jpg`) and
  `liblanguage_id_l2c_jni.so` ship in the AAR; no download.

Declared explicitly because the roots' Gradle metadata does not
surface them, although the shipped bytecode references them
(jdeps-verified, as is the whole closure):

- `com.google.mlkit:language-id-common:16.1.0` — language-id's
  metadata scopes its common module away from the runtime
  variant an app resolves.
- `com.google.guava:listenablefuture:1.0` — concurrent-futures'
  metadata does not surface it.

## The closure (all Google Maven unless marked *)

- com.google.mlkit:common 18.11.0 (AAR)
- com.google.android.gms:play-services-base 18.5.0 (AAR),
  play-services-basement 18.4.0 (AAR), play-services-tasks
  18.2.0 (AAR)
- com.google.firebase:firebase-components 16.1.0 (AAR),
  firebase-encoders 16.1.0 (jar), firebase-encoders-json
  17.1.0 (AAR)
- com.google.android.datatransport:transport-api 2.2.1 (AAR),
  transport-runtime 2.2.6 (AAR), transport-backend-cct 2.3.3
  (AAR)
- androidx.core:core 1.9.0 (AAR) — webkit's POM declares
  androidx.core as a compile dependency, and a jdeps scan of
  webkit's classes.jar shows exactly two core classes
  referenced at runtime:
  `androidx.core.content.pm.PackageInfoCompat` and
  `androidx.core.util.Pair`. The ML Kit stack declares a floor
  of 1.9.0 (play-services-base wants 1.2.0, mlkit-common 1.9.0),
  and two versions of one library cannot coexist in the dex, so
  core sits at 1.9.0 — a single version satisfying every
  consumer. AAR SHA-256:
  8bda3ee3a88887d54f6679fb6b6cd788629f73234ac91c8bbed924e721ec85b8
  (cross-checked 2026-11-27 against Google Maven's .sha1).
  License: Apache License 2.0.
- androidx.collection:collection 1.0.0 (jar) — forced to
  webkit's floor: androidx.core 1.9.0's metadata asks for
  1.1.0 and Gradle's highest-wins would drift from the
  shipped, device-verified set (`resolutionStrategy.force` in
  the build file).
- androidx.concurrent:concurrent-futures 1.0.0 (jar),
  androidx.lifecycle:lifecycle-runtime 2.3.1 (AAR),
  androidx.lifecycle:lifecycle-common 2.3.1 (jar),
  androidx.arch.core:core-runtime 2.1.0 (AAR),
  androidx.arch.core:core-common 2.1.0 (jar),
  androidx.versionedparcelable:versionedparcelable 1.1.1 (AAR)
- javax.inject:javax.inject 1 (jar) *
- com.squareup.okhttp3:okhttp 3.12.13 (jar),
  com.squareup.okio:okio 1.17.6 (jar) — DEVIATION from the POM:
  translate's POM declares okhttp 3.0.0, which carries known
  CVEs. 3.12.13 (2021-01-30) is the final 3.12.x and clears the
  pre-2021 advisories; okio 1.17.6 is the 1.x-line fix for
  CVE-2023-3635. One advisory remains open against 3.12.13 and
  is recorded here rather than smoothed over: CVE-2021-0341
  (hostname verification) was fixed only in OkHttp 4.9.2, and
  moving to 4.x is not available — ML Kit's model downloader
  (zztz, the ONLY okhttp consumer in the set) calls
  `okhttp3.MediaType.parse`, which OkHttp 4 removed
  (bytecode-verified), so a 4.x jar would break model downloads
  with NoSuchMethodError. Residual exposure: a network MITM
  presenting a crafted certificate for Google's model servers.
  One part of the original concern has since been closed by
  direct evidence (security-audit run-3, 2026-11-27): the model
  files ML Kit fetches at runtime ARE verified beyond TLS —
  each downloaded model is checked against the SHA-256 recorded
  in the metadata bundled in the APK
  (translate_models_metadata.json) and a mismatch is refused,
  so a MITM cannot substitute a model. What remains of the
  residual is observation (which languages a user fetches) and
  interference (blocking or corrupting a download, which then
  fails the hash check and surfaces as a failed translation).
  Caveat: the audit found no path by which a server-pushed
  configuration (rapid response / remote config) replaces the
  bundled metadata in these versions, but that negative result
  rests on obfuscated code — re-check it whenever the ML Kit
  artifacts are updated.
- org.jetbrains.kotlin:kotlin-stdlib 2.0.21 — the build's own
  stdlib is the single one dexed; older copies inside the
  libraries' trees are not separate artifacts under Gradle
  resolution.

## Compile-only (never intended for the APK)

Annotation jars the POMs list with compile scope; their
annotations are class-retention and nothing references them at
runtime. They are declared `compileOnly` in the build file.

- androidx.annotation:annotation 1.8.1 — the Android variant
  artifact Gradle resolves is `annotation-metadata-1.8.1.jar`,
  byte-identical to the plain jar the manual pipeline vendored
  (SHA-256 fa5e67ffcc52a0413f4b39c2737b43c1ba6c6975b4bd4a4ea8627549e766285b,
  cross-checked 2026-11-20 against Google Maven's .sha1).
- org.jspecify:jspecify 1.0.0 * — SHA-256:
  1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
  (cross-checked 2026-11-20 against Maven Central's .sha1).
- com.google.firebase:firebase-annotations 16.0.0 — SHA-256:
  8830f2e8a5e434cbc5e4bd18133073b6e8518a8ce8ec2ce6f3b273c91d9664c2
  (cross-checked 2026-11-27 against Google Maven's .sha1).

One recorded difference from the manual pipeline: the library
metadata also places all three of these jars on the runtime
classpath, so their (class-retention, never instantiated)
classes are dexed into the APK (jspecify's presence in the dex
was measured directly, 2026-11-27). The manual build kept
them out entirely. The difference is inert — no code path can
observe it — and is accepted rather than fought with
per-carrier excludes. `org.jetbrains:annotations` (a stdlib
transitive of the same kind) IS excluded from the runtime
classpaths, but only there: the Kotlin compiler's own codegen
loads `org.jetbrains.annotations.NotNull`, so excluding it
globally breaks compilation.

## Exclusions (POM-reachable but deliberately not shipped)

Enforced by `exclude` rules in `app/build.gradle.kts`:

- androidx.appcompat and its chain: nothing in the set
  references appcompat classes (jdeps-verified); it enters only
  through mlkit:common's Gradle metadata.
- androidx.fragment / androidx.activity / lifecycle-livedata /
  lifecycle-livedata-core / lifecycle-viewmodel / loader /
  viewpager / savedstate / customview: referenced only by
  play-services-basement's and play-services-base's
  error-dialog helpers (SupportErrorDialogFragment and the
  ActivityResultLauncher-based error APIs), which enter through
  play-services-basement's metadata. PageLoupe never invokes
  them — ML Kit reports failures as exceptions, which the app
  surfaces itself — and fragment would drag the rest in for
  that dead path alone.
- androidx.annotation:annotation-experimental and (runtime only)
  org.jetbrains:annotations: class-retention annotation
  metadata, never on any classpath of the manual build.

## Manifest merge

AGP merges the AAR manifests. What arrives that way, identical
to the hand-merge the manual pipeline maintained: androidx.core
1.9.0's `android:appComponentFactory`
(`androidx.core.app.CoreComponentFactory`) and its
DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION signature permission
pair (the `${applicationId}` placeholder resolved by the
merger), the `com.google.android.gms.version` meta-data,
GoogleApiActivity, MlKitInitProvider, and
MlKitComponentDiscoveryService with the four component
registrars (common, translate, language-id common, language-id
bundled).

Deliberately NOT merged, removed in the app manifest with
`tools:node="remove"`: the datatransport components
TransportBackendDiscovery (with its `backend:` meta-data),
JobInfoSchedulerService and
AlarmManagerSchedulerBroadcastReceiver. The shipped manifest
as a whole is pinned by three allowlists and asserted after
every packaging by the `verifyShippedManifest*` Gradle tasks
(`app/verify_manifest.py`): the component set with its
exported flags (`app/manifest-components.txt`), the
uses-permission set (`app/manifest-permissions.txt`), and the
structural inventory — element tags, application attribute
names, meta-data names, intent actions and categories
(`app/manifest-structure.txt`) — plus the application
posture values the verifier itself holds (no debuggable or
testOnly, allowBackup, usesCleartextTraffic,
extractNativeLibs, appComponentFactory).

## Telemetry: two distinct paths, stated precisely

1. datatransport / CCT diagnostics: ML Kit logs diagnostic
   events through datatransport's CCT backend (its classes
   reference CCTDestination directly, so the backend jar stays
   linked). The upload pipeline, however, only runs through
   components declared in the datatransport AAR manifests —
   TransportBackendDiscovery, JobInfoSchedulerService and
   AlarmManagerSchedulerBroadcastReceiver — and NONE of those
   are merged into the app manifest (see above); the shipped
   manifest is asserted after every build. Without backend
   discovery and the schedulers, queued events have no upload
   path; the local event store is size-bounded by the library
   itself. Note the store is on-device persistence: event
   metadata (language codes among them) can remain in it after
   the fact, including events from secret-tab translations —
   part of the v0.41.0 owner disclosure. The often-cited
   `firebase_data_collection_default_enabled` meta-data is NOT
   a substitute: it is honored only by FirebaseInitProvider,
   which standalone ML Kit does not register.
2. Play services API telemetry: translate's and
   language-id-common's bytecode also reference
   `com.google.android.gms.common.internal.TelemetryLogging{,Client,Options}`
   (jdeps-verified). That path reports API usage through the
   Play services process by IPC; it cannot be disarmed from the
   app manifest. What it carries is API-usage metadata by
   design, not page content (page text is processed on-device
   and is never an input to either path) — but the earlier
   blanket claim that "nothing leaves the device" was wrong for
   this path and is corrected here; it was disclosed to the
   owner in the v0.41.0 report.

Model downloads: cancelling the progress dialog cancels the
app-side wait, but a download already handed to ML Kit / Play
services may still complete in the background; the model then
simply sits in the on-device inventory for later use.

## ABI restriction, native packaging, resources

Only arm64-v8a native libraries are packed
(`ndk.abiFilters` in the build file; the owner's device ABI).
The AARs' other ABIs (armeabi-v7a, x86, x86_64) are
deliberately not shipped; on any other ABI the translation
feature would fail to load its engine while the rest of the app
is unaffected in structure (the feature is reachable only from
the menu). `android:extractNativeLibs` is false: the .so files
are stored uncompressed and 4 KB page-aligned, so the platform
maps them in place instead of extracting a second copy at
install time. Caveat: 16 KB page-size devices would need 16
KB-aligned libraries; the libraries are not verified for that,
and the owner's device uses 4 KB pages.

Library resources and R classes are merged and generated by
AGP (the manual pipeline's aapt2 link plus generated R classes
were the hand equivalent). The AARs carrying a res/ tree are
core, play-services-base, play-services-basement,
lifecycle-runtime and mlkit-translate.

## Test-only: org.json (never in the APK)

The JVM unit tests (tests/, run by the `runUnitTests` Gradle
task) use JSON-java, the org.json reference implementation
(https://github.com/stleary/JSON-java), resolved from Maven
Central as `org.json:json:20260814` into a file-only
configuration in `app/build.gradle.kts`. The app itself uses
the org.json built into Android; on the JVM, android.jar's
org.json is a stub that throws at runtime, so the recorder's
CDP ingestion and console JSON export could not be tested at
all without a working implementation. (The requests export,
writeRequestsJson, uses android.util.JsonWriter and stays
outside JVM coverage.) The jar is placed first on the classpath
of the suites that need it only, and is pinned like everything
else by `gradle/verification-metadata.xml`.

- Why this version: 20260814 was the latest stable release at
  adoption (2026-11-21) and includes the fixes for
  CVE-2023-5072 and CVE-2026-59171. Versions before 20231013
  are avoided (known DoS issues).
- SHA-256: 7dd2de1533e766e2b6c07d8194da85145ad313a167a4760050f5c9b6839604fe;
  Maven Central .sha1 at adoption:
  a1d979d4b336516526d00ff423e4a8d4ba27670f (matched).
- License: the jar's POM declares Public Domain; the LICENSE
  it links to is the JSON License text (which carries the
  "Good, not Evil" clause). The jar is used only inside this
  project's own test runs and is not redistributed; revisit the
  license if that ever changes.
- Adoption record (2026-11-21): downloaded over HTTPS from
  Maven Central; the published .sha1 matched the file. A .asc
  signature exists (Ed25519, key ID FD116C1969FCCFF3), but the
  signing key could not be retrieved from the public keyservers
  reached at adoption, so the signature is NOT verified —
  provenance rests on the HTTPS source and the .sha1 match.
  The known vulnerability history is tracked upstream (e.g.
  CVE-2026-59171, GHSA-ghc9-756g-vrcv, fixed in 20260719;
  checked against the GitHub Advisory Database entry on
  2026-11-21).

Scope limits (owner decision, 2026-11-21, ledger SEN-19):
JSON-java and Android's org.json diverge in edge behavior —
key order in toString(), optString for a JSON null, duplicate
keys, nesting-depth limits, number formatting. Inputs
containing JSON null are likewise not asserted on the JVM.
Tests running against this jar assert the recorder's own logic
(structure and values) only; byte-level output equality stays
verified on device, as before. One deliberate exception to the
JSON-null rule: a null ELEMENT inside the console args array.
Both implementations' optJSONObject returns null for a
non-object element (a type check, not the divergent optString
behavior), and the recorder's own stringifyArg maps a null
argument to "undefined" — that much is asserted.
