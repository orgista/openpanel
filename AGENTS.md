# OpenPanel agent instructions

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
