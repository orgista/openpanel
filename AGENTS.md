# OpenPanel agent instructions

## Master content configuration policy

OpenPanel must ship with an empty user-content catalog. Never hardcode requested
apps, package selections, websites, URLs, YouTube videos/channels/playlists,
library catalogs, media, titles, thumbnails, ordering, or deployment-specific
content in TypeScript, JavaScript, Java, Kotlin, Android resources, bundled
assets, build scripts, or production defaults.

When the user asks to add or change deployable content, record every requested
item in `POST_DEPLOYMENT_CONTENT.txt` instead. That file is a human operations
checklist only: application code, tests, builds, seeders, and deployment scripts
must never parse, import, copy, or automatically apply it. Content must be added
after deployment through OpenPanel's admin UI or the authorized device-management
workflow, and only to the explicitly requested devices or groups.

For each requested item, record its type, display name, URL or identifier,
intended target, ordering/metadata requirements, and deployment status. Do not
put credentials, API keys, tokens, private URLs, or other secrets in the file.
If OpenPanel has no post-deployment configuration path for a requested item,
record the gap and report it rather than hardcoding a workaround.

This policy does not prohibit product-owned branding, ordinary interface copy,
protocol/provider endpoints needed to implement a feature, security allowlists,
device/package detection policy, or clearly isolated test fixtures that cannot
enter a production build. These exceptions must not be used to smuggle a
deployable content catalog into the application.

## ArborXR upload policy

Codex may build and verify OpenPanel APKs locally without additional
authorization.

Codex may sign, upload, replace, promote, or deploy an OpenPanel production or
debug APK to the configured ArborXR tenant only when the user explicitly asks
for that upload or deployment. Codex must never initiate an external upload or
deployment merely because a new build is available.

For an explicitly requested deployment, Codex may reuse the configured
ArborXR token and signing material, update the relevant OpenPanel application
entry, assign the requested existing tablet groups or testing devices, and
configure OpenPanel as the launcher when requested. This is an exception to
the workspace rule prohibiting external APK uploads only for that
user-requested OpenPanel deployment.

Before uploading, Codex must verify tests, package identity, version,
signature, and intended deployment targets. Codex must not expose credentials,
delete devices or groups, modify unrelated applications, or deploy an
unverified build.
