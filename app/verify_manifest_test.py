#!/usr/bin/env python3
"""Self-test for app/verify_manifest.py.

Feeds synthetic aapt2 xmltree output (the subprocess call is
stubbed, and restored after each case) and synthetic
allowlists to the verifier. The clean fixture must produce
no problems; each mutation case must produce exactly the
expected problem set — no more, no less — so a mutation
cannot pass on the strength of an unrelated problem, and a
rule cannot silently stop firing. Mutations are inserted as
siblings after the parent element's own attribute block,
where a real dump would put them.

This is the saved, re-runnable form of the parser negative
tests the review loop originally ran by hand; the Gradle
build runs it as part of check, and the shipped-manifest
verification depends on it too (verifyManifestSelfTest in
app/build.gradle.kts), so the gate's health is checked
whenever the gate runs.

Run directly: python3 -I app/verify_manifest_test.py
Exits non-zero if any case misbehaves.
"""

import contextlib
import importlib.util
import io
import tempfile
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "verify_manifest", Path(__file__).parent / "verify_manifest.py")
vm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(vm)

NS = "http://schemas.android.com/apk/res/android"
APP_ID = "jp.kaleidpixel.pageloupe"
DISCOVERY = "com.google.mlkit.common.internal.MlKitComponentDiscoveryService"
REGISTRAR = ("com.google.firebase.components:com.google.mlkit.nl"
             ".translate.NaturalLanguageTranslateRegistrar")

# The fixture uses each attribute's real android resource id
# (the verifier pins the ids of the attributes it reads).
IDS = {
    "name": "0x01010003",
    "label": "0x01010001",
    "protectionLevel": "0x01010009",
    "exported": "0x01010010",
    "value": "0x01010024",
    "mimeType": "0x01010026",
    "scheme": "0x01010027",
    "appComponentFactory": "0x0101057a",
}


def a(name, value):
    """An android-namespaced attribute line with a quoted
    string value and its (Raw: ...) echo."""
    return (f'A: {NS}:{name}({IDS[name]})="{value}"'
            f' (Raw: "{value}")')


def build_manifest(mutate=None):
    q = '"'
    lines = [
        f"N: android={NS} (line=2)",
        "  E: manifest (line=2)",
        f"    A: {NS}:versionCode(0x0101021b)=113",
        f'    A: {NS}:versionName(0x0101021c)="0.44.0" (Raw: "0.44.0")',
        f"    A: {NS}:compileSdkVersion(0x01010572)=34",
        f'    A: {NS}:compileSdkVersionCodename(0x01010573)="14"'
        ' (Raw: "14")',
        f'    A: package="{APP_ID}" (Raw: "{APP_ID}")',
        "    A: platformBuildVersionCode=34",
        "    A: platformBuildVersionName=14",
        "      E: uses-sdk (line=7)",
        f"        A: {NS}:minSdkVersion(0x0101020c)=33",
        f"        A: {NS}:targetSdkVersion(0x01010270)=34",
        "      E: uses-permission (line=23)",
        f"        {a('name', 'android.permission.INTERNET')}",
        "      E: queries (line=59)",
        "          E: intent (line=60)",
        "              E: action (line=61)",
        f"                {a('name', 'android.intent.action.OPEN_DOCUMENT')}",
        "              E: data (line=69)",
        f"                {a('mimeType', '*/*')}",
        "      E: permission (line=73)",
        f"        {a('name', APP_ID + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION')}",
        f"        A: {NS}:protectionLevel(0x01010009)=0x00000002",
        "      E: application (line=84)",
        f"        A: {NS}:theme(0x01010000)=@0x7f0b0000",
        f"        {a('label', 'PageLoupe')}",
        f"        A: {NS}:icon(0x01010002)=@0x7f080000",
        f"        A: {NS}:allowBackup(0x01010280)=false",
        f"        A: {NS}:extractNativeLibs(0x010104ea)=false",
        f"        A: {NS}:usesCleartextTraffic(0x010104ec)=true",
        f"        A: {NS}:roundIcon(0x0101052c)=@0x7f080002",
        f"        {a('appComponentFactory', 'androidx.core.app.CoreComponentFactory')}",
        f"        A: {NS}:dataExtractionRules(0x0101063e)=@0x7f0d0000",
        f"        A: {NS}:enableOnBackInvokedCallback(0x0101066c)=true",
        "          E: meta-data (line=90)",
        f"            {a('name', 'com.google.android.gms.version')}",
        f"            A: {NS}:value(0x01010024)=@0x7f060000",
        "          E: activity (line=95)",
        f"            {a('name', APP_ID + '.MainActivity')}",
        f"            A: {NS}:exported(0x01010010)=true",
        "              E: intent-filter (line=101)",
        "                  E: action (line=102)",
        f"                    {a('name', 'android.intent.action.MAIN')}",
        "                  E: category (line=104)",
        f"                    {a('name', 'android.intent.category.LAUNCHER')}",
        "              E: intent-filter (line=106)",
        "                  E: action (line=107)",
        f"                    {a('name', 'android.intent.action.VIEW')}",
        "                  E: category (line=109)",
        f"                    {a('name', 'android.intent.category.DEFAULT')}",
        "                  E: category (line=110)",
        f"                    {a('name', 'android.intent.category.BROWSABLE')}",
        "                  E: data (line=112)",
        f"                    {a('scheme', 'http')}",
        "                  E: data (line=113)",
        f"                    {a('scheme', 'https')}",
        "          E: provider (line=200)",
        f"            {a('name', 'com.google.mlkit.common.internal.MlKitInitProvider')}",
        f"            A: {NS}:exported(0x01010010)=false",
        "          E: service (line=210)",
        f"            {a('name', DISCOVERY)}",
        f"            A: {NS}:exported(0x01010010)=false",
        "              E: meta-data (line=121)",
        f"                {a('name', REGISTRAR)}",
        f"                {a('value', 'com.google.firebase.components.ComponentRegistrar')}",
    ]
    if mutate:
        lines = mutate(lines)
    return "\n".join(lines) + "\n"


COMPONENTS = """\
jp.kaleidpixel.pageloupe.MainActivity exported=true
com.google.mlkit.common.internal.MlKitComponentDiscoveryService exported=false
com.google.mlkit.common.internal.MlKitInitProvider exported=false
"""

PERMISSIONS = "android.permission.INTERNET\n"

STRUCTURE = f"""\
# section: tags
action
activity
application
category
data
intent
intent-filter
manifest
meta-data
permission
provider
queries
service
uses-permission
uses-sdk
# section: application-attributes
allowBackup
appComponentFactory
dataExtractionRules
enableOnBackInvokedCallback
extractNativeLibs
icon
label
roundIcon
theme
usesCleartextTraffic
# section: manifest-attributes
compileSdkVersion
compileSdkVersionCodename
package
platformBuildVersionCode
platformBuildVersionName
versionCode
versionName
# section: meta-data
com.google.android.gms.version @ application
{REGISTRAR} @ {DISCOVERY}=com.google.firebase.components.ComponentRegistrar
# section: intent-filters
<queries> | actions=android.intent.action.OPEN_DOCUMENT | categories= | schemes=
jp.kaleidpixel.pageloupe.MainActivity | actions=android.intent.action.MAIN | categories=android.intent.category.LAUNCHER | schemes=
jp.kaleidpixel.pageloupe.MainActivity | actions=android.intent.action.VIEW | categories=android.intent.category.BROWSABLE,android.intent.category.DEFAULT | schemes=http,https
# section: application-id
jp.kaleidpixel.pageloupe
# section: uses-sdk
minSdkVersion=33
targetSdkVersion=34
"""


class FakeCompleted:
    def __init__(self, out):
        self.stdout = out


def run_case(mutate=None, application_id=APP_ID, structure=STRUCTURE):
    text = build_manifest(mutate)
    original = vm.subprocess.run
    vm.subprocess.run = lambda *args, **kwargs: FakeCompleted(text)
    try:
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "components.txt").write_text(COMPONENTS)
            (tmp / "permissions.txt").write_text(PERMISSIONS)
            (tmp / "structure.txt").write_text(structure)
            return vm.verify(
                tmp / "x.apk", tmp / "components.txt",
                tmp / "permissions.txt", tmp / "structure.txt",
                tmp / "aapt2", application_id)
    finally:
        vm.subprocess.run = original


def insert_after(lines, marker, new_lines):
    idx = next(i for i, line in enumerate(lines) if marker in line)
    return lines[:idx + 1] + new_lines + lines[idx + 1:]


def insert_before(lines, marker, new_lines):
    idx = next(i for i, line in enumerate(lines) if marker in line)
    return lines[:idx] + new_lines + lines[idx:]


def replace(lines, old, new):
    return [line.replace(old, new) for line in lines]


CASES = []


def case(name, expected, **kwargs):
    CASES.append((name, expected, kwargs))


case("clean fixture passes", [])
case(
    "namespace-less attribute shadowing an android one",
    ["without the android namespace"],
    mutate=lambda lines: insert_after(
        lines, ":exported(0x01010010)=true",
        ["            A: exported=false"]),
)
case(
    "the same attribute id twice on one element",
    ["duplicate attribute id"],
    mutate=lambda lines: insert_after(
        lines, ":exported(0x01010010)=true",
        [f"            A: {NS}:exported(0x01010010)=false"]),
)
case(
    "the same attribute name twice with different ids",
    ["duplicate attribute windowSoftInputMode on"],
    mutate=lambda lines: insert_after(
        lines, ":exported(0x01010010)=true",
        [f"            A: {NS}:windowSoftInputMode(0x0101001d)"
         "=0x00000000",
         f"            A: {NS}:windowSoftInputMode(0x0101001e)"
         "=0x00000000"]),
)
case(
    "attribute in a foreign namespace",
    ["foreign namespace"],
    mutate=lambda lines: insert_after(
        lines, "E: application",
        ["        A: http://schemas.android.com/apk/res-auto"
         ":allowBackup(0x0)=false"]),
)
case(
    "value with an embedded quote (the real dump form)",
    ["embedded quote", "application attribute set mismatch"],
    mutate=lambda lines: replace(
        lines,
        f'{NS}:label(0x01010001)="PageLoupe" (Raw: "PageLoupe")',
        f'{NS}:label(0x01010001)="X" (Raw: "X" (Raw: "X"'
        ' (Raw: "X")'),
)
case(
    "namespaced attribute without a resource id",
    ["without a resource id", "component set mismatch"],
    mutate=lambda lines: replace(
        lines, ":exported(0x01010010)=true", ":exported=true"),
)
case(
    "attribute id that does not match its name",
    ["attribute id mismatch for exported", "component set mismatch"],
    mutate=lambda lines: replace(
        lines, ":exported(0x01010010)=true",
        ":exported(0x01010003)=true"),
)
case(
    "component outside <application>",
    ["outside <application>"],
    mutate=lambda lines: insert_before(
        lines, "E: application",
        ["      E: activity (line=300)",
         f"        {a('name', 'a.B')}"]),
)
case(
    "<permission> outside <manifest>",
    ["outside <manifest>"],
    mutate=lambda lines: insert_before(
        lines, "E: provider",
        ["          E: permission (line=301)",
         f"            {a('name', 'a.B')}"]),
)
case(
    "a second <application> element",
    ["exactly one <application>",
     "application attribute set mismatch",
     "application allowBackup must be",
     "application usesCleartextTraffic must be",
     "application extractNativeLibs must be",
     "application appComponentFactory must be"],
    mutate=lambda lines: lines + ["      E: application (line=400)"],
)
case(
    "reported applicationId differs from the pinned one",
    ["reported applicationId", "unexpected <permission> name"],
    application_id="jp.example.other",
)
case(
    "duplicate meta-data under one parent",
    [f"duplicate meta-data element: x.y @ {DISCOVERY}"],
    mutate=lambda lines: insert_before(
        lines, "E: meta-data (line=121)",
        ["              E: meta-data (line=119)",
         f"                {a('name', 'x.y')}",
         "              E: meta-data (line=120)",
         f"                {a('name', 'x.y')}"]),
    structure=STRUCTURE.replace(
        "# section: meta-data\n",
        f"# section: meta-data\nx.y @ {DISCOVERY}\n"),
)
case(
    "a second <manifest> element",
    ["exactly one <manifest>", "manifest package must be",
     "manifest attribute set mismatch"],
    mutate=lambda lines: lines + ["  E: manifest (line=500)"],
)
case(
    "uses-sdk target value drift",
    ["uses-sdk targetSdkVersion must be 34"],
    mutate=lambda lines: replace(
        lines, ":targetSdkVersion(0x01010270)=34",
        ":targetSdkVersion(0x01010270)=35"),
)
case(
    "an extra attribute on <manifest>",
    ["manifest attribute set mismatch"],
    mutate=lambda lines: insert_after(
        lines, "A: package=",
        [f"    A: {NS}:installLocation(0x010102b7)=1"]),
)
case(
    "uses-permission outside <manifest>",
    ["outside <manifest>"],
    mutate=lambda lines: insert_before(
        lines, "E: provider",
        ["          E: uses-permission (line=302)",
         f"            {a('name', 'android.permission.CAMERA')}"]),
)
case(
    "meta-data value swapped",
    ["value must be com.google.firebase.components"
     ".ComponentRegistrar"],
    mutate=lambda lines: replace(
        lines,
        "com.google.firebase.components.ComponentRegistrar",
        "com.example.OtherRegistrar"),
)
case(
    "an action added to a filter",
    ["intent surface set mismatch"],
    mutate=lambda lines: insert_after(
        lines, "android.intent.action.VIEW",
        ["                  E: action (line=108)",
         f"                    {a('name', 'android.intent.action.SEND')}"]),
)
case(
    "a component's exported differs",
    ["component set mismatch"],
    mutate=lambda lines: replace(
        lines, ":exported(0x01010010)=true",
        ":exported(0x01010010)=false"),
)
case(
    "debuggable present on <application>",
    ["application attribute set mismatch",
     "application must not carry debuggable"],
    mutate=lambda lines: insert_after(
        lines, ":allowBackup(0x01010280)=false",
        [f"        A: {NS}:debuggable(0x0101000f)=true"]),
)
case(
    "an unrecognized dump line",
    ["unrecognized dump line"],
    mutate=lambda lines: lines + ["  X: something else"],
)
case(
    "an <intent> outside <queries>",
    ["intent element outside <queries>",
     "intent-filter without a component owner"],
    mutate=lambda lines: insert_before(
        lines, "E: provider",
        ["          E: intent (line=303)"]),
)
case(
    "an attribute id printed under another attribute's name",
    ["belongs to exported, printed as windowSoftInputMode"],
    mutate=lambda lines: insert_after(
        lines, ":exported(0x01010010)=true",
        [f"            A: {NS}:windowSoftInputMode(0x01010010)"
         "=0x00000000"]),
)
case(
    "a second <uses-sdk> element",
    ["exactly one <uses-sdk>"],
    mutate=lambda lines: insert_after(
        lines, ":targetSdkVersion(0x01010270)=34",
        ["      E: uses-sdk (line=8)",
         f"        A: {NS}:minSdkVersion(0x0101020c)=33",
         f"        A: {NS}:targetSdkVersion(0x01010270)=34"]),
)
case(
    "no <uses-sdk> element",
    ["exactly one <uses-sdk>", "element tag set mismatch",
     "uses-sdk minSdkVersion must be 33",
     "uses-sdk targetSdkVersion must be 34"],
    mutate=lambda lines: drop_block(lines, "E: uses-sdk", 3),
)
case(
    "an empty uses-sdk allowlist section",
    ["empty section 'uses-sdk'"],
    structure=STRUCTURE.replace(
        "minSdkVersion=33\ntargetSdkVersion=34\n", ""),
)
case(
    "a bare attribute carrying a resource id",
    ["bare attribute with a resource id",
     "manifest attribute set mismatch"],
    mutate=lambda lines: replace(
        lines, "A: platformBuildVersionCode=34",
        "A: platformBuildVersionCode(0x01010003)=34"),
)
case(
    "an unquoted value with trailing content",
    ["unquoted attribute value with trailing content",
     "manifest attribute set mismatch"],
    mutate=lambda lines: replace(
        lines, ":versionCode(0x0101021b)=113",
        ":versionCode(0x0101021b)=113 junk"),
)
case(
    "an unparsable element line",
    ["unparsable element line",
     "unparsable attribute line", "unparsable attribute line",
     "intent-filter without a component owner",
     "intent-filter without a component owner",
     "component set mismatch", "element tag set mismatch",
     "intent surface set mismatch"],
    mutate=lambda lines: replace(
        lines, "E: activity (line=95)", "E: activity"),
)
case(
    "an unparsable attribute line",
    ["unparsable attribute line"],
    mutate=lambda lines: insert_after(
        lines, ":allowBackup(0x01010280)=false",
        ["        A: ???"]),
)
case(
    "an unterminated attribute value",
    ["unterminated attribute value",
     "application attribute set mismatch"],
    mutate=lambda lines: replace(
        lines, ':label(0x01010001)="PageLoupe" (Raw: "PageLoupe")',
        ':label(0x01010001)="PageLoupe'),
)
case(
    "a nested <manifest> element",
    ["nested <manifest>", "exactly one <manifest>",
     "manifest attribute set mismatch", "manifest package must be"],
    mutate=lambda lines: insert_before(
        lines, "E: provider",
        ["          E: manifest (line=304)"]),
)
case(
    "a component without a name",
    ["element without a name"],
    mutate=lambda lines: insert_before(
        lines, "E: provider",
        ["          E: activity (line=305)",
         f"            A: {NS}:exported(0x01010010)=true"]),
)
case(
    "a relative component name",
    ["relative component name"],
    mutate=lambda lines: insert_before(
        lines, "E: provider",
        ["          E: activity (line=306)",
         f"            {a('name', '.Foo')}",
         f"            A: {NS}:exported(0x01010010)=true"]),
)
case(
    "a duplicate component",
    ["duplicate component element"],
    mutate=lambda lines: insert_before(
        lines, "E: provider",
        ["          E: activity (line=307)",
         f"            {a('name', APP_ID + '.MainActivity')}",
         f"            A: {NS}:exported(0x01010010)=true"]),
)
case(
    "<queries> outside <manifest>",
    ["outside <manifest>"],
    mutate=lambda lines: insert_before(
        lines, "E: provider",
        ["          E: queries (line=308)"]),
)
case(
    "<uses-sdk> outside <manifest>",
    ["outside <manifest>", "exactly one <uses-sdk>",
     "uses-sdk minSdkVersion must be 33",
     "uses-sdk targetSdkVersion must be 34"],
    mutate=lambda lines: insert_before(
        drop_block(lines, "E: uses-sdk", 3),
        "E: provider",
        ["          E: uses-sdk (line=309)",
         f"            A: {NS}:minSdkVersion(0x0101020c)=33",
         f"            A: {NS}:targetSdkVersion(0x01010270)=34"]),
)
case(
    "an <action> outside an intent-filter",
    ["outside an intent-filter"],
    mutate=lambda lines: insert_before(
        lines, "E: intent-filter (line=101)",
        ["              E: action (line=310)",
         f"                {a('name', 'android.intent.action.SEND')}"]),
)
case(
    "a <meta-data> directly under <manifest>",
    ["under an unexpected parent"],
    mutate=lambda lines: insert_before(
        lines, "E: application",
        ["      E: meta-data (line=311)",
         f"        {a('name', 'x.z')}"]),
)
case(
    "testOnly present on <application>",
    ["application attribute set mismatch",
     "application must not carry testOnly"],
    mutate=lambda lines: insert_after(
        lines, ":allowBackup(0x01010280)=false",
        [f"        A: {NS}:testOnly(0x01010272)=true"]),
)
case(
    "allowBackup true on its own",
    ["application allowBackup must be false"],
    mutate=lambda lines: replace(
        lines, ":allowBackup(0x01010280)=false",
        ":allowBackup(0x01010280)=true"),
)
case(
    "a forbidden component present",
    ["forbidden components present", "component set mismatch"],
    mutate=lambda lines: lines + [
        "          E: service (line=312)",
        f"            {a('name', 'com.google.android.datatransport.runtime.backends.TransportBackendDiscovery')}",
        f"            A: {NS}:exported(0x01010010)=false"],
)
def drop_block(lines, marker, count):
    idx = next(i for i, line in enumerate(lines) if marker in line)
    return lines[:idx] + lines[idx + count:]


case(
    "a required component missing",
    ["required components missing", "component set mismatch",
     "element tag set mismatch"],
    mutate=lambda lines: drop_block(lines, "E: provider", 3),
)
case(
    "the receiver permission is not signature-level",
    ["not signature-level"],
    mutate=lambda lines: replace(
        lines, ":protectionLevel(0x01010009)=0x00000002",
        ":protectionLevel(0x01010009)=0x00000000"),
)
case(
    "a second namespace declaration",
    ["unexpected namespace declaration"],
    mutate=lambda lines: lines + [f"  N: android={NS}"],
)


def run_main_case(mode):
    """Exercise vm.main itself: the rename-on-failure and
    stale-cleanup behavior the verdict relies on. main's own
    output (stage marker, verdict lines) is captured and
    discarded in every mode: the self-test's success output
    is its one summary line, and a fake 'renamed to
    .rejected' line in a passing build log would read as a
    real rejection."""
    text = build_manifest()
    original = vm.subprocess.run
    vm.subprocess.run = lambda *args, **kwargs: FakeCompleted(text)
    try:
        with contextlib.redirect_stdout(io.StringIO()), \
                contextlib.redirect_stderr(io.StringIO()):
            return _run_main_case(mode)
    finally:
        vm.subprocess.run = original


def _run_main_case(mode):
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        apk = tmp / "x.apk"
        apk.write_bytes(b"apk")
        (tmp / "components.txt").write_text(COMPONENTS)
        (tmp / "permissions.txt").write_text(PERMISSIONS)
        (tmp / "structure.txt").write_text(STRUCTURE)
        if mode == "argc":
            other = tmp / "notes.txt"
            other.write_bytes(b"keep me")
            with contextlib.redirect_stdout(io.StringIO()):
                rc = vm.main(["verify_manifest.py", str(other)])
            return rc == 2 and other.exists() and apk.exists()
        if mode == "argc-apk":
            with contextlib.redirect_stdout(io.StringIO()):
                rc = vm.main(["verify_manifest.py", str(apk)])
            return (rc == 2 and not apk.exists()
                    and (tmp / "x.apk.rejected").exists())
        if mode == "success":
            (tmp / "x.apk.rejected").write_bytes(b"stale")
            rc = vm.main([
                "verify_manifest.py", str(apk),
                str(tmp / "components.txt"),
                str(tmp / "permissions.txt"),
                str(tmp / "structure.txt"),
                str(tmp / "aapt2"), APP_ID])
            return (rc == 0 and apk.exists()
                    and not (tmp / "x.apk.rejected").exists())
        # mode == "failure": an allowlist missing a shipped
        # component makes the verdict fail.
        (tmp / "components.txt").write_text(
            "jp.kaleidpixel.pageloupe.MainActivity exported=true\n")
        rc = vm.main([
            "verify_manifest.py", str(apk),
            str(tmp / "components.txt"),
            str(tmp / "permissions.txt"),
            str(tmp / "structure.txt"),
            str(tmp / "aapt2"), APP_ID])
        return (rc == 1 and not apk.exists()
                and (tmp / "x.apk.rejected").exists())


MAIN_CASES = [
    ("main renames the APK on a failed verdict", "failure"),
    ("main removes a stale .rejected on success", "success"),
    ("main with a bad argc leaves a non-APK file alone", "argc"),
    ("main with a bad argc renames an APK argument", "argc-apk"),
]


def problems_match(problems, expected):
    """Multiset matching: each expected substring consumes
    one distinct problem containing it (longest expectations
    first, so a specific expectation is not starved by a
    broad one), and no problem may be left over. Returns
    (ok, unmatched expectations, leftover problems) so a
    failure message names both sides of the mismatch — the
    greedy assignment can in principle miss an assignment
    that exists, and the message is what tells a maintainer
    whether that is what happened."""
    remaining = list(problems)
    unmatched = []
    for e in sorted(expected, key=len, reverse=True):
        hit = next((p for p in remaining if e in p), None)
        if hit is None:
            unmatched.append(e)
        else:
            remaining.remove(hit)
    return (not unmatched and not remaining), unmatched, remaining


def main():
    # Quiet on success (one summary line), detailed on
    # failure: this runs before every packaging, and a wall
    # of PASS lines buries real build output.
    failures = 0
    for name, expected, kwargs in CASES:
        problems = run_case(**kwargs)
        ok, unmatched, leftover = problems_match(problems, expected)
        if not ok:
            print(f"FAIL: {name} — unmatched expectations:"
                  f" {unmatched}, leftover problems: {leftover}")
            failures += 1
    for name, mode in MAIN_CASES:
        if not run_main_case(mode):
            print(f"FAIL: {name}")
            failures += 1
    total = len(CASES) + len(MAIN_CASES)
    if failures:
        print(f"verify_manifest_test: {failures} case(s) failed")
        return 1
    print(f"verify_manifest_test: all {total} cases pass")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
