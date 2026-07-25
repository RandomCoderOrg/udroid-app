# App updates

uDroid checks the public `RandomCoderOrg/udroid-app` GitHub releases without an
account or access token. The repository currently publishes prereleases, so the
updater uses the release-list endpoint rather than GitHub's “latest release”
endpoint.

## Lifecycle

1. Android's native JobScheduler registers a persisted, network-connected,
   battery-aware check every 12 hours.
2. App startup requests a one-time check only when the previous result is more
   than six hours old.
3. Manual checks replace only the previous manual job; the periodic job remains
   unique.
4. A newer semantic version appears in the Workspace and may post a
   notification.
5. Download begins only after the user presses **Download update**.
6. Partial APK data is retained for resume.
7. The completed APK must match both the digest in GitHub's release API and the
   exact filename entry in `SHA256SUMS`.
8. Before installation, uDroid verifies the APK package name, Android version
   code, and signing certificate.
9. Android's PackageInstaller displays the final system confirmation.

The listener never installs silently. On Android versions that gate unknown-app
installation per source, uDroid opens the relevant system settings page and
asks the user to return before submitting the verified package.

## Release selection

Draft releases are ignored. A usable release must provide:

- a valid semantic tag such as `v0.0.3`;
- an APK asset under the exact
  `RandomCoderOrg/udroid-app/releases/download/<tag>/` path;
- a `SHA256SUMS` asset under the same tag;
- a positive APK size;
- a version newer than the installed `BuildConfig.VERSION_NAME`.

Redirects may remain on HTTPS but cannot downgrade to cleartext HTTP.

## Stable signing

Android updates must use the same signing certificate as the installed app.
Tagged CI builds therefore fail closed unless the four update-signing secrets
listed in the README are configured. Normal branch and pull-request builds can
continue using Android's local debug key because they are not published as
updates.

The PKCS12 keystore is decoded only inside the temporary GitHub Actions runner
and is not committed to this repository.

The old `v0.0.1` and `v0.0.2` APKs used ephemeral debug signing. They cannot be
updated in place to the first stable-signed build. This is a one-time reinstall
boundary for early testers.

For every tagged release:

1. Increase both `versionName` and Android `versionCode`.
2. Keep the four signing secrets configured in GitHub Actions.
3. Push the matching `v<versionName>` tag.
4. Keep both the APK and generated `SHA256SUMS` in the GitHub release.

## Runtime cost

The first implementation used WorkManager for one small release request.
Device-generated profiles showed that this added roughly 2,440 startup rules
(15,457 to 17,897). The final listener uses the platform JobScheduler already
present on Android and does not initialize another persistence framework.

On the same Pixel 6a profile journey, the final startup profile contains 15,513
rules: 56 more than the pre-updater baseline, or about 0.36%. The periodic job
is registered once, persisted by Android, and constrained to a connected
network and a non-low battery.

References:

- [GitHub release API](https://docs.github.com/en/rest/releases/releases)
- [JobScheduler](https://developer.android.com/reference/android/app/job/JobScheduler)
- [Android PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller)
