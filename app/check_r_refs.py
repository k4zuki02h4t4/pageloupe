#!/usr/bin/env python3
"""Cross-check library R references against the generated R classes.

Library bytecode built by AGP reads its own R fields at runtime
(library R fields are non-final there, so the references are real
Fieldrefs, not inlined constants). If a referenced R type or field
was not generated -- the AAR missing from the res list, or the
generator skipping an entry -- the failure is a
NoClassDefFoundError / NoSuchFieldError at first use, on device,
not at build time (this is how ML Kit's R$xml slipped through in
v0.41.0). This script makes the build fail instead:

- referenced side: every Fieldref to a */R$<type> class found in
  the constant pools of the vendored jars (the framework's own
  android.R is exempt: it lives in the boot classpath);
- defined side: the fields declared by the generated R.java
  sources, plus the .class files javac actually emitted.

Usage:
  check_r_refs.py <classes_dir> <gen_dir>... -- <jar>...
  check_r_refs.py --dex <classes.dex>... -- <jar>...
    (second form: assert every referenced R type is DEFINED in
    the final dex set, closing the jar/D8 leg of the pipeline)
"""
import os
import re
import struct
import sys
import zipfile

R_CLASS_RE = re.compile(r"^(.+)/R\$([a-zA-Z0-9_]+)$")
GEN_TYPE_RE = re.compile(r"public static final class ([a-zA-Z0-9_]+) \{")
GEN_FIELD_RE = re.compile(r"public static final (?:int|int\[\]) ([a-zA-Z0-9_]+)")


def fieldrefs_in_class(data):
    """Yield (class_internal_name, field_name) for every Fieldref
    in a .class file's constant pool whose class is an R$ type."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        return
    cp_count = struct.unpack(">H", data[8:10])[0]
    pos = 10
    utf8 = {}
    classes = {}
    nat = {}
    refs = []
    i = 1
    while i < cp_count:
        tag = data[pos]
        pos += 1
        if tag == 1:  # Utf8
            ln = struct.unpack(">H", data[pos:pos + 2])[0]
            utf8[i] = data[pos + 2:pos + 2 + ln].decode("utf-8", "replace")
            pos += 2 + ln
        elif tag == 7:  # Class
            classes[i] = struct.unpack(">H", data[pos:pos + 2])[0]
            pos += 2
        elif tag == 9:  # Fieldref
            refs.append(struct.unpack(">HH", data[pos:pos + 4]))
            pos += 4
        elif tag == 12:  # NameAndType
            nat[i] = struct.unpack(">HH", data[pos:pos + 4])
            pos += 4
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        elif tag in (8, 16, 19, 20):
            pos += 2
        elif tag in (10, 11, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        elif tag == 17:
            pos += 4
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        i += 1
    for class_idx, nat_idx in refs:
        class_name = utf8.get(classes.get(class_idx, -1), "")
        if not R_CLASS_RE.match(class_name):
            continue
        name_idx, _desc_idx = nat.get(nat_idx, (-1, -1))
        field = utf8.get(name_idx, "")
        if field:
            yield class_name, field


def referenced_fields(jars):
    refs = {}  # (package_path, type) -> set(field)
    for jar in jars:
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if not name.endswith(".class"):
                    continue
                for class_name, field in fieldrefs_in_class(z.read(name)):
                    m = R_CLASS_RE.match(class_name)
                    pkg, typ = m.group(1), m.group(2)
                    if pkg == "android":
                        continue  # the framework's own R
                    refs.setdefault((pkg, typ), set()).add(field)
    return refs


def defined_fields(gen_dirs):
    """(package_path, type) -> set(field), from generated R.java."""
    defined = {}
    for gen_dir in gen_dirs:
        for root, _dirs, files in os.walk(gen_dir):
            for fn in files:
                if fn != "R.java":
                    continue
                path = os.path.join(root, fn)
                pkg = os.path.relpath(root, gen_dir).replace(os.sep, "/")
                current = None
                with open(path, encoding="utf-8") as f:
                    for line in f:
                        tm = GEN_TYPE_RE.search(line)
                        if tm:
                            current = tm.group(1)
                            defined.setdefault((pkg, current), set())
                            continue
                        fm = GEN_FIELD_RE.search(line)
                        if fm and current:
                            defined[(pkg, current)].add(fm.group(1))
    return defined


def dex_defined_classes(path):
    """Class descriptors defined by a dex file (its class_defs)."""
    with open(path, "rb") as f:
        data = f.read()
    if data[:3] != b"dex":
        raise ValueError("not a dex file")

    def u32(off):
        return struct.unpack("<I", data[off:off + 4])[0]

    string_ids_size, string_ids_off = u32(56), u32(60)
    type_ids_off = u32(68)
    class_defs_size, class_defs_off = u32(96), u32(100)

    def read_string(idx):
        off = u32(string_ids_off + idx * 4)
        # uleb128 length, then the bytes (MUTF-8; R names are ASCII)
        shift = 0
        ln = 0
        while True:
            b = data[off]
            off += 1
            ln |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                break
        return data[off:off + ln].decode("utf-8", "replace")

    defined = set()
    for i in range(class_defs_size):
        class_idx = u32(class_defs_off + i * 32)
        descriptor_idx = u32(type_ids_off + class_idx * 4)
        defined.add(read_string(descriptor_idx))
    return defined


USAGE = ("usage: check_r_refs.py <classes_dir> <gen_dir>... -- <jar>...\n"
         "       check_r_refs.py --dex <classes.dex>... -- <jar>...")


def main():
    argv = sys.argv[1:]
    # Shape validation first, before the (comparatively) heavy
    # jar scan: a malformed invocation should fail fast and read
    # as a usage error, not as a check failure (review r11).
    if "--" not in argv or not argv:
        print(USAGE, file=sys.stderr)
        return 2
    sep = argv.index("--")
    dex_mode = argv[0] == "--dex"
    if dex_mode and sep < 2:
        print(USAGE, file=sys.stderr)
        return 2
    if not dex_mode and (sep < 2 or argv[0].startswith("--")):
        print(USAGE, file=sys.stderr)
        return 2
    if dex_mode:
        for dex_path in argv[1:sep]:
            if not os.path.isfile(dex_path):
                print(f"R dex check failed: no such dex file: {dex_path}",
                      file=sys.stderr)
                return 1
    jars = argv[sep + 1:]
    refs = referenced_fields(jars)
    if not refs:
        # An empty scan means the gate itself is broken (bad jar
        # list, parser regression); never let it pass silently.
        print("R reference check failed: no R references found in the "
              "given jars; the scan itself must be broken", file=sys.stderr)
        return 1

    if dex_mode:
        defined = set()
        for dex_path in argv[1:sep]:
            defined |= dex_defined_classes(dex_path)
        missing = [f"{pkg}/R${typ}" for (pkg, typ) in sorted(refs)
                   if f"L{pkg}/R${typ};" not in defined]
        for m in missing:
            print(f"R reference check failed: {m} is referenced but not "
                  f"defined in the dex", file=sys.stderr)
        if missing:
            return 1
        print(f"R dex check ok: {len(refs)} referenced R types defined "
              f"in the dex")
        return 0

    classes_dir, gen_dirs = argv[0], argv[1:sep]
    defined = defined_fields(gen_dirs)

    problems = []
    for (pkg, typ), fields in sorted(refs.items()):
        if (pkg, typ) not in defined:
            problems.append(f"{pkg}/R${typ}: referenced by a vendored jar "
                            f"but not generated")
            continue
        for field in sorted(fields - defined[(pkg, typ)]):
            problems.append(f"{pkg}/R${typ}.{field}: referenced but not "
                            f"in the generated R")
        cls = os.path.join(classes_dir, pkg, f"R${typ}.class")
        if not os.path.isfile(cls):
            problems.append(f"{pkg}/R${typ}: class file missing from "
                            f"{classes_dir} (javac did not emit it)")

    if problems:
        for p in problems:
            print(f"R reference check failed: {p}", file=sys.stderr)
        return 1
    print(f"R reference check ok: {len(refs)} referenced R types verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
