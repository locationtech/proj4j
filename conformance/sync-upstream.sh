#!/usr/bin/env bash
#
# sync-upstream.sh — re-vendor the PROJ conformance corpus and its test resources.
#
# Everything under
#     conformance/src/test/resources/gie/
#     conformance/src/test/resources/gigs/
#     conformance/src/test/resources/proj-data/
# is a verbatim copy of PROJ at the pinned revision below. Nothing in those
# directories is hand-edited; run this script to refresh them and regenerate
# the SHA-256 manifest that makes drift detectable.
#
# Usage:
#     ./sync-upstream.sh [PATH_TO_PROJ_CHECKOUT]
#     PROJ_DIR=/somewhere/PROJ ./sync-upstream.sh
#
set -euo pipefail

# ---------------------------------------------------------------------------
# The pin. This is the single source of truth for "which PROJ are we tracking".
# ---------------------------------------------------------------------------
PROJ_REV=9.8.1
PROJ_REV_SHA=f08fa86c478c4bbbf003b1ec751dd84aa6eca486

PROJ_DIR_DEFAULT=/Volumes/git/PROJ
PROJ_DIR="${1:-${PROJ_DIR:-$PROJ_DIR_DEFAULT}}"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
RES="$SCRIPT_DIR/src/test/resources"
MANIFEST="$RES/gie-manifest.sha256"

die() { printf 'sync-upstream.sh: %s\n' "$*" >&2; exit 1; }
note() { printf '  %s\n' "$*"; }

# ---------------------------------------------------------------------------
# 0. Preconditions
# ---------------------------------------------------------------------------
[ -d "$PROJ_DIR/.git" ] || [ -f "$PROJ_DIR/.git" ] \
  || die "not a git checkout: $PROJ_DIR (pass the path as \$1 or set \$PROJ_DIR)"

# The PROJ working tree is normally parked on master, hundreds of commits ahead
# of our target. We never read the working tree; every extraction below goes
# through the tag. Verify the tag still resolves to the SHA we pinned, and stop
# loudly if it does not — a moved tag means the corpus is not what we think.
ACTUAL_SHA="$(git -C "$PROJ_DIR" rev-parse --verify --quiet "${PROJ_REV}^{commit}" || true)"
[ -n "$ACTUAL_SHA" ] || die "revision '${PROJ_REV}' does not exist in $PROJ_DIR"
if [ "$ACTUAL_SHA" != "$PROJ_REV_SHA" ]; then
  die "PIN MISMATCH: ${PROJ_REV}^{commit} in $PROJ_DIR is
    $ACTUAL_SHA
  but this script is pinned to
    $PROJ_REV_SHA
  Refusing to vendor. Either the tag moved or the checkout is a different repo."
fi

# Portable SHA-256: macOS ships shasum/openssl, not GNU coreutils sha256sum.
# All three emit "<hash><sep><path>"; the sed below normalises the binary-mode
# " *path" separator that sha256sum and `openssl -r` use into two spaces.
if command -v shasum >/dev/null 2>&1; then
  SHA_CMD=(shasum -a 256)
elif command -v sha256sum >/dev/null 2>&1; then
  SHA_CMD=(sha256sum)
elif command -v openssl >/dev/null 2>&1; then
  SHA_CMD=(openssl dgst -sha256 -r)
else
  die "no SHA-256 tool found (looked for shasum, sha256sum, openssl)"
fi

echo "PROJ ${PROJ_REV} (${PROJ_REV_SHA})"
echo "  from: $PROJ_DIR"
echo "  into: $RES"

# ---------------------------------------------------------------------------
# 1. The .gie corpus: test/gie -> gie/, test/gigs -> gigs/
#
# Delete-then-extract, so a file deleted upstream also disappears here.
#
# NOT VENDORED: test/gie/tinshift_gpkg.gie and test/gie/tinshift_gpkg_network.gie.
# Those two exist only on PROJ master; they were added after 9.8.1 and are out of
# scope for this migration. They are absent from the archive below by
# construction — the assertion after the extraction keeps that honest, so if a
# future re-pin pulls them in, this script fails rather than silently widening
# the corpus.
# ---------------------------------------------------------------------------
echo
echo "[1/4] .gie corpus"
rm -rf "$RES/gie" "$RES/gigs"
mkdir -p "$RES/gie" "$RES/gigs"

git -C "$PROJ_DIR" archive "$PROJ_REV" test/gie \
  | tar -x -C "$RES/gie" --strip-components=2
git -C "$PROJ_DIR" archive "$PROJ_REV" test/gigs \
  | tar -x -C "$RES/gigs" --strip-components=2

for forbidden in tinshift_gpkg.gie tinshift_gpkg_network.gie; do
  if [ -e "$RES/gie/$forbidden" ]; then
    die "$forbidden was vendored; it is master-only and out of scope"
  fi
done
note "gie/  $(find "$RES/gie" -type f | wc -l | tr -d ' ') files"
note "gigs/ $(find "$RES/gigs" -type f -name '*.gie' | wc -l | tr -d ' ') active" \
     "+ $(find "$RES/gigs" -type f -name '*.gie.failing' | wc -l | tr -d ' ') quarantined"

# ---------------------------------------------------------------------------
# 2. Test resources: a faithful reproduction of the `for_tests` tree that
#    9.8.1:data/CMakeLists.txt builds. Upstream copies a *whitelist* into
#    data/for_tests/ and points every CTest at it via PROJ_DATA, with the
#    comment "so that we are not influenced by the presence of other grids".
#    We mirror that layout under proj-data/ for the same reason.
#
#    The one thing we do NOT reproduce is proj.db: upstream generates it from
#    data/sql/*.sql at build time and copies it into for_tests/. It is ~10 MB
#    and is not a checked-in artifact upstream, so it is not vendored here.
# ---------------------------------------------------------------------------
echo
echo "[2/4] proj-data resources (9.8.1:data/CMakeLists.txt for_tests whitelist)"
rm -rf "$RES/proj-data"
mkdir -p "$RES/proj-data"

# 2a. DATA_FOR_TESTS + PROJ_INI: init dictionaries and the runtime ini, from
#     the top of data/ into the root of for_tests/.
git -C "$PROJ_DIR" archive "$PROJ_REV" \
      data/GL27 data/nad27 data/nad83 data/ITRF2000 data/proj.ini \
  | tar -x -C "$RES/proj-data" --strip-components=1

# 2b. file(GLOB DATA_TESTS tests/*) — the whole of data/tests/, flat, preserving
#     the tests/ subdirectory exactly as CMake does.
git -C "$PROJ_DIR" archive "$PROJ_REV" data/tests \
  | tar -x -C "$RES/proj-data" --strip-components=1

# 2c. DATA_FOR_TESTS_FROM_TESTS_SUBDIR — six grids promoted from tests/ to the
#     for_tests/ root, where +nadgrids=/+geoidgrids= will find them by bare name.
for f in alaska BETA2007.gsb conus MD ntf_r93.gsb ntv1_can.dat; do
  [ -f "$RES/proj-data/tests/$f" ] || die "expected data/tests/$f at $PROJ_REV"
  cp "$RES/proj-data/tests/$f" "$RES/proj-data/$f"
done

# 2d. Two promotions that are also renames. The in-tree files are reduced
#     versions of the real grids, but the tests reference the production names.
cp "$RES/proj-data/tests/egm96_15_downsampled.gtx" "$RES/proj-data/egm96_15.gtx"
cp "$RES/proj-data/tests/ntv2_0_downsampled.gsb"   "$RES/proj-data/ntv2_0.gsb"

# 2e. The deliberately awkward path. Upstream's comment: "test_cs2cs_datumfile
#     has a special case". The space in the directory name is the point of the
#     fixture — it exercises path handling — so keep it verbatim.
mkdir -p "$RES/proj-data/dir with space"
cp "$RES/proj-data/tests/conus" "$RES/proj-data/dir with space/myconus"

note "proj-data/ $(find "$RES/proj-data" -type f | wc -l | tr -d ' ') files"

# ---------------------------------------------------------------------------
# 3. Manifest of every vendored file, so drift is a diff and not a surprise.
# ---------------------------------------------------------------------------
echo
echo "[3/4] manifest"
rm -f "$MANIFEST"
{
  printf '# SHA-256 of every file vendored from PROJ %s (%s)\n' \
         "$PROJ_REV" "$PROJ_REV_SHA"
  printf '# Generated by conformance/sync-upstream.sh. Do not edit by hand.\n'
  printf '# Paths are relative to conformance/src/test/resources/.\n'
  (
    cd "$RES"
    find gie gigs proj-data -type f -print0 \
      | LC_ALL=C sort -z \
      | xargs -0 "${SHA_CMD[@]}" \
      | sed 's/ \*/  /'
  )
} > "$MANIFEST"
note "$(grep -cv '^#' "$MANIFEST" | tr -d ' ') hashed files -> ${MANIFEST#"$SCRIPT_DIR"/}"

# ---------------------------------------------------------------------------
# 4. Summary
# ---------------------------------------------------------------------------
summarise() {
  local dir="$1" label="$2" count bytes
  count=$(find "$RES/$dir" -type f | wc -l | tr -d ' ')
  bytes=$(find "$RES/$dir" -type f -print0 | xargs -0 cat | wc -c | tr -d ' ')
  printf '  %-28s %5s files  %12s bytes\n' "$label" "$count" "$bytes"
}

echo
echo "[4/4] summary"
summarise gie "gie/"
summarise gigs "gigs/"
summarise proj-data "proj-data/"
summarise proj-data/tests "  of which proj-data/tests/"
printf '  %-28s %5s files  %12s bytes\n' "TOTAL" \
  "$(cd "$RES" && find gie gigs proj-data -type f | wc -l | tr -d ' ')" \
  "$(cd "$RES" && find gie gigs proj-data -type f -print0 | xargs -0 cat | wc -c | tr -d ' ')"
echo
echo "Done. Licence obligations for this corpus are in"
echo "  ${RES#"$SCRIPT_DIR"/}/NOTICE-gie.md  — read it before redistributing."
