# OpenPanel Storage Layout

## Canonical location

All durable OpenPanel data belongs under this NAS folder:

`/Volumes/Files/Projects/Code, Apps & Websites/Apps/OpenPanel`

That folder contains the public engine repository, the private UI repository
at `src/`, source assets, screenshots, local reference material, documentation,
and project-specific toolchains. The convenience path at
`~/Documents/Files/Projects/Apps/OpenPanel` is only a symbolic link to this NAS
folder; it is not a second copy.

NPM's project cache is configured as `.npm-cache/`, so future package downloads
remain under the canonical folder. `node_modules/`, build outputs, and other
generated files also live below the project root when created there.

## Android build exception

Gradle cannot keep its live cache on this SMB share. macOS reports
`Operation not supported` when Gradle tries to acquire the native file lock.
The local helper therefore uses one disposable folder named
`openpanel-gradle-cache` under macOS's temporary directory. By default it is
deleted automatically when the build exits.

For repeated local builds, set `OPENPANEL_KEEP_LOCAL_CACHE=1` to retain that
cache temporarily, then remove it with:

```sh
npm run storage:clean-local
```

The Android SDK, JDK, Git credentials, and npm's global installation are shared
machine tools rather than OpenPanel-owned data. CI signing copies remain in the
protected CI environment; the local production upload key is stored in the
ignored, owner-only `private/signing/` area described below.

Run `npm run storage:audit` to confirm the canonical location and detect
OpenPanel-named files or folders in common local and temporary locations.

## Private records

Non-sensitive historical outputs now live physically under
`private/external-records/`. Their former Raster and Unraid paths are symbolic
links, so those systems can still read the same checksummed files without
keeping duplicate data.

Strict consolidation places sensitive OpenPanel data in these ignored,
owner-only locations:

- `private/signing/openpanel-production.keystore` — the active replacement
  signing key for `com.orgista.openpanel`. Its password is stored in macOS
  Keychain and in an independent owner-controlled recovery vault.
- `private/signing/openpanel-upload.keystore` — the inaccessible legacy key for
  `com.orgista.openpanel`. `~/openpanel-upload.keystore` remains a compatibility
  symlink to this preserved legacy file.
- `private/agent-state/claude/current/` — current OpenPanel-specific Claude
  project state.
- `private/agent-state/claude/archive-20260718/` — archived OpenPanel-specific
  Claude project state.

The former ServerPlus project-state paths are compatibility symlinks into this
folder, so Claude can keep using them without maintaining separate copies.
ServerPlus's global Claude indexes and Codex chat/process archives are shared
multi-project service records; they remain with those services rather than
being misclassified as dedicated OpenPanel data. The exposed OpenPanel signing
credential was redacted from the affected shared records and from the relocated
project histories. Verify this without printing the credential by running:

```sh
node scripts/redact-openpanel-agent-secrets.mjs --verify
```

Other products and operations reports may legitimately mention OpenPanel—for
example, the Orgista website, ArborXR deployment tooling, AI Ops audits, and
shared chat archives. Those cross-project references stay with their owning
systems. The storage audit enforces consolidation of OpenPanel-owned files and
dedicated records, not removal of every textual reference to the product.

The exposed legacy password is not used for new releases. Never copy signing
passwords into documentation, tickets, chat, or command output.
