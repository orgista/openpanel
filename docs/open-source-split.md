# Open-source split: public engine + private UI

OpenPanel is **open-core**. The native kiosk engine and build tooling are public
(this repo, **`orgista/openpanel`**); the polished React UI is kept in a separate
**private** repo. There is a single app: the Capacitor app
`com.orgista.openpanel` (the legacy AOSP launcher and the ArborXR SDK were
removed).

## What goes where

| Path | Repo | Notes |
| --- | --- | --- |
| `android/` (Capacitor native, `SystemBridgePlugin`, `OpenPanelDeviceAdminReceiver`, manifest, gradle) | **public** `orgista/openpanel` | The kiosk engine + native bridge |
| `scripts/`, `docs/`, `guidelines/`, build config (`package.json`, `vite.config.ts`, `capacitor.config.ts`, `tsconfig.json`, `index.html`, `postcss.config.mjs`) | **public** `orgista/openpanel` | The UI builds against these |
| `.github/workflows/` | **public** `orgista/openpanel` | CI |
| `src/` (entire React app + TS bridge bindings, styles, assets) | **private** (separate repo) | The gated UI — **git-ignored here**, not in the public repo |

The UI currently lives only in the local working tree and is excluded via
`.gitignore` (`/src/`). To build the full app, place the private UI at `src/`
(that keeps `vite`, `index.html` → `/src/main.tsx`, and `tsconfig` working
unchanged). It can later be wired as a git submodule at `src/` — see below.

## Adding the private UI repo (optional, later)

The public engine repo already exists and is pushed. To formalize the private UI
as a submodule:

```sh
# 1. Private UI repo from the current src/
cd src && git init && git add . && git commit -m "OpenPanel UI"
gh repo create orgista/openpanel-ui --private --source=. --push && cd ..

# 2. Wire it into the public repo as a submodule at src/
#    (first remove the /src/ ignore line from .gitignore)
git submodule add git@github.com:orgista/openpanel-ui.git src
git commit -am "Add private UI submodule at src/"
git push

# 3. Let CI read the private submodule
gh secret set UI_SUBMODULE_TOKEN --repo orgista/openpanel   # PAT w/ read on openpanel-ui
```

Clone for development (with UI access): `git clone --recurse-submodules <url>`.
A contributor without UI access gets a buildable open engine but no bundled UI.

## What is NOT published (git-ignored)

`/src/` (private UI), keystores (`*.keystore`), `.env*`, `Samples/` (commercial
Fully Kiosk APKs — do not redistribute), `*.apk` / `*.zip`, `dist/`, `build/`,
`node_modules/`, `.toolchains/`, `local.properties`, and `docs/_archive/`
(retired internal/research notes).

Verify before any push: `git ls-files | grep -iE 'keystore|\.env|password|secret'`
must be empty.

## Release signing (CI)

`.github/workflows/android.yml` builds a debug APK on every push and a **signed
release APK on a `v*` tag**. Release signing reads these repo secrets:

```sh
PASS="$(security find-generic-password -a openpanel-upload -s "OpenPanel upload keystore password" -w)"
REPO="orgista/openpanel"
base64 -i ~/openpanel-upload.keystore | gh secret set RELEASE_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$PASS"       | gh secret set OPENPANEL_KEYSTORE_PASS --repo "$REPO"
printf '%s' "$PASS"       | gh secret set OPENPANEL_KEY_PASS      --repo "$REPO"
printf 'openpanel-upload' | gh secret set OPENPANEL_KEY_ALIAS     --repo "$REPO"
```
Cut a release with `git tag v0.2.4 && git push --tags`.
