# Third-party notices

ZFoldDuo resolves the following third-party components through Gradle. Their source code is not maintained in this repository.

## Direct dependencies

| Component | Version | License | Project |
| --- | ---: | --- | --- |
| Kadb and Kadb mDNS | 2.1.4 | Apache License 2.0 | <https://github.com/flyfishxu/Kadb> |
| Kotlin coroutines | 1.11.0 | Apache License 2.0 | <https://github.com/Kotlin/kotlinx.coroutines> |
| Android Hidden API Bypass | 6.1 | Apache License 2.0 | <https://github.com/LSPosed/AndroidHiddenApiBypass> |
| JUnit 4 (tests only) | 4.13.2 | Eclipse Public License 1.0 | <https://github.com/junit-team/junit4> |

## Runtime transitive dependencies

| Component | Version | License | Project |
| --- | ---: | --- | --- |
| Kotlin standard library | 2.4.0 | Apache License 2.0 | <https://github.com/JetBrains/kotlin> |
| Okio | 3.17.0 | Apache License 2.0 | <https://github.com/square/okio> |
| SPAKE2-Java | 1.1.1 | GNU General Public License v3.0 | <https://github.com/Flyfish233/spake2-java> |
| ed25519-elisabeth | 0.1.0 | MIT License | <https://github.com/cryptography-cafe/ed25519-elisabeth> |
| curve25519-elisabeth | 0.1.0 | MIT License | <https://github.com/cryptography-cafe/curve25519-elisabeth> |
| Bouncy Castle Java | 1.84 | Bouncy Castle Licence | <https://github.com/bcgit/bc-java> |
| AndroidX libraries | resolved by Gradle | Apache License 2.0 | <https://github.com/androidx/androidx> |
| JSpecify annotations | 1.0.0 | Apache License 2.0 | <https://github.com/jspecify/jspecify> |
| JetBrains annotations | 23.0.0 | Apache License 2.0 | <https://github.com/JetBrains/java-annotations> |

SPAKE2-Java is used by Kadb's pairing implementation and is licensed under GPLv3. The ZFoldDuo-authored source remains available under the repository's MIT License; redistribution of an APK must also satisfy all applicable third-party terms, including GPLv3 for this bundled component.

The authoritative license text and copyright notice for each component are provided by its published artifact and linked project. Gradle's resolved dependency graph is the source of truth for the exact set included in a particular build.
