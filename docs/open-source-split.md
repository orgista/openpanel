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
| `scripts/`, `docs/`, build config (`package.json`, `vite.config.ts`, `capacitor.config.ts`, `tsconfig.json`, `index.html`, `postcss.config.mjs`) | **public** `orgista/openpanel` | The UI builds against these |
| `.github/workflows/` | **public** `orgista/openpanel` | CI |
| `src/` (entire React app + TS bridge bindings, styles, assets) | **private** `orgista/openpanel-ui` | The gated UI — **git-ignored here**, not in the public repo |

The UI lives in the private repo **`orgista/openpanel-ui`** (its root maps 1:1
onto `src/`) and is excluded here via `.gitignore` (`/src/`). To build the full
app, clone it to `src/` (that keeps `vite`, `index.html` → `/src/main.tsx`, and
`tsconfig` working unchanged). It can also be wired as a git submodule at
`src/` — see below.

## Wiring the private UI as a submodule (optional)

The private UI repo already exists and is pushed (in the local working tree,
`src/` is a nested git repo tracking it — push UI changes from there). To
formalize it as a submodule of the public repo:

```sh
# 1. Wire it in as a submodule at src/
#    (first remove the /src/ ignore line from .gitignore)
git submodule add git@github.com:orgista/openpanel-ui.git src
git commit -am "build: add private UI submodule at src/"
git push

# 2. Let CI read the private submodule
gh secret set UI_SUBMODULE_TOKEN --repo orgista/openpanel   # PAT w/ read on openpanel-ui
```

Clone for development (with UI access): `git clone --recurse-submodules <url>`.
A contributor without UI access gets a buildable open engine but no bundled UI.

## What is NOT published (git-ignored)

`/src/` (private UI), keystores (`*.keystore`), `.env*`, `Samples/` (commercial
Fully Kiosk APKs — do not redistribute), `*.apk` / `*.zip`, `dist/`, `build/`,
`node_modules/`, `.toolchains/`, `local.properties`, `guidelines/` (design
template), and `docs/_archive/` (retired internal/research notes).

Verify before any push: `git ls-files | grep -iE 'keystore|\.env|password|secret'`
must be empty.

## Release signing

The checked-in `.github/workflows/android.yml` is an engine compile check for the
public repo. A signed release requires a UI-aware checkout plus external signing
inputs. The release Gradle tasks fail unless these values are supplied from CI
or a secret manager:

```sh
PASS="$(security find-generic-password -a openpanel-upload -s "OpenPanel upload keystore password" -w)"
REPO="orgista/openpanel"
base64 -i ~/openpanel-upload.keystore | gh secret set RELEASE_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$PASS"       | gh secret set OPENPANEL_KEYSTORE_PASS --repo "$REPO"
printf '%s' "$PASS"       | gh secret set OPENPANEL_KEY_PASS      --repo "$REPO"
printf 'openpanel-upload' | gh secret set OPENPANEL_KEY_ALIAS     --repo "$REPO"
```

In CI, `RELEASE_KEYSTORE_BASE64` should be decoded into a temporary file and
`OPENPANEL_KEYSTORE_FILE` should be exported to that temporary path at runtime.
Do not commit the keystore or write signing passwords into Gradle files.
