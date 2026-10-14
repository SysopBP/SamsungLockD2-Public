# Third-party licensing and release review

D2 original code retains the existing rights notice in LICENSE. Shipped libraries retain their own terms.

The root and APK asset copies of THIRD_PARTY_NOTICES.txt are synchronized. Shizuku API/provider use MIT, not the Shizuku manager's Apache license. libxposed is compileOnly and is not a shipped framework implementation.

Before a release, resolve its actual runtime dependency graph, inspect embedded notices in every redistributed artifact, and update both notice copies. Keep runtime-dependencies.txt with the release. The component inventory here is a baseline, not certification of every transitive dependency.

For new ports, record upstream URL, exact revision, copied files, changes and license. OneUIX uses AGPLv3; copying copyrightable implementation into D2 requires review of resulting license/source obligations. A credit line alone does not preserve D2's original licensing for such a derivative. Independently implemented ideas must have documented provenance.

Keep Samsung/KernelSU/LSPosed compatibility descriptions factual and preserve upstream legal attribution. Do not present D2 as endorsed by those projects.
