# Test-only libraries

Jars in this directory are used ONLY by the JVM tests
(tests/run_tests.sh). They are never on the app's compile
classpath, never referenced by app/build.sh, and never enter
the APK.

## json-20260814.jar

- What: JSON-java, the org.json reference implementation
  (https://github.com/stleary/JSON-java). The app itself uses
  the org.json built into Android; on the JVM, android.jar's
  org.json is a stub that throws at runtime, so the recorder's
  CDP ingestion and console JSON export (toJson) could not be
  tested at all without a working implementation. (The
  requests export, writeRequestsJson, uses
  android.util.JsonWriter and stays outside JVM coverage.)
- Why this version: 20260814 was the latest stable release at
  adoption (2026-11-21) and includes the fixes for
  CVE-2023-5072 and CVE-2026-59171. Versions before 20231013
  are avoided (known DoS issues).
- Source:
  https://repo1.maven.org/maven2/org/json/json/20260814/json-20260814.jar
- Maven Central .sha1: a1d979d4b336516526d00ff423e4a8d4ba27670f
  (matched the downloaded file at adoption)
- SHA-256: 7dd2de1533e766e2b6c07d8194da85145ad313a167a4760050f5c9b6839604fe
  (also in SHA256SUMS in this directory; run_tests.sh verifies
  it on every run and aborts on mismatch)
- License: the jar's POM declares Public Domain; the LICENSE
  it links to is the JSON License text (which carries the
  "Good, not Evil" clause). The jar is used only inside this
  project's own test runs and is not redistributed; revisit
  the license if that ever changes.
- Adoption record (2026-11-21): downloaded over HTTPS from
  the source URL above; the published .sha1 matched the file;
  the SHA-256 above was computed from the file. A .asc
  signature exists (Ed25519, key ID FD116C1969FCCFF3), but
  the signing key could not be retrieved from the public
  keyservers reached at adoption, so the signature is NOT
  verified — provenance rests on the HTTPS source and the
  .sha1 match, the same discipline as app/libs. The known
  vulnerability history is tracked upstream (e.g.
  CVE-2026-59171, GHSA-ghc9-756g-vrcv, fixed in 20260719;
  checked against the GitHub Advisory Database entry on
  2026-11-21).
- What the hash check can and cannot do: it detects
  corruption and mix-ups. It cannot detect a coordinated
  replacement of both the jar and SHA256SUMS; changes to
  either are therefore always eyeballed in diff review
  (see REVIEW.md).

Scope limits (owner decision, 2026-11-21, ledger SEN-19):
JSON-java and Android's org.json diverge in edge behavior —
key order in toString(), optString for a JSON null, duplicate
keys, nesting-depth limits, number formatting. Inputs
containing JSON null are likewise not asserted on the JVM.
Tests running against this jar assert the recorder's own
logic (structure and values) only; byte-level output
equality stays verified on device, as before. One deliberate
exception to the JSON-null rule: a null ELEMENT inside the
console args array. Both implementations' optJSONObject
returns null for a non-object element (a type check, not the
divergent optString behavior), and the recorder's own
stringifyArg maps a null argument to "undefined" — that much
is asserted.
