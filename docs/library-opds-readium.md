# Books & Audio for libraries and public institutions

OpenPanel's library feature is designed around open publishing standards rather
than one commercial vendor. It uses the BSD-3-Clause-licensed Readium Kotlin
Toolkit for EPUB, PDF, packaged audiobooks, and standalone audio, and supports
OPDS 1.2 and OPDS 2.0 catalogs for discovery and acquisition.

## Supported workflows

- Import local EPUB, PDF, Readium audiobook, MP3, M4A/M4B, or AAC files with
  Android's system file picker.
- Configure HTTPS OPDS catalogs during setup or in **Admin Panel → Books &
  Audio**.
- Browse approved catalogs from the launcher and download compatible
  open-access publications into OpenPanel's app-private storage.
- Read EPUBs with font-size, light/dark theme, touch-edge, keyboard, and D-pad
  navigation.
- Read PDFs with Readium's PDFium navigator.
- Play audiobooks with play/pause, 30-second skip, seeking, media-volume
  routing, and saved position.
- Resume the most recently opened publication from its stored Readium Locator.

Project Gutenberg is included because it exposes a stable public OPDS feed and
provides public-domain publications. Administrators can add their library's own
OPDS endpoint without modifying the app.

## Lending, authentication, and DRM boundary

OPDS is a discovery/acquisition protocol, not a universal library account. A
catalog entry may advertise a loan, but OpenPanel does not collect patron
credentials or attempt to bypass DRM. Palace, Libby/OverDrive, Hoopla,
cloudLibrary, and similar licensed collections must use an integration and
authentication flow authorized by the library and vendor.

Readium LCP can be added after the deploying institution supplies the licensed
native `liblcp` integration and completes any required certification. Without
that connector, OpenPanel clearly reports that a restricted publication needs
the institution's licensed connector.

## Security and privacy

- Catalogs and publication downloads must use HTTPS, including redirects.
- Catalog responses are capped at 5 MB and publication downloads at 1 GB.
- Files are copied into app-private storage; OpenPanel never grants another app
  broad storage access.
- Catalog credentials are not stored in this release.
- Book metadata and reading positions stay on the device and are excluded from
  Android backup with the rest of OpenPanel's private data.
- Deleting a publication removes both its private file and its metadata.

## Open-source dependencies

- [Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit) —
  BSD-3-Clause.
- [AndroidX Media3](https://github.com/androidx/media) — Apache-2.0.
- [OPDS 2.0 specification](https://specs.opds.io/opds-2.0) — the interoperable
  catalog model used by the feature.

OpenPanel's own library bridge, UI, storage policy, and reader integration are
released under the repository's [MIT License](../LICENSE).
