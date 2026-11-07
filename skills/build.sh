#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Builds the two skill packages from skills/ into target/skills/dist/.
#
#   spring-mcp-skill.zip   free    — the skill itself
#   spring-mcp-audit.zip   paid    — audit workflow + report template, on top of
#                                    the same reference/ and scripts/
#
# Layout inside each zip: one top-level folder with SKILL.md at its root, which
# is what the marketplace and the client both expect.
#
#   bash skills/build.sh
# ---------------------------------------------------------------------------
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
STAGE="$ROOT/target/skills/stage"
DIST="$ROOT/target/skills/dist"

rm -rf "$STAGE" "$DIST"
mkdir -p "$STAGE/free" "$STAGE/paid" "$DIST"

# --- free ------------------------------------------------------------------
cp -R "$ROOT/skills/spring-mcp" "$STAGE/free/spring-mcp"
cp "$ROOT/skills/INSTALL.md" "$STAGE/free/spring-mcp/INSTALL.md"
cp "$ROOT/skills/CLIENT-SETUP.md" "$STAGE/free/spring-mcp/CLIENT-SETUP.md"

# --- paid ------------------------------------------------------------------
# Same reference/ and scripts/ as the free skill, different SKILL.md, plus the
# report template. One copy of the tool docs means the two packages cannot drift.
mkdir -p "$STAGE/paid/spring-mcp-audit/reference"
cp "$ROOT/skills/spring-mcp-audit/SKILL.md" "$STAGE/paid/spring-mcp-audit/SKILL.md"
cp -R "$ROOT/skills/spring-mcp/reference/." "$STAGE/paid/spring-mcp-audit/reference/"
cp -R "$ROOT/skills/spring-mcp/scripts" "$STAGE/paid/spring-mcp-audit/scripts"
cp "$ROOT/skills/spring-mcp-audit/reference/report-template.md" \
   "$STAGE/paid/spring-mcp-audit/reference/report-template.md"
cp "$ROOT/skills/INSTALL.md" "$STAGE/paid/spring-mcp-audit/INSTALL.md"
cp "$ROOT/skills/CLIENT-SETUP.md" "$STAGE/paid/spring-mcp-audit/CLIENT-SETUP.md"

# --- normalize + zip -------------------------------------------------------
# .cmd must keep CRLF after packaging; the zip is built from the working tree,
# which a fresh checkout may have produced with different endings.
python - "$STAGE" "$DIST" <<'PY'
import hashlib, os, shutil, sys, zipfile

stage, dist = sys.argv[1], sys.argv[2]

for name in ("free", "paid"):
    for dirpath, _, files in os.walk(os.path.join(stage, name)):
        for f in files:
            if f.endswith((".cmd", ".bat")):
                p = os.path.join(dirpath, f)
                b = open(p, "rb").read().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
                open(p, "wb").write(b)

for src, archive, folder in (
    ("free/spring-mcp", "spring-mcp-skill.zip", "spring-mcp"),
    ("paid/spring-mcp-audit", "spring-mcp-audit.zip", "spring-mcp-audit"),
):
    out = os.path.join(dist, archive)
    base = os.path.join(stage, src)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for dirpath, _, files in os.walk(base):
            for f in sorted(files):
                full = os.path.join(dirpath, f)
                rel = os.path.relpath(full, base).replace(os.sep, "/")
                z.write(full, f"{folder}/{rel}")

    with zipfile.ZipFile(out) as z:
        names = z.namelist()
        assert f"{folder}/SKILL.md" in names, f"{archive}: SKILL.md not at root"
        assert all(n.startswith(folder + "/") for n in names), f"{archive}: stray entry"
        binary = [n for n in names if n.endswith((".jar", ".exe", ".class"))]
        assert not binary, f"{archive}: binary payload {binary}"
        zh = hashlib.sha256(open(out, "rb").read()).hexdigest()
    print(f"{archive:24} {os.path.getsize(out):>7} bytes  {len(names):>2} files  sha256 {zh[:16]}")
    for n in names:
        print(f"    {n}")
PY

echo
echo "zips in target/skills/dist/"
