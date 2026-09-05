# NAR Corpus Metadata Resolver Design

**Status:** Approved for the metadata-only checkpoint on 2026-08-17.

## Goal

Create a local, synthetic-fixture-tested resolver that turns catalog rows and
already-observed metadata into a deterministic acquisition ledger. It must not
download, open, hash, inspect, cache, or execute NAR payloads.

## Scope

The resolver accepts supplied catalog rows and metadata observations; it never
makes an HTTP request. Its output gives every row one acquisition disposition:

- `nar-downloadable`
- `manifest-only`
- `unavailable`
- `duplicate-catalog-record`
- `permission-excluded`

The initial implementation is a PowerShell command because the existing corpus
orchestration is PowerShell. A small module owns normalization, policy, and
canonical JSON rendering; a command script reads fixture JSON and writes the
ledger. The command has an explicit `-FixturePath` input and rejects
payload-shaped data before rendering output.

## Inputs and outputs

Fixture input uses only JSON catalog rows and metadata evidence. A row has its
snapshot identity, ordinal, title, author, update time, and landing URL. An
evidence record has only public metadata: URL, observed timestamp, HTTP method
and status, redirect chain, robots/terms result, and the presence of a
title-specific initial-NAR link. No response body, archive bytes, local archive
path, or archive digest is accepted.

The generated ledger is canonical UTF-8 JSON with stable row ordering by
snapshot ID and ordinal. Each record carries a stable catalog row ID, an
optional canonical-record ID and duplicate pointer, exactly one disposition,
reason code, confidence, and public evidence URLs. It has no wall-clock
generation time, machine path, local cache key, payload hash, or acquisition
field. A future approved downloader will write a separate, access-controlled
artifact ledger rather than extending this output with local paths.

## Policy

The resolver prefers exclusion over acquisition. A record is
`permission-excluded` when robots, terms, author notice, personal-use marker,
or an access boundary rules out automated retrieval. It is a duplicate only
when title, author, and canonical landing URL all match a prior snapshot row;
the earliest ordinal is canonical. It is `nar-downloadable` only when a public
title-specific initial-NAR link has already been observed and no exclusion
applies. A catalog manifest without that link is `manifest-only`; no usable
public endpoint is `unavailable`.

The implementation deliberately does not model retries, crawling, redirects,
user agents, caching, or download state. Those require a separately reviewed
networked acquisition checkpoint.

## Safety invariants

- Fixture validation rejects archive payload fields (`narPath`, `archivePath`,
  `sha256`, `bytes`, and `objectKey`) and `.nar` fixture references.
- No network cmdlet, archive API, process launcher, or Android/emulator command
  is invoked by the module or command.
- Every output row has one of the five approved dispositions.
- A permission exclusion wins over an otherwise observed initial-NAR link.
- Duplicate rows preserve snapshot provenance and are never discarded.
- The command writes only its explicit generated JSON destination.

## Verification

Synthetic fixtures cover each disposition, exclusion precedence, duplicate
canonicalization, stable output across input-order changes, and rejected
payload-shaped fixture data. Tests execute the command and inspect generated
JSON; they never inspect source text or call external services.

## Non-goals

This checkpoint does not download real ghosts, resolve live sites, create a
cache, run archive preflight, install or execute SHIORI, change the pinned
23-row corpus, or publish a real catalog snapshot.
