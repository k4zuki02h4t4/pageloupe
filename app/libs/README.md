# Vendored libraries

PageLoupe's rule is no external dependencies (framework + Kotlin
stdlib only). The exceptions INSIDE THE APK, each decided by the
owner, are androidx.webkit (with its companion androidx.core),
decided 2026-11-20: it is Google's official AndroidX library,
actively maintained, and its multi-profile API is the only
supported way to give secret tabs a browsing profile of their own;
and the ML Kit on-device translation stack, decided 2026-11-27
(see the ML Kit section below for its full inventory and the
deliberate exclusions). (A separate test-only jar lives in
tests/lib and never enters the APK; see tests/lib/README.md.)

## androidx.webkit 1.18.0 (runtime)

- Source: https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/1.18.0/webkit-1.18.0.aar
- AAR SHA-256: f15a0bd7d51f4bb8dffdd3a2cf5829f9a7de9ad4ba2682fa85d619032050581f
  (cross-checked 2026-11-20: the local file's SHA-1 matches the
  .sha1 checksum Google Maven publishes for this artifact)
- `webkit-classes.jar` is the AAR's `classes.jar`, extracted
  unchanged. SHA-256: 2b8112e33c3d5a801628a59948a700875d52e368ac9ca0781a1c2bf295a836a2
- License: Apache License 2.0 (see the AAR's META-INF).

## androidx.core 1.9.0 (runtime)

webkit's POM declares androidx.core as a compile dependency, and a
jdeps scan of webkit's classes.jar shows exactly two core classes
referenced at runtime: `androidx.core.content.pm.PackageInfoCompat`
and `androidx.core.util.Pair`. Up to v0.40.0 the POM's floor
version 1.1.0 was vendored to satisfy them. The ML Kit stack
(v0.41.0) declares a floor of 1.9.0 (play-services-base wants
1.2.0, mlkit-common 1.9.0), and two versions of one library cannot
coexist in the dex, so core was raised to 1.9.0 -- a single
version satisfying every consumer, as a Gradle resolution would
pick. webkit's two referenced classes exist unchanged in 1.9.0.

- Source: https://dl.google.com/dl/android/maven2/androidx/core/core/1.9.0/core-1.9.0.aar
- AAR SHA-256: 8bda3ee3a88887d54f6679fb6b6cd788629f73234ac91c8bbed924e721ec85b8
  (cross-checked 2026-11-27 against Google Maven's published
  .sha1, same as above)
- `core-classes.jar` is the AAR's `classes.jar`, extracted
  unchanged. SHA-256: 8a0ecdeb687d2127c412b20683888258995367f579e050000bbfaa646b578ee7
- License: Apache License 2.0.
- The 1.9.0 manifest contributions are merged by hand in the app
  manifest: `android:appComponentFactory` =
  `androidx.core.app.CoreComponentFactory` and the
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` signature permission
  pair (its `${applicationId}` placeholder resolved).

## Compile-only (never dexed into the APK)

Annotation jars webkit's POM lists with compile scope. They are on
the kotlinc classpath only; their annotations are class-retention
and nothing references them at runtime.

- androidx.annotation:annotation 1.8.1 —
  https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.8.1/annotation-1.8.1.jar
  SHA-256: fa5e67ffcc52a0413f4b39c2737b43c1ba6c6975b4bd4a4ea8627549e766285b
  (cross-checked 2026-11-20 against Google Maven's published .sha1)
- org.jspecify:jspecify 1.0.0 —
  https://repo1.maven.org/maven2/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar
  SHA-256: 1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
  (cross-checked 2026-11-20 against Maven Central's published .sha1)
- com.google.firebase:firebase-annotations 16.0.0 —
  https://dl.google.com/dl/android/maven2/com/google/firebase/firebase-annotations/16.0.0/firebase-annotations-16.0.0.jar
  SHA-256: 8830f2e8a5e434cbc5e4bd18133073b6e8518a8ce8ec2ce6f3b273c91d9664c2
  (cross-checked 2026-11-27 against Google Maven's published
  .sha1). Annotation types the ML Kit closure references;
  class-retention, never dexed.

build.sh additionally verifies at build time that every
`*-classes.jar` file is byte-identical to the `classes.jar`
inside its hash-verified AAR (the webkit and core pairs keep
their historical names, `webkit-classes.jar` and
`core-classes.jar`).

## ML Kit on-device translation (runtime, v0.41.0)

Owner decision 2026-11-27: page translation runs on-device with
ML Kit, accepting this dependency set at its measured scale. The
set below is the POM closure of the two entry artifacts,
verified with a jdeps reachability scan over the vendored jars
(the same discipline as the webkit/core pair): every class
reference in the set resolves inside it, except annotation-only
references and the Play Services dialog helpers noted under
Exclusions. All artifacts were cross-checked on 2026-11-27
against the publisher's own published .sha1 (Google Maven, or
Maven Central where marked *). Per-file SHA-256 values are in
SHA256SUMS, which build.sh verifies on every build; each AAR's
`<name>-classes.jar` is its `classes.jar`, extracted unchanged
and re-verified against the AAR at build time.

Entry points (Google Maven):
- com.google.mlkit:translate 17.0.3 (AAR) -- the translation
  engine is bundled: `jni/<abi>/libtranslate_jni.so` plus the
  models metadata. Language models themselves are downloaded
  per language at first use (with the user's consent).
- com.google.mlkit:language-id 17.0.6 (AAR) -- bundled variant:
  the TFLite language model (`assets/tflite_langid.tflite.jpg`)
  and `liblanguage_id_l2c_jni.so` ship in the AAR; no download.

Closure (Google Maven unless marked):
- com.google.mlkit:common 18.11.0 (AAR), com.google.mlkit:language-id-common 16.1.0 (AAR)
- com.google.android.gms:play-services-base 18.5.0 (AAR),
  play-services-basement 18.4.0 (AAR), play-services-tasks 18.2.0 (AAR)
- com.google.firebase:firebase-components 16.1.0 (AAR),
  firebase-encoders 16.1.0 (jar), firebase-encoders-json 17.1.0 (AAR)
- com.google.android.datatransport:transport-api 2.2.1 (AAR),
  transport-runtime 2.2.6 (AAR), transport-backend-cct 2.3.3 (AAR)
- androidx.core:core 1.9.0 (see its own section above),
  androidx.collection:collection 1.0.0 (jar),
  androidx.concurrent:concurrent-futures 1.0.0 (jar),
  androidx.lifecycle:lifecycle-runtime 2.3.1 (AAR),
  androidx.lifecycle:lifecycle-common 2.3.1 (jar),
  androidx.arch.core:core-runtime 2.1.0 (AAR),
  androidx.arch.core:core-common 2.1.0 (jar),
  androidx.versionedparcelable:versionedparcelable 1.1.1 (AAR)
- com.google.guava:listenablefuture 1.0 (jar) *,
  javax.inject:javax.inject 1 (jar) *
- com.squareup.okhttp3:okhttp 3.12.13 (jar),
  com.squareup.okio:okio 1.17.6 (jar) -- DEVIATION from the POM
  (v0.41.0 review r1/r2): translate's POM declares okhttp
  3.0.0, which carries known CVEs. 3.12.13 (2021-01-30) is
  the final 3.12.x and clears the pre-2021 advisories; okio
  1.17.6 is the 1.x-line fix for CVE-2023-3635. One advisory
  remains open against 3.12.13 and is recorded here rather
  than smoothed over: CVE-2021-0341 (hostname verification)
  was fixed only in OkHttp 4.9.2, and moving to 4.x is not
  available -- ML Kit's model downloader (zztz, the ONLY
  okhttp consumer in the set) calls
  `okhttp3.MediaType.parse`, which OkHttp 4 removed
  (bytecode-verified), so a 4.x jar would break model
  downloads with NoSuchMethodError. Residual exposure: a
  network MITM presenting a crafted certificate for Google's
  model servers. One part of the original concern has since
  been closed by direct evidence (security-audit run-3,
  2026-11-27): the model files ML Kit fetches at runtime ARE
  verified beyond TLS — each downloaded model is checked
  against the SHA-256 recorded in the metadata bundled in
  the APK (translate_models_metadata.json) and a mismatch is
  refused, so a MITM cannot substitute a model. What remains
  of the residual is observation (which languages a user
  fetches) and interference (blocking or corrupting a
  download, which then fails the hash check and surfaces as
  a failed translation). Caveat: the audit found no path by
  which a server-pushed configuration (rapid response /
  remote config) replaces the bundled metadata in these
  versions, but that negative result rests on obfuscated
  code — re-check it whenever the ML Kit artifacts are
  updated.

Manifest merge (by hand in src/main/AndroidManifest.xml, since
this build has no Gradle merger): the gms.version meta-data,
MlKitInitProvider, MlKitComponentDiscoveryService with the four
component registrars (common, translate, language-id common,
language-id bundled), and GoogleApiActivity.

Telemetry: two distinct paths, stated precisely
(v0.41.0 review r1).

1. datatransport / CCT diagnostics: ML Kit logs diagnostic
events through datatransport's CCT backend (its classes
reference CCTDestination directly, so the backend jar stays
linked). The upload pipeline, however, only runs through
components declared in the datatransport AAR manifests --
TransportBackendDiscovery (with its `backend:` meta-data),
JobInfoSchedulerService and AlarmManagerSchedulerBroadcastReceiver
-- and NONE of those are merged into the app manifest;
build.sh asserts their absence (and the ML Kit components'
presence) in the shipped APK's manifest. Without backend
discovery and the schedulers, queued events have no upload
path; the local event store is size-bounded by the library
itself. Note the store is on-device persistence: event
metadata (language codes among them) can remain in it after
the fact, including events from secret-tab translations --
part of the v0.41.0 owner disclosure. The often-cited
`firebase_data_collection_default_enabled` meta-data is NOT
a substitute: it is honored only by FirebaseInitProvider,
which standalone ML Kit does not register.

2. Play services API telemetry: translate's and
language-id-common's bytecode also reference
`com.google.android.gms.common.internal.TelemetryLogging{,Client,Options}`
(jdeps-verified). That path reports API usage through the Play
services process by IPC; it cannot be disarmed from the app
manifest. What it carries is API-usage metadata by design, not
page content (page text is processed on-device and is never an
input to either path) -- but the earlier blanket claim that
"nothing leaves the device" was wrong for this path and is
corrected here; it was disclosed to the owner in the v0.41.0
report.

Model downloads: cancelling the progress dialog cancels the
app-side wait, but a download already handed to ML Kit /
Play services may still complete in the background; the model
then simply sits in the on-device inventory for later use.

ABI restriction: only arm64-v8a native libraries are packed
(the owner's device ABI). The AARs' other ABIs (armeabi-v7a,
x86, x86_64) are deliberately not shipped; on any other ABI the
translation feature would fail to load its engine while the
rest of the app is unaffected in structure (the feature is
reachable only from the menu). `android:extractNativeLibs` is
false (v0.41.0 review r1): the .so files are stored uncompressed
and 4 KB page-aligned, so the platform maps them in place.
Caveat: 16 KB page-size devices would need 16 KB-aligned
libraries; the vendored .so files are not verified for that,
and the owner's device uses 4 KB pages.

Library resources and R classes: the AARs carrying a res/ tree
(core, play-services-base, play-services-basement,
lifecycle-runtime, mlkit-translate) are compiled and linked by
build.sh together with the app's resources, and
`app/gen_library_r.py` generates each library's R class from its
AAR R.txt against the final link symbols -- the manual
equivalent of AGP's resource merge.

Exclusions (POM-reachable but deliberately not vendored):
- androidx.appcompat and its chain: nothing in the set
  references appcompat classes (jdeps-verified); it entered the
  naive POM walk only through unrelated consumers.
- androidx.fragment / androidx.activity / lifecycle-livedata /
  loader / viewpager: referenced only by play-services-base's
  error-dialog helpers (SupportErrorDialogFragment and the
  ActivityResultLauncher-based error APIs). PageLoupe never
  invokes them -- ML Kit reports failures as exceptions, which
  the app surfaces itself -- and fragment would drag the other
  four in for that dead path alone.
- kotlin-stdlib older copies inside the tree: the build's own
  stdlib (kotlinc 2.0.21) is the single one dexed.
- firebase-annotations 16.0.0 is compile-only (it is in the
  closure list above, marked as a compile-classpath jar).

## Reference only (not read by the build)

- androidx.webkit 1.18.0 POM —
  https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/1.18.0/webkit-1.18.0.pom
  SHA-256: cfe9a0f8ab5d0c1b2cd8dfc052b83da4025f8065179f91535bf806960e76fdd7
  (cross-checked 2026-11-21 against Google Maven's published
  .sha1). Kept as the record of webkit's declared dependencies
  (the basis for the androidx.core floor version and the
  compile-only annotation jars above). build.sh never reads
  it; it is hash-pinned in SHA256SUMS like everything else in
  this directory so a silent replacement cannot go unnoticed.
