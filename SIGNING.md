# Quiet OS: one-time stable APK signing setup

GitHub Actions now requires a permanent keystore for pushes to `main`. Pull request builds still use disposable debug signing and **are not in-place upgrades**.

## Create and keep the keystore

On your own Mac, install a JDK and run:

```sh
keytool -genkeypair -v -keystore quiet-upload.jks -alias quiet -keyalg RSA -keysize 3072 -validity 10000
```

Set strong passwords when prompted. Save `quiet-upload.jks` and the passwords somewhere private with a backup. **Do not commit or share the keystore or passwords.** Never regenerate it for future updates.

Keep signing material in GitHub Actions encrypted repository secrets, never in source files, workflow literals, build logs, or published artifacts. The workflow reads the signing keystore and passwords from repository secrets configured under **Settings > Secrets and variables > Actions**. Maintain those values privately; no secret values belong in this document.

## Install and update

1. Merge the signing PR only after adding the secrets (otherwise main CI will fail intentionally).
2. Download the resulting main-branch `Quiet-debug-apk` artifact.
3. Because the signing certificate differs from the old random GitHub Actions debug key, one uninstall/reinstall will usually be necessary. **Uninstalling may delete local launcher settings and training data.** Save anything important beforehand.
4. Install subsequent **main-branch** APKs over this installation without uninstalling. APKs built from PR checks use a different debug signing key.
5. Keep using the same keystore, alias and passwords for every release. Losing them can prevent installing future updates over existing installations.

## Versioning

GitHub Actions uses `1000 + GITHUB_RUN_NUMBER` as `versionCode`, which increases on newer workflow runs within the same workflow. Local builds use the fallback project version code 28. Do not install local or PR debug APKs over the stable-signed builds.

If an upgrade is rejected, compare signing certificates, application ID, and `versionCode`; do not delete application data as a first troubleshooting step.
