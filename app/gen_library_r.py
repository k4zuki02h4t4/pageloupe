#!/usr/bin/env python3
"""Generate R.java for vendored library AARs in the manual build.

AGP does this from each AAR's R.txt plus the final link symbols;
this script mirrors that: for every resource a library declares in
its R.txt, emit the field with the value the aapt2 link actually
assigned (read from --output-text-symbols). Fields the link did not
assign (resource not compiled into this build) are skipped, as in
AGP's shrinking of library R classes to the linked symbol table.

Usage:
  gen_library_r.py <symbols.txt> <out_dir> <package> <R.txt> [<package> <R.txt> ...]
"""
import os
import re
import sys

# R.txt placeholder values come in two spellings: most AARs use
# 0x00000000, Google Play services AARs use a bare 0. The value
# itself is never used (the link symbols carry the real one),
# but a line that matches nothing here used to fall through
# silently and shrink the library's R (v0.41.0 review r8).
INT_RE = re.compile(r"^int (\S+) (\S+) (0x[0-9a-fA-F]+|0)$")
HEX_VALUE_RE = re.compile(r"^0x[0-9a-fA-F]+$")
STYLEABLE_RE = re.compile(r"^int\[\] styleable (\S+) \{ ([^}]*) \}$")
STYLEABLE_IDX_RE = re.compile(r"^int styleable (\S+) (\d+)$")


IDENT_RE = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*$")


def check_ident(package, kind, name):
    # Names out of an AAR's R.txt are written into generated Java
    # verbatim; anything that is not a plain Java identifier is
    # refused outright instead of becoming generated code
    # (v0.41.0 review r9).
    if not IDENT_RE.match(name):
        raise SystemExit(f"error: {package}: {kind} name is not a Java "
                         f"identifier: {name!r}")


def parse_symbols(path):
    values = {}
    styleables = {}
    styleable_indices = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            m = STYLEABLE_RE.match(line)
            if m:
                ids = [x.strip() for x in m.group(2).split(",") if x.strip()]
                # The elements are written into the generated
                # Java verbatim, like the names check_ident
                # guards: anything that is not a plain hex
                # literal is refused instead of becoming
                # generated code (run-3 hardening; the scalar
                # path gets the same guarantee from INT_RE).
                for el in ids:
                    if not HEX_VALUE_RE.match(el):
                        raise SystemExit(
                            f"error: styleable {m.group(1)}: element is "
                            f"not a hex literal: {el!r}")
                styleables[m.group(1)] = ids
                continue
            m = STYLEABLE_IDX_RE.match(line)
            if m:
                styleable_indices[m.group(1)] = int(m.group(2))
                continue
            m = INT_RE.match(line)
            if m:
                values[(m.group(1), m.group(2))] = m.group(3)
    return values, styleables, styleable_indices


def gen_r(package, rtxt_path, values, styleables, styleable_indices):
    by_type = {}
    style_arrays = []
    style_indices = []
    unlinked = 0
    unparsed = 0
    with open(rtxt_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            m = STYLEABLE_RE.match(line)
            if m:
                name = m.group(1)
                check_ident(package, "styleable", name)
                if name in styleables:
                    style_arrays.append((name, styleables[name]))
                else:
                    unlinked += 1
                continue
            m = STYLEABLE_IDX_RE.match(line)
            if m:
                name = m.group(1)
                check_ident(package, "styleable index", name)
                # The index must address the MERGED array the
                # link produced, so the value comes from the link
                # symbols, not from the library's own R.txt (whose
                # numbering is library-local). An index the link
                # did not emit is skipped like any unlinked entry.
                if name in styleable_indices:
                    idx = styleable_indices[name]
                    style_indices.append((name, idx))
                else:
                    unlinked += 1
                continue
            m = INT_RE.match(line)
            if m:
                typ, name = m.group(1), m.group(2)
                check_ident(package, "type", typ)
                check_ident(package, "field", name)
                if (typ, name) in values:
                    by_type.setdefault(typ, []).append((name, values[(typ, name)]))
                else:
                    unlinked += 1
            elif line:
                # A non-empty R.txt line in a shape this script
                # does not understand: silently dropping lines
                # is how the Play services libraries lost most
                # of their R (see INT_RE), so this is an error,
                # not a warning (v0.41.0 review r9).
                unparsed += 1
    if unparsed:
        raise SystemExit(f"error: {package}: {unparsed} R.txt lines in an "
                         f"unrecognized shape; refusing to generate a "
                         f"partial R")
    array_lens = {name: len(ids) for name, ids in style_arrays}
    for name, idx in style_indices:
        # The index name is <Styleable>_<attr>; the array it must
        # fit into is the one emitted for the longest matching
        # styleable name, when that array was emitted at all.
        cands = [a for a in array_lens if name.startswith(a + "_")]
        if cands:
            arr = max(cands, key=len)
            if idx >= array_lens[arr]:
                raise SystemExit(f"error: {package}: styleable index {name}="
                                 f"{idx} is outside the linked {arr} array "
                                 f"of {array_lens[arr]} entries")
    if unlinked:
        # Expected for resources this build does not compile in,
        # but never silent: a typo'd package or a dropped res zip
        # would otherwise shrink a library's R without a trace
        # (v0.41.0 review r1).
        print(f"warning: {package}: {unlinked} R.txt entries not in link symbols, skipped",
              file=sys.stderr)
    out = [f"package {package};", "", "public final class R {"]
    for typ in sorted(by_type):
        out.append(f"  public static final class {typ} {{")
        for name, val in by_type[typ]:
            out.append(f"    public static final int {name} = {val};")
        out.append("  }")
    if style_arrays or style_indices:
        out.append("  public static final class styleable {")
        for name, ids in style_arrays:
            joined = ", ".join(ids)
            out.append(f"    public static final int[] {name} = new int[] {{ {joined} }};")
        for name, idx in style_indices:
            out.append(f"    public static final int {name} = {idx};")
        out.append("  }")
    out.append("}")
    return "\n".join(out) + "\n"


def main():
    symbols_path, out_dir = sys.argv[1], sys.argv[2]
    pairs = sys.argv[3:]
    values, styleables, styleable_indices = parse_symbols(symbols_path)
    for i in range(0, len(pairs), 2):
        package, rtxt = pairs[i], pairs[i + 1]
        dest = os.path.join(out_dir, *package.split("."), "R.java")
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "w", encoding="utf-8") as f:
            f.write(gen_r(package, rtxt, values, styleables, styleable_indices))
        print(f"R generated: {package}")


if __name__ == "__main__":
    main()
