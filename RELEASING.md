# Publishing APK releases

The [release workflow](.github/workflows/release.yml) runs when a tag such as `v0.1.2` is pushed. It runs unit tests and release lint, builds a non-debuggable release APK, checks its version against the tag, signs it, verifies the signature/alignment, and publishes a GitHub release with the APK and a SHA-256 checksum file.

Only stable `vMAJOR.MINOR.PATCH` tags are supported. No personal GitHub token is needed: the workflow uses its built-in `GITHUB_TOKEN` with release-writing permission. The workflow must be included in the tagged commit.

## One-time signing setup

Android updates must keep the same signing key. Create a dedicated release key once, back it up securely with its passwords, and reuse it for every release. Do not commit the key, passwords, or its base64 representation.

For example, run this interactive command locally:

```sh
keytool -genkeypair -v -storetype JKS \
  -keystore mymidihub-release.jks -alias mymidihub \
  -keyalg RSA -keysize 3072 -validity 10000
```

The certificate's identity fields are public in distributed APKs; use the identity you want to publish. Then create an encoded copy for GitHub's secret storage:

```sh
base64 -w 0 mymidihub-release.jks > mymidihub-release.jks.b64
```

On macOS, use `base64 < mymidihub-release.jks | tr -d '\n' > mymidihub-release.jks.b64` instead. Base64 is only an encoding, not encryption; treat this file as a private signing key too.

In the repository's **Settings → Secrets and variables → Actions**, create these repository secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Contents of `mymidihub-release.jks.b64` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | `mymidihub` if using the example above |
| `ANDROID_KEY_PASSWORD` | Private-key password (may be the same as the keystore password) |

The workflow decodes the key into the runner's temporary directory only during signing and removes it afterward. Missing secrets cause the workflow to fail without publishing a release.

Release APKs signed with this new key cannot update an existing locally debug-signed installation. Switching from the original debug APK requires uninstalling it first, which clears saved routes and labels. Subsequent release APKs can update each other when signed with the same release key and an increased version code.

## Publish a version

1. In `app/build.gradle.kts`, update `versionName` (for example, `0.1.2`) and increase `versionCode` (for example, from `2` to `3`). Android uses the integer version code for updates; increment it for every release.
2. Commit and push the changes, including the workflow if this is its first release.
3. Tag that commit and push the tag:

```sh
git tag -a v0.1.2 -m 'Release 0.1.2'
git push origin v0.1.2
```

The tag's version must match `versionName` exactly. Once the workflow succeeds, the repository's Releases page contains:

- `MyMidiHub-v0.1.2.apk`
- `MyMidiHub-v0.1.2.apk.sha256`

The workflow uploads assets to a draft, then publishes it. A failed run can be rerun from the Actions page; an existing draft is reused. Published releases are not overwritten. For a subsequent change, increment the version and push a new tag.

Signing and GitHub publishing require the configured secrets and a hosted workflow run. Local Gradle builds alone do not validate those external steps.
