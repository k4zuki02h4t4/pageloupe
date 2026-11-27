#!/usr/bin/env python3
"""Assert a packaged APK's manifest matches the approved posture.

Successor of the manual pipeline's gate (the old build.sh signed
to a pending name, ran this check, and only then promoted the
APK to its final name). The Gradle build calls this script after
packaging (see app/build.gradle.kts), for every variant.

What is asserted, all from the APK's own binary manifest
(aapt2 dump xmltree), never from source files:

  - the component set (activity / activity-alias / service /
    receiver / provider) equals app/manifest-components.txt,
    including each component's android:exported value;
  - the uses-permission set equals app/manifest-permissions.txt;
  - exactly one <permission> element exists, the
    signature-protected DYNAMIC_RECEIVER one androidx declares;
  - the structural inventory equals app/manifest-structure.txt:
    the set of element tags, the application element's
    attribute names, every <meta-data> entry (name, owning
    parent, and value where the allowlist pins one), every
    intent surface (per owning component: its filters'
    action, category and data-scheme names), and the
    application id itself — the manifest's package attribute
    and the caller-reported id must both equal the pinned
    value;
  - the application element's posture values: no debuggable or
    testOnly attribute, and allowBackup / usesCleartextTraffic /
    extractNativeLibs / appComponentFactory carry the approved
    values;
  - the ML Kit components the app depends on are present and
    the datatransport components (whose CCT backend is the
    telemetry upload path) are absent — implied by the set
    equality, but spelled out because they are the reason the
    gate exists.

Not asserted, by design: attribute values that are resource
references (icon, theme, dataExtractionRules — their resource
ids shift between builds; their presence is pinned by the
attribute-name set), intent-filter <data> details beyond the
scheme (hosts, mime types and path / pathPrefix /
pathPattern are source-review territory), component
attributes beyond exported (permission, process, taskAffinity
and the like), and intent-filter attributes (priority,
autoVerify).

Fail-closed: on ANY failure — a mismatch, a missing file, an
aapt2 error — the APK is renamed to <name>.rejected before
exiting non-zero, so an unverified artifact never keeps its
shippable name. A stale .rejected from an earlier failure is
removed when a new verification starts.

Usage:
    verify_manifest.py <apk> <components-file> <permissions-file>
                       <structure-file> <aapt2> <application-id>
"""

import re
import subprocess
import sys
from pathlib import Path

COMPONENT_TAGS = {"activity", "activity-alias", "service", "receiver", "provider"}

REQUIRED_COMPONENTS = {
    "com.google.mlkit.common.internal.MlKitInitProvider",
    "com.google.mlkit.common.internal.MlKitComponentDiscoveryService",
}

FORBIDDEN_COMPONENTS = {
    "com.google.android.datatransport.runtime.backends"
    ".TransportBackendDiscovery",
    "com.google.android.datatransport.runtime.scheduling.jobscheduling"
    ".JobInfoSchedulerService",
    "com.google.android.datatransport.runtime.scheduling.jobscheduling"
    ".AlarmManagerSchedulerBroadcastReceiver",
}

# Approved application-element values (attribute name ->
# required value; a required value of None means the attribute
# must be absent). The full attribute-name set is pinned
# separately by the structure allowlist.
APPLICATION_POSTURE = {
    "debuggable": None,
    "testOnly": None,
    "allowBackup": "false",
    "usesCleartextTraffic": "true",
    "extractNativeLibs": "false",
    "appComponentFactory": "androidx.core.app.CoreComponentFactory",
}

ELEMENT_RE = re.compile(r"^(\s*)E: (\S+) \(line=\d+\)$")
# Attribute names print with their full namespace URI
# (http://schemas.android.com/apk/res/android:name); the local
# part is taken after the last colon below. Android-namespace
# attributes always carry a resource id in parentheses in a
# packaged manifest; the bare attributes on the <manifest>
# element (package, platformBuildVersion*) print without one.
# The id group is captured so its presence can be required
# for namespaced attributes: the platform resolves attributes
# by id, so an id-less namesake is a value the platform does
# not read under that name.
ATTR_RE = re.compile(r"^\s*A: (\S+?)(?:\((0x[0-9a-f]+)\))?=(.*)$")


ANDROID_NS = "http://schemas.android.com/apk/res/android"

# The android resource ids of every attribute the gate pins
# by name — the ones whose values it reads, plus every member
# of the application and manifest attribute-name sets —
# measured from the shipped APK's dump (and, for debuggable
# and testOnly, the framework's published ids). The platform
# resolves attributes by id, so an attribute whose printed
# name and id disagree is one the gate and the platform read
# differently; pinning the name sets without the ids would
# let a swapped id (a networkSecurityConfig riding on
# roundIcon's name, say) pass the set comparison.
ATTRIBUTE_IDS = {
    "theme": "0x01010000",
    "label": "0x01010001",
    "icon": "0x01010002",
    "name": "0x01010003",
    "protectionLevel": "0x01010009",
    "debuggable": "0x0101000f",
    "exported": "0x01010010",
    "value": "0x01010024",
    "scheme": "0x01010027",
    "allowBackup": "0x01010280",
    "versionCode": "0x0101021b",
    "versionName": "0x0101021c",
    "minSdkVersion": "0x0101020c",
    "targetSdkVersion": "0x01010270",
    "testOnly": "0x01010272",
    "extractNativeLibs": "0x010104ea",
    "usesCleartextTraffic": "0x010104ec",
    "roundIcon": "0x0101052c",
    "compileSdkVersion": "0x01010572",
    "compileSdkVersionCodename": "0x01010573",
    "appComponentFactory": "0x0101057a",
    "dataExtractionRules": "0x0101063e",
    "enableOnBackInvokedCallback": "0x0101066c",
}

# The same table read backwards: an id from the table
# printed under a different name is the platform reading a
# gate-checked attribute where the gate sees an unchecked
# one, so the check runs in both directions.
ATTRIBUTE_NAMES_BY_ID = {v: k for k, v in ATTRIBUTE_IDS.items()}

# The only attributes aapt2 prints without the android
# namespace in a packaged manifest, all on the <manifest>
# element itself (measured against the shipped APK). A bare
# attribute anywhere else would be invisible to the platform
# but, keyed by its local name alone, could shadow the
# android-namespaced value in this parser's dictionaries —
# the gate would read the value the platform ignores.
BARE_ATTRIBUTES = {
    "package", "platformBuildVersionCode", "platformBuildVersionName",
}


def dump_elements(aapt2: Path, apk: Path):
    """Parse the binary manifest into (elements, problems).

    Each element is a dict with its tag, its attributes
    ({local name: value}) and its parent element (by
    indentation), so assertions can pin where an element
    sits, not only that it exists. Attribute values in the
    xmltree dump come back as quoted strings, booleans, or
    hex integers; they are normalized to the plain string
    form here. Anything the parser cannot read — an element
    line, an attribute line, a foreign or (outside the
    measured bare set) namespace-less attribute name, a
    duplicated attribute, a value with an embedded quote —
    is a problem, never a silent skip: what the gate cannot
    see, the gate does not pin.
    """
    try:
        out = subprocess.run(
            [str(aapt2), "dump", "xmltree", str(apk),
             "--file", "AndroidManifest.xml"],
            check=True, capture_output=True, text=True,
        ).stdout
    except subprocess.CalledProcessError as e:
        raise RuntimeError(
            f"aapt2 dump failed (exit {e.returncode}):"
            f" {(e.stderr or '').strip()}")
    elements = []
    problems = []
    stack = []
    current = None
    ns_count = 0
    for line in out.splitlines():
        m = ELEMENT_RE.match(line)
        if m:
            indent = len(m.group(1))
            while stack and stack[-1][0] >= indent:
                stack.pop()
            current = {
                "tag": m.group(2),
                "attrs": {},
                "parent": stack[-1][1] if stack else None,
            }
            elements.append(current)
            stack.append((indent, current))
            continue
        if line.lstrip().startswith("E: "):
            # An element line the parser cannot read: fail
            # closed, exactly like an unreadable attribute line
            # (its attributes would otherwise be attributed to
            # the previous element).
            problems.append(f"unparsable element line: {line.strip()}")
            current = None
            continue
        m = ATTR_RE.match(line)
        if m and current is not None:
            full_name = m.group(1)
            if "://" in full_name:
                namespace, _, local = full_name.rpartition(":")
                if namespace != ANDROID_NS:
                    problems.append(
                        f"attribute in a foreign namespace: {full_name}")
                    continue
                if m.group(2) is None:
                    problems.append(
                        f"namespaced attribute without a resource"
                        f" id: {full_name}")
                    continue
                attr_id = m.group(2).lower()
                expected_id = ATTRIBUTE_IDS.get(local)
                if expected_id is not None and attr_id != expected_id:
                    problems.append(
                        f"attribute id mismatch for {local}:"
                        f" expected {expected_id}, found {m.group(2)}")
                    continue
                owner_name = ATTRIBUTE_NAMES_BY_ID.get(attr_id)
                if owner_name is not None and owner_name != local:
                    problems.append(
                        f"attribute id {m.group(2)} belongs to"
                        f" {owner_name}, printed as {local}")
                    continue
                if attr_id in current.setdefault("ids", set()):
                    problems.append(
                        f"duplicate attribute id {m.group(2)} on"
                        f" <{current['tag']}>")
                    continue
                current["ids"].add(attr_id)
            elif re.fullmatch(r"[A-Za-z_][\w.]*", full_name):
                local = full_name
                if current["tag"] != "manifest" or \
                        local not in BARE_ATTRIBUTES:
                    problems.append(
                        f"attribute without the android namespace:"
                        f" {full_name} on <{current['tag']}>")
                    continue
                if m.group(2) is not None:
                    problems.append(
                        f"bare attribute with a resource id:"
                        f" {full_name}")
                    continue
            else:
                # A name that is neither namespaced nor a plain
                # identifier (an id-only rendering, say) cannot
                # be told apart from the attribute it claims to
                # be; the element carrying it is not pinned.
                problems.append(
                    f"attribute with an unreadable name: {full_name}")
                continue
            if local in current["attrs"]:
                problems.append(
                    f"duplicate attribute {local} on <{current['tag']}>")
                continue
            raw = m.group(3).strip()
            if raw.startswith('"'):
                end = raw.find('"', 1)
                if end < 0:
                    problems.append(
                        f"unterminated attribute value: {full_name}")
                    continue
                value = raw[1:end]
                rest = raw[end + 1:]
                if rest and rest != f' (Raw: "{value}")':
                    # The dump's only legitimate continuation
                    # after a quoted value is the (Raw: ...)
                    # echo of that exact value. Anything else —
                    # including an echo of a different string —
                    # means the real value contained a quote
                    # and the parse above cut it short at it,
                    # so what the gate would compare is a
                    # prefix of the real value.
                    problems.append(
                        f"attribute value with an embedded quote:"
                        f" {full_name}")
                    continue
            else:
                if len(raw.split()) != 1:
                    # An unquoted (typed) value is a single
                    # token in the dump; trailing matter means
                    # the line is not the form it claims to be.
                    problems.append(
                        f"unquoted attribute value with trailing"
                        f" content: {full_name}")
                    continue
                value = raw
            current["attrs"][local] = value
        elif line.lstrip().startswith("A: "):
            problems.append(f"unparsable attribute line: {line.strip()}")
        elif line.lstrip().startswith("N: "):
            # The shipped dump carries exactly one namespace
            # declaration (the android prefix, at the top). Any
            # further declaration is a line whose content the
            # gate does not read, so it is pinned like the
            # rest.
            ns_count += 1
            if ns_count > 1:
                problems.append(
                    f"unexpected namespace declaration: {line.strip()}")
            continue
        elif line.strip():
            # A line that is none of the dump's three forms
            # (namespace, element, attribute) — the second
            # half of a value containing a newline, say — is
            # not silently skipped: what the gate cannot see,
            # the gate does not pin.
            problems.append(f"unrecognized dump line: {line.strip()}")
    return elements, problems


def read_name_list(path: Path):
    """A plain name list; duplicates are problems, as in the
    other readers."""
    names = set()
    problems = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            if line in names:
                problems.append(f"{path.name}: duplicate entry {line}")
            names.add(line)
    return names, problems


def read_components(path: Path):
    """Lines are 'name' or 'name exported=true|false'.

    Strict: an unknown token or a duplicate name is a problem,
    never a silent default.
    """
    components = {}
    problems = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        name = parts[0]
        exported = None
        for extra in parts[1:]:
            if extra.startswith("exported=") and exported is None:
                exported = extra.split("=", 1)[1]
            else:
                problems.append(
                    f"components allowlist: unknown token {extra!r}"
                    f" on line {line!r}")
        if name in components:
            problems.append(f"components allowlist: duplicate {name}")
        components[name] = exported
    return components, problems


STRUCTURE_SECTIONS = {
    "tags", "application-attributes", "meta-data",
    "intent-filters", "application-id", "manifest-attributes",
    "uses-sdk",
}


def read_structure(path: Path):
    """Sections introduced by '# section: <name>' lines.

    Strict, like the other readers: an unknown or repeated
    section, an entry before any section, and a duplicate
    entry are all problems.
    """
    sections = {}
    problems = []
    current = None
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("# section:"):
            current = line.split(":", 1)[1].strip()
            if current not in STRUCTURE_SECTIONS:
                problems.append(
                    f"structure allowlist: unknown section {current!r}")
            if current in sections:
                problems.append(
                    f"structure allowlist: duplicate section {current!r}")
            sections.setdefault(current, set())
        elif line and not line.startswith("#"):
            if current is None:
                problems.append(
                    f"structure allowlist: entry before any section:"
                    f" {line!r}")
            elif line in sections[current]:
                problems.append(
                    f"structure allowlist: duplicate entry {line!r}"
                    f" in section {current!r}")
            else:
                sections[current].add(line)
    return sections, problems


def set_problem(label, shipped, expected):
    if shipped == expected:
        return None
    return (
        f"{label} mismatch — shipped only: "
        + ", ".join(sorted(shipped - expected))
        + "; expected only: "
        + ", ".join(sorted(expected - shipped)))


def verify(apk: Path, components_file: Path, permissions_file: Path,
           structure_file: Path, aapt2: Path, application_id: str):
    problems = []
    elements, parse_problems = dump_elements(aapt2, apk)
    problems.extend(parse_problems)

    components = {}
    permissions = set()
    permission_elements = []
    application_attrs = {}
    application_count = 0
    manifest_package = None
    manifest_attrs = {}
    manifest_count = 0
    uses_sdk_attrs = {}
    uses_sdk_count = 0
    tags = set()
    metadata = {}
    children = {}
    for element in elements:
        if element["parent"] is not None:
            children.setdefault(id(element["parent"]), []).append(element)
    for element in elements:
        tag = element["tag"]
        attrs = element["attrs"]
        parent = element["parent"]
        tags.add(tag)
        # An element whose identifying attribute cannot be
        # read is not silently dropped from its set: a name the
        # gate cannot see is a name the gate does not pin.
        # Placement is pinned too: an element only counts
        # where the platform would honour it.
        if tag == "manifest":
            manifest_count += 1
            if parent is not None:
                problems.append("nested <manifest> element")
            manifest_package = attrs.get("package")
            manifest_attrs = attrs
        elif tag in ("permission", "uses-sdk", "queries"):
            # Placement for the remaining top-level elements
            # (application's own count and placement are
            # checked in its branch below; components,
            # uses-permission and meta-data have their rules
            # in their branches).
            if parent is None or parent["tag"] != "manifest":
                problems.append(f"{tag} element outside <manifest>")
                continue
            if tag == "permission":
                permission_elements.append(attrs)
            elif tag == "uses-sdk":
                uses_sdk_count += 1
                uses_sdk_attrs = attrs
        elif tag in COMPONENT_TAGS:
            if parent is None or parent["tag"] != "application":
                problems.append(f"{tag} element outside <application>")
                continue
            name = attrs.get("name")
            if not name:
                problems.append(f"{tag} element without a name")
            elif name.startswith("."):
                # AGP's merger expands relative names, so a
                # relative name in the shipped manifest is
                # itself a finding, not something to expand
                # silently against a possibly different id.
                problems.append(
                    f"unexpanded relative component name: {name}")
            elif name in components:
                problems.append(f"duplicate component element: {name}")
            else:
                components[name] = attrs.get("exported")
        elif tag in ("uses-permission", "uses-permission-sdk-23",
                     "uses-permission-sdk-m"):
            if parent is None or parent["tag"] != "manifest":
                problems.append(f"{tag} element outside <manifest>")
                continue
            name = attrs.get("name")
            if not name:
                problems.append(f"{tag} element without a name")
            else:
                permissions.add(name)
        elif tag == "application":
            if parent is None or parent["tag"] != "manifest":
                problems.append("application element outside <manifest>")
            application_count += 1
            application_attrs = attrs
        elif tag == "meta-data":
            name = attrs.get("name")
            if not name:
                problems.append("meta-data element without a name")
                continue
            if parent is not None and parent["tag"] == "application":
                parent_id = "application"
            elif parent is not None and parent["tag"] in COMPONENT_TAGS:
                parent_id = parent["attrs"].get("name") or "<unnamed>"
            else:
                problems.append(
                    f"meta-data {name} under an unexpected parent")
                continue
            key = f"{name} @ {parent_id}"
            if key in metadata:
                problems.append(f"duplicate meta-data element: {key}")
            else:
                metadata[key] = attrs.get("value")
    if application_count != 1:
        problems.append(
            f"expected exactly one <application> element,"
            f" found {application_count}")
    if manifest_count != 1:
        problems.append(
            f"expected exactly one <manifest> element,"
            f" found {manifest_count}")
    if uses_sdk_count != 1:
        problems.append(
            f"expected exactly one <uses-sdk> element,"
            f" found {uses_sdk_count}")

    # Intent surfaces, pinned per owner: for every
    # <intent-filter> (and the <intent> inside <queries>), the
    # sorted action / category / data-scheme names of its
    # direct children, attributed to the owning component (or
    # <queries>). A filter moved to another component, or an
    # action added to one filter, changes the surface.
    intent_surfaces = set()
    for element in elements:
        if element["tag"] == "intent":
            if element["parent"] is None or \
                    element["parent"]["tag"] != "queries":
                problems.append("intent element outside <queries>")
        if element["tag"] in ("action", "category", "data"):
            if element["parent"] is None or \
                    element["parent"]["tag"] not in ("intent-filter", "intent"):
                problems.append(
                    f"{element['tag']} element outside an intent-filter")
        if element["tag"] not in ("intent-filter", "intent"):
            continue
        owner = None
        node = element["parent"]
        while node is not None:
            if node["tag"] in COMPONENT_TAGS:
                owner = node["attrs"].get("name") or "<unnamed>"
                break
            if node["tag"] == "queries":
                owner = "<queries>"
                break
            node = node["parent"]
        if owner is None:
            problems.append("intent-filter without a component owner")
            continue
        surface = {"action": set(), "category": set(), "data": set()}
        for child in children.get(id(element), []):
            if child["tag"] in ("action", "category"):
                name = child["attrs"].get("name")
                if not name:
                    problems.append(
                        f"{child['tag']} element without a name")
                else:
                    surface[child["tag"]].add(name)
            elif child["tag"] == "data":
                scheme = child["attrs"].get("scheme")
                if scheme:
                    surface["data"].add(scheme)
        intent_surfaces.add(
            f"{owner} | actions={','.join(sorted(surface['action']))}"
            f" | categories={','.join(sorted(surface['category']))}"
            f" | schemes={','.join(sorted(surface['data']))}")

    expected_components, list_problems = read_components(components_file)
    problems.extend(list_problems)
    if components != expected_components:
        only_shipped = sorted(set(components) - set(expected_components))
        only_expected = sorted(set(expected_components) - set(components))
        changed = sorted(
            name for name in set(components) & set(expected_components)
            if components[name] != expected_components[name]
        )
        detail = []
        if only_shipped:
            detail.append("shipped only: " + ", ".join(only_shipped))
        if only_expected:
            detail.append("expected only: " + ", ".join(only_expected))
        if changed:
            detail.append(
                "exported differs: " + ", ".join(
                    f"{n} (shipped {components[n]},"
                    f" expected {expected_components[n]})"
                    for n in changed))
        problems.append("component set mismatch — " + "; ".join(detail))

    expected_permissions, list_problems = read_name_list(permissions_file)
    problems.extend(list_problems)
    problem = set_problem(
        "uses-permission set", permissions, expected_permissions)
    if problem:
        problems.append(problem)

    structure, structure_problems = read_structure(structure_file)
    problems.extend(structure_problems)
    for label, shipped, section in (
        ("element tag set", tags, "tags"),
        ("application attribute set", set(application_attrs),
         "application-attributes"),
        ("manifest attribute set", set(manifest_attrs),
         "manifest-attributes"),
        ("intent surface set", intent_surfaces, "intent-filters"),
    ):
        if section not in structure:
            problems.append(
                f"structure allowlist: missing section {section!r}")
            continue
        problem = set_problem(label, shipped, structure[section])
        if problem:
            problems.append(problem)

    # The application id is pinned by the allowlist, not
    # trusted from the build: both the manifest's own package
    # attribute and the id the build reported (output
    # metadata, passed in by the caller) must equal it. An
    # applicationId change or suffix would otherwise sail
    # through — every id-derived expectation (the receiver
    # permission's name, say) is computed from the reported
    # id and would stay self-consistent.
    if "application-id" not in structure:
        problems.append(
            "structure allowlist: missing section 'application-id'")
    elif len(structure["application-id"]) != 1:
        problems.append(
            "structure allowlist: 'application-id' must hold"
            " exactly one entry")
    else:
        expected_id = next(iter(structure["application-id"]))
        if manifest_package != expected_id:
            problems.append(
                f"manifest package must be {expected_id},"
                f" found {manifest_package}")
        if application_id != expected_id:
            problems.append(
                f"reported applicationId must be {expected_id},"
                f" found {application_id}")

    # meta-data is pinned as name -> value where the allowlist
    # gives a value (the component registrars: a swapped
    # registrar class is a behaviour change the name alone
    # would miss). Entries without a value (gms.version, whose
    # value is a resource reference that shifts between
    # builds) pin the name only.
    if "meta-data" not in structure:
        problems.append("structure allowlist: missing section 'meta-data'")
    else:
        expected_metadata = {}
        for entry in structure["meta-data"]:
            name, sep, value = entry.partition("=")
            expected_metadata[name] = value if sep else None
        problem = set_problem(
            "meta-data name set", set(metadata), set(expected_metadata))
        if problem:
            problems.append(problem)
        for name, expected_value in expected_metadata.items():
            if expected_value is not None and \
                    metadata.get(name) != expected_value:
                problems.append(
                    f"meta-data {name} value must be {expected_value},"
                    f" found {metadata.get(name)}")

    # The SDK levels are build-defining values; pin them in
    # the allowlist (name=value entries) so a drift between
    # the build configuration and the shipped manifest is a
    # gate failure, not a surprise.
    if "uses-sdk" not in structure:
        problems.append("structure allowlist: missing section 'uses-sdk'")
    elif not structure["uses-sdk"]:
        problems.append("structure allowlist: empty section 'uses-sdk'")
    else:
        for entry in structure["uses-sdk"]:
            name, _, expected = entry.partition("=")
            if uses_sdk_attrs.get(name) != expected:
                problems.append(
                    f"uses-sdk {name} must be {expected},"
                    f" found {uses_sdk_attrs.get(name)}")

    receiver_permission = (
        application_id + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    if len(permission_elements) != 1:
        problems.append(
            f"expected exactly one <permission> element, found"
            f" {len(permission_elements)}")
    else:
        attrs = permission_elements[0]
        if attrs.get("name") != receiver_permission:
            problems.append(
                f"unexpected <permission> name: {attrs.get('name')}")
        # protectionLevel signature == 0x2 in the binary dump.
        if attrs.get("protectionLevel") not in ("0x00000002", "2"):
            problems.append(
                "DYNAMIC_RECEIVER permission is not signature-level:"
                f" {attrs.get('protectionLevel')}")

    for attr, required in APPLICATION_POSTURE.items():
        actual = application_attrs.get(attr)
        if required is None and actual is not None:
            problems.append(f"application must not carry {attr} (={actual})")
        elif required is not None and actual != required:
            problems.append(
                f"application {attr} must be {required}, found {actual}")

    missing = REQUIRED_COMPONENTS - set(components)
    if missing:
        problems.append(
            "required components missing: " + ", ".join(sorted(missing)))
    present = FORBIDDEN_COMPONENTS & set(components)
    if present:
        problems.append(
            "forbidden components present: " + ", ".join(sorted(present)))
    return problems


def main(argv):
    apk = Path(argv[1]) if len(argv) >= 2 else None
    if len(argv) != 7:
        print(__doc__)
        # Best-effort fail-closed, for APK paths only: a typo'd
        # manual invocation must never rename an arbitrary file.
        if apk is not None and apk.suffix == ".apk" and apk.exists():
            apk.rename(apk.with_name(apk.name + ".rejected"))
        return 2
    rejected = apk.with_name(apk.name + ".rejected")
    if rejected.exists():
        rejected.unlink()
    # A stage marker on stderr, flushed immediately: if the
    # caller has to kill this process, its captured output
    # still shows how far verification got. It lives here, not
    # in the parser, so the parser stays a pure function and
    # direct callers (the self-test) stay quiet.
    print("verify_manifest: dumping the manifest with aapt2",
          file=sys.stderr, flush=True)
    try:
        problems = verify(
            apk, Path(argv[2]), Path(argv[3]), Path(argv[4]),
            Path(argv[5]), argv[6])
    except Exception as e:  # fail closed on any tooling error
        problems = [f"verification errored: {e}"]
    if problems:
        for problem in problems:
            print(f"verify_manifest: {problem}", file=sys.stderr)
        if apk.exists():
            apk.rename(rejected)
            print(f"verify_manifest: renamed to {rejected.name}",
                  file=sys.stderr)
        return 1
    print("verify_manifest: OK (components, permissions, structure, posture)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
