# D2 source and distribution

Current covered D2 revisions use AGPL-3.0-only. Copyright remains with the D2
Project and other respective contributors. This transition does not replace
third-party licenses or claim ownership of their work.

## Build the source

1. Obtain the source archive accompanying the exact APK or check out its commit
   from https://github.com/SysopBP/SamsungLockD2-Public.
2. Install the JDK and Android SDK versions specified by `.github/workflows/android.yml`.
3. Install Gradle 9.3.1 and run `gradle wrapper --gradle-version 9.3.1` if the
   wrapper is not already present.
4. The SESL forks are available from https://github.com/tribalfs/sesl-androidx
   and https://github.com/tribalfs/sesl-material-components-android. GitHub's
   package server can require your own package-read credentials. Set
   `SESL_GITHUB_USER` and `SESL_GITHUB_TOKEN` in your environment to resolve the
   declared artifacts. Never put tokens in a source archive.
5. Run `./gradlew testDebugUnitTest assembleDebug lintDebug`.
6. A local debug build can use your own signing key. The original private
   release signing key is not included in Corresponding Source.

Build scripts, manifest, resources, Kotlin source, module scripts and license
notices are included. Dependency declarations and the CI runtime-dependency
report identify the external libraries. Preserve their own licenses and notices.
A recipient need not possess the project's private signing key to modify and
build the software.

## Before distributing each covered APK

- Publish a source archive matching the APK's build commit alongside the APK.
  The current branch head may contain different code and is not a substitute.
- Include LICENSE, the in-app AGPL license, copyright and third-party notices.
- Preserve original notices in imported files. For modified upstream files,
  record upstream origin/revision, modification date and changes.
- Check both direct and transitive dependency licenses and the resulting APK's
  notices before release. The existing dependency notices are retained; this
  transition alone is not a completed audit of every dependency artifact.
- If a modified covered program supports remote network interaction, provide
  its users a prominent no-charge offer of its Corresponding Source as required
  by AGPL section 13. An ordinary client request for weather data alone is not
  represented here as a remotely operated D2 server.
- Do not apply additional terms that remove rights granted by AGPL.

## Source archive generation

Run `python3 scripts/package_corresponding_source.py --output dist` from the
repository root. The script archives the tracked build revision, records its
commit and produces a checksum. It excludes runtime credentials and private
signing material because they are not tracked source.

CI publishes the archive beside its APK. Artifact retention is temporary; when
publishing a release, publish both assets together and retain the matching source.
Historical release workflows may reference pre-transition APKs. Do not describe
those older APKs as newly AGPL-licensed without a separate verified release.

## Historical OneUIX provenance

D2 previously integrated selected [SoClear/OneUIX](https://github.com/SoClear/OneUIX) sources (reference revision `591d09c9cc69faf3c8faf258e39869b2df7981f4`). Those port modules and stubs were intentionally removed from the current branch. The independent D2 power menu remains. Retain appropriate attribution, original license information and historical source availability for builds that included the port. The current read-only D2 StrongAuth observer carries its own source notice.

## DexKit historical distribution

DexKit 2.2.0 was a dependency of the removed OneUIX port. It is not required by the current D2 Gradle dependency graph. Earlier builds containing DexKit must retain their corresponding source and notices; current packaging should be audited to avoid claiming or bundling a library that is no longer shipped. Do not discard prior release source archives.

Use your own signing key for the modified APK. The original private signing key is not distributed. Android will require uninstalling a differently signed installed APK before installing your modified APK; export any wanted user settings first. D2 is installed as a normal APK and has no firmware signature requirement, locked hardware installation key, or installer intended to prevent replacement of its library. Disable its kiosk/root integration before replacing it.
