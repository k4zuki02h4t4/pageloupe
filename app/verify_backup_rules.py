#!/usr/bin/env python3
"""Gate for res/xml/data_extraction_rules.xml (SEN-5).

The backup / device-transfer exclusions keep the browsing
history, the saved tab session and the bookmarks off other
devices. Before this gate the rules were reviewed by eye
only, and the review found the gaps it had missed: the
state files' ".tmp" forms (a save in flight) were not
excluded, and nothing kept the two sections in step.

Checks (fail closed; every problem is reported):
1. The XML parses and holds exactly a cloud-backup and a
   device-transfer section.
2. The two sections' exclude sets are identical.
3. Required coverage, in BOTH sections, with the file
   names derived from the Kotlin sources (so a rename in
   the code moves the requirement with it):
   - each state file (MainActivity's HISTORY_FILE_NAME,
     TAB_SESSION_FILE_NAME, BOOKMARKS_FILE_NAME) in its
     plain form, its AtomicFileWrite ".tmp" form and its
     ".corrupt" evacuee form (the suffixes are read out of
     AtomicFileWrite.kt and MainActivity.kt);
   - the settings preferences file (Settings' PREFS name
     plus ".xml") under the sharedpref domain;
   - the ML Kit datatransport database and its -journal /
     -wal / -shm side files under the database domain
     (names pinned here: they are the library's, not ours).

Usage:
    verify_backup_rules.py RULES_XML MAINACTIVITY_KT SETTINGS_KT ATOMIC_KT
    verify_backup_rules.py --selftest
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DATABASE_NAMES = [
    "com.google.android.datatransport.events",
    "com.google.android.datatransport.events-journal",
    "com.google.android.datatransport.events-wal",
    "com.google.android.datatransport.events-shm",
]

STATE_CONSTANTS = ["HISTORY_FILE_NAME", "TAB_SESSION_FILE_NAME", "BOOKMARKS_FILE_NAME"]


def derive_requirements(main_activity: str, settings: str, atomic: str):
    """Read the names the rules must cover out of the sources.

    Returns (required, problems): required is a set of
    (domain, path) pairs; a name that cannot be derived is
    a problem, never a silent skip.
    """
    problems = []
    required = set()

    suffixes = []
    m = re.search(r'target\.name \+ "(\.tmp)"', atomic)
    if m:
        suffixes.append(m.group(1))
    else:
        problems.append("AtomicFileWrite.kt: temp suffix not found")
    m = re.search(r'file\.name \+ "(\.corrupt)"', main_activity)
    if m:
        suffixes.append(m.group(1))
    else:
        problems.append("MainActivity.kt: corrupt suffix not found")

    for const in STATE_CONSTANTS:
        m = re.search(const + r'\s*=\s*"([^"]+)"', main_activity)
        if not m:
            problems.append(f"MainActivity.kt: {const} not found")
            continue
        name = m.group(1)
        required.add(("file", name))
        for suffix in suffixes:
            required.add(("file", name + suffix))

    m = re.search(r'PREFS\s*=\s*"([^"]+)"', settings)
    if m:
        required.add(("sharedpref", m.group(1) + ".xml"))
    else:
        problems.append("Settings.kt: PREFS not found")

    for name in DATABASE_NAMES:
        required.add(("database", name))

    return required, problems


def parse_sections(rules_xml: str):
    """Parse the rules into {section: {(domain, path)}}."""
    root = ET.fromstring(rules_xml)
    if root.tag != "data-extraction-rules":
        raise ValueError(f"root element is <{root.tag}>, not <data-extraction-rules>")
    sections = {}
    for child in root:
        entries = set()
        for exclude in child:
            if exclude.tag != "exclude":
                raise ValueError(f"unexpected <{exclude.tag}> in <{child.tag}>")
            domain = exclude.get("domain")
            path = exclude.get("path")
            if not domain or not path:
                raise ValueError(f"exclude without domain/path in <{child.tag}>")
            entries.add((domain, path))
        sections[child.tag] = entries
    return sections


def check(rules_xml: str, main_activity: str, settings: str, atomic: str):
    """All problems with the given sources (empty = pass)."""
    required, problems = derive_requirements(main_activity, settings, atomic)
    try:
        sections = parse_sections(rules_xml)
    except (ET.ParseError, ValueError) as e:
        return problems + [f"rules XML: {e}"]
    if set(sections) != {"cloud-backup", "device-transfer"}:
        problems.append(
            f"sections are {sorted(sections)}, expected cloud-backup + device-transfer")
        return problems
    cloud = sections["cloud-backup"]
    transfer = sections["device-transfer"]
    if cloud != transfer:
        problems.append(
            "sections differ: cloud-only "
            f"{sorted(cloud - transfer)}, transfer-only {sorted(transfer - cloud)}")
    for label, entries in (("cloud-backup", cloud), ("device-transfer", transfer)):
        for domain, path in sorted(required - entries):
            problems.append(f"{label}: missing exclude {domain} {path}")
    return problems


def run(rules_path: Path, main_path: Path, settings_path: Path, atomic_path: Path) -> int:
    problems = check(
        rules_path.read_text(encoding="utf-8"),
        main_path.read_text(encoding="utf-8"),
        settings_path.read_text(encoding="utf-8"),
        atomic_path.read_text(encoding="utf-8"),
    )
    if problems:
        for p in problems:
            print(f"verify_backup_rules: {p}")
        return 1
    print("verify_backup_rules: OK")
    return 0


# ---------------------------------------------------------------------------
# Self-test: synthetic sources and rule variants. The check
# must pass the real shape and fail each broken variant for
# the stated reason.

FAKE_MAIN = """
const val HISTORY_FILE_NAME = "history.json"
const val TAB_SESSION_FILE_NAME = "tab-session.json"
const val BOOKMARKS_FILE_NAME = "bookmarks.json"
val target = java.io.File(file.parentFile, file.name + ".corrupt")
"""
FAKE_SETTINGS = 'private const val PREFS = "pageloupe_settings"'
FAKE_ATOMIC = 'val tmp = File(target.parentFile, target.name + ".tmp")'


def fake_rules(excludes):
    body = "".join(
        f'<exclude domain="{d}" path="{p}" />' for d, p in excludes)
    return (
        '<?xml version="1.0" encoding="utf-8"?>'
        "<data-extraction-rules>"
        f"<cloud-backup>{body}</cloud-backup>"
        f"<device-transfer>{body}</device-transfer>"
        "</data-extraction-rules>"
    )


def selftest() -> int:
    required, problems = derive_requirements(FAKE_MAIN, FAKE_SETTINGS, FAKE_ATOMIC)
    assert not problems, problems
    cases = []

    def case(name, rules, main=FAKE_MAIN, settings=FAKE_SETTINGS,
             atomic=FAKE_ATOMIC, expect_ok=True):
        cases.append((name, rules, main, settings, atomic, expect_ok))

    case("complete rules pass", fake_rules(sorted(required)))
    case(
        "a missing .tmp form fails",
        fake_rules(sorted(required - {("file", "history.json.tmp")})),
        expect_ok=False,
    )
    case(
        "a missing .corrupt form fails",
        fake_rules(sorted(required - {("file", "bookmarks.json.corrupt")})),
        expect_ok=False,
    )
    case(
        "a missing sharedpref exclusion fails",
        fake_rules(sorted(required - {("sharedpref", "pageloupe_settings.xml")})),
        expect_ok=False,
    )
    case(
        "a missing datatransport side file fails",
        fake_rules(sorted(
            required - {("database", "com.google.android.datatransport.events-wal")})),
        expect_ok=False,
    )
    asymmetric = (
        '<?xml version="1.0" encoding="utf-8"?>'
        "<data-extraction-rules>"
        "<cloud-backup>"
        + "".join(f'<exclude domain="{d}" path="{p}" />' for d, p in sorted(required))
        + "</cloud-backup>"
        "<device-transfer>"
        + "".join(
            f'<exclude domain="{d}" path="{p}" />'
            for d, p in sorted(required - {("file", "tab-session.json")}))
        + "</device-transfer></data-extraction-rules>"
    )
    case("asymmetric sections fail", asymmetric, expect_ok=False)
    case("unparsable XML fails", "<data-extraction-rules><cloud-backup>", expect_ok=False)
    renamed_main = FAKE_MAIN.replace('"history.json"', '"hist2.json"')
    case(
        "a renamed state file moves the requirement",
        fake_rules(sorted(required)),
        main=renamed_main,
        expect_ok=False,
    )
    case(
        "an underivable name fails closed",
        fake_rules(sorted(required)),
        settings="private const val OTHER = 1",
        expect_ok=False,
    )

    failures = 0
    for name, rules, main, settings, atomic, expect_ok in cases:
        got = check(rules, main, settings, atomic)
        ok = not got
        if ok != expect_ok:
            failures += 1
            print(f"SELFTEST FAIL: {name}: expected ok={expect_ok}, problems={got}")
        else:
            print(f"selftest ok: {name}")
    if failures:
        print(f"verify_backup_rules selftest: {failures} failures")
        return 1
    print(f"verify_backup_rules selftest: all {len(cases)} cases passed")
    return 0


def main(argv):
    if argv == ["--selftest"]:
        return selftest()
    if len(argv) != 4:
        print(__doc__)
        return 2
    return run(*(Path(a) for a in argv))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
