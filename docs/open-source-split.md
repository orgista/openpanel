# Open-source split: engine + UI

OpenPanel is MIT-licensed. The native kiosk engine/build tooling and React UI
are versioned in separate repositories. The public CyberBanksy mirrors are
**`cyberbanksy/openpanel`** and **`cyberbanksy/openpanel-ui`**. There is a single app: the Capacitor app
`com.orgista.openpanel` (the legacy AOSP launcher and the ArborXR SDK were
removed).

## What goes where

| Path | Repo | Notes |
| --- | --- | --- |
| `android/` (Capacitor native, `SystemBridgePlugin`, `OpenPanelDeviceAdminReceiver`, manifest, gradle) | **public** `orgista/openpanel` | The kiosk engine + native bridge |
| `scripts/`, `docs/`, build config (`package.json`, `vite.config.ts`, `capacitor.config.ts`, `tsconfig.json`, `index.html`, `postcss.config.mjs`) | **public** `orgista/openpanel` | The UI builds against these |
| `.github/workflows/` | **public** `orgista/openpanel` | Engine CI plus protected full-release verification |
| `src/` (entire React app + TS bridge bindings, styles, assets) | **public** `cyberbanksy/openpanel-ui` | MIT UI source — **git-ignored here** because it is a nested repository |

The UI repo's root maps 1:1 onto `src/` and is excluded here via `.gitignore`
(`/src/`). To build the full app, clone
`https://github.com/cyberbanksy/openpanel-ui.git` to `src/`. That keeps `vite`,
`index.html` → `/src/main.tsx`, and `tsconfig` working unchanged. It can also be
wired as a git submodule at `src/`.

## Wiring the UI as a submodule (optional)

The UI repo exists independently (in the local working tree,
`src/` is a nested git repo tracking it — push UI changes from there). To
formalize it as a submodule of the public repo:

```sh
# 1. Wire it in as a submodule at src/
#    (first remove the /src/ ignore line from .gitignore)
git submodule add https://github.com/cyberbanksy/openpanel-ui.git src
git commit -am "build: add UI submodule at src/"
git push
```

Clone for development: `git clone --recurse-submodules <url>`. A public UI needs
no checkout token. Keep `UI_SUBMODULE_TOKEN` only when a private upstream mirror
is intentionally selected in CI.

## What is NOT published (git-ignored)

`/src/` (separate UI checkout), keystores (`*.keystore`), `.env*`, `Samples/` (commercial
Fully Kiosk APKs — do not redistribute), `*.apk` / `*.zip`, `dist/`, `build/`,
`node_modules/`, `.toolchains/`, `local.properties`, `guidelines/` (design
template), and `docs/_archive/` (retired internal/research notes).

Verify before any push: `git ls-files | grep -iE 'keystore|\.env|password|secret'`
must be empty.

## Release signing

The checked-in `.github/workflows/android.yml` tests, lints, and assembles the
public engine. `.github/workflows/release-verification.yml` checks out the UI
at the exact commit in the `OPENPANEL_UI_REF` repository variable,
then verifies a complete signed release without publishing the APK. It requires
`UI_SUBMODULE_TOKEN` plus these signing secrets:

```sh
PASS="$(security find-generic-password -a openpanel-production-v2 -s "OpenPanel production signing key v2" -w)"
REPO="orgista/openpanel"
KEYSTORE="/Volumes/Files/Projects/Code, Apps & Websites/Apps/OpenPanel/private/signing/openpanel-production.keystore"
base64 -i "$KEYSTORE" | gh secret set RELEASE_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$PASS"       | gh secret set OPENPANEL_KEYSTORE_PASS --repo "$REPO"
printf '%s' "$PASS"       | gh secret set OPENPANEL_KEY_PASS      --repo "$REPO"
printf 'openpanel-production-v2' | gh secret set OPENPANEL_KEY_ALIAS --repo "$REPO"
```

In CI, `RELEASE_KEYSTORE_BASE64` should be decoded into a temporary file and
`OPENPANEL_KEYSTORE_FILE` should be exported to that temporary path at runtime.
Do not commit the keystore or write signing passwords into Gradle files.
Keep the production Keychain item and independent recovery copy current. The
legacy credential is not valid for releases signed with the replacement key.
