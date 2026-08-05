# Manifest fixtures

These files are **test fixtures**, not the live baseline. Nothing here is read by the conformance
gate; everything here is read by the unit tests in
`org.locationtech.proj4j.conformance.manifest` and `…conformance.report`.

| file | what it is |
|---|---|
| `expected-failures.seed.tsv` | an illustrative manifest, pinning the format and carrying the triage text for the ten quarantined GIGS `.gie.failing` files |
| `corpus-index.seed.tsv` | the matching corpus index (all keys that existed when the manifest was written, including passing ones) |
| `malformed-missing-column.tsv` | two columns instead of three — must be rejected, naming the line |
| `malformed-pass-row.tsv` | records a `PASS` — must be rejected |
| `malformed-bad-key.tsv` | truncated content hash — must be rejected |

## The real manifest is generated

The live manifest is **not** hand-written and is **not** in this directory. It is produced from an
actual run:

```
mvn -Pconformance verify                        # gate: fails on REGRESSED / UNEXPECTED_PASS / DISAPPEARED
mvn -Pconformance verify -Dgie.regenerate=true  # accept the current state as the new baseline
```

Regeneration writes the manifest and its corpus index together. Both must be committed together, or
the next diff reports phantom `NEW`/`DISAPPEARED` entries.

The content hashes in `expected-failures.seed.tsv` are **fabricated**. Real hashes are the first 8
hex digits of a SHA-1 over the normalised assertion text plus the normalised operation definition,
and can only be computed by lexing the corpus. Do not copy a line from the seed into a real
manifest: the key will match nothing, and the gate will (correctly) report it as `DISAPPEARED`.

## Format

Tab-separated, three columns, `#` comments, an optional `key	expected	reason` header, sorted
in the canonical key order (path, operation block, assertion index, hash).

```
gigs/5110.gie.failing#2:0@b4c5d6e7	FAIL	laea ellipsoidal inverse: roundtrip 1000 drift …
└──────── file ────────┘ │  │ └─ hash ─┘  └ outcome ┘  └─ free-text reason ─┘
                         │  └─ assertion index within the block
                         └──── operation-block index within the file
```

Rules that the parser enforces and that matter when hand-editing:

- **Only non-`PASS` rows.** An absent key means "expected to pass". The file is meant to shrink.
- **`FAIL` or `SKIP`, upper case.** A `SKIP` is never a pass; `require_grid` and `ignore` produce it.
- **No duplicate keys**, and no tabs or newlines inside a reason.
- **Never hand-sort.** Regeneration emits the canonical order; a hand-sorted file just makes a noisy
  diff on the next regeneration.

## Reasons survive regeneration

`ManifestRegenerator` copies the previous `reason` forward for every key whose expected outcome is
unchanged. Write the reason properly — it is the artefact that makes the next triage cheap. The
useful shape is *root cause, evidence, and whether it is ours*:

> `syntax artefact, not maths: no trailing " \" continuation, so +step lines are swallowed as
> comments and the operation degenerates to a bare +proj=pipeline`

Nine of the ten quarantined GIGS files fail for exactly that reason and should not be chased as
numerical work. `5110` is the genuine one — a `laea` ellipsoidal inverse whose point checks pass at
`tolerance 0.05 m` and whose `roundtrip 1000` blocks fail at `0.006 m`, drifting from 105 mm at
lat 70 to 1,312 mm at lat 30. `5101.4-jhs-etmerc.gie` (not `.failing`) is in the passing set and must
stay a positive test.
