# Monica Steam Rust core

This directory contains the performance-sensitive Steam protocol core and its Android JNI bridge. The migration is intentionally coarse-grained: parsing/encoding work that benefits from native execution crosses JNI once per response or operation, while Android UI, lifecycle, localization, HTTP orchestration and platform integration stay in Kotlin/Compose.

## Toolchains

- `monica-steam-core`: Rust 1.75+ (`rust-version = "1.75"`)
- `monica-steam-android`: Rust 1.77+ (`rust-version = "1.77"`), matching the current JNI dependency chain
- Android CI NDK: `27.2.12479018`
- Android minSdk: 26
- Packaged ABIs: `arm64-v8a`, `armeabi-v7a`

The CI deliberately tests the core at Rust 1.75 and the complete workspace at Rust 1.77 instead of testing only the latest stable compiler.

## Current native core coverage

The Rust core now owns the coarse-grained protocol or parsing work for:

- bounded protobuf reading/writing with borrowed field views;
- Steam CM envelope encoding/decoding, multi-message payloads and web-logon bodies;
- direct-message sessions and history pages, including reactions;
- group-chat history and group/room summaries;
- owned games, achievement progress, StoreBrowse metadata and achievement detail merging;
- family shared-library apps;
- friend nickname lists;
- authorized-device enumeration;
- trade-offer pages, including descriptions, nested assets and sorting;
- wishlist pages, including nested store assets and purchase options.

Production Android call sites use the batch read parsers and CM codec Rust-first and retain their Kotlin implementations as compatibility fallbacks. Secret import, Steam Guard codes, confirmation hashes, login approval signing, login orchestration and authenticator changes use the established Kotlin implementations. The Android bridge does not expose secret import, Guard/signing or authenticator mutation entry points. Their Rust core implementations remain covered by host tests for reference, but are not used by Android.

## Android bridge layout

`monica-steam-android/src/lib.rs` is intentionally only a module registry. JNI entry points and binary serializers are split by domain:

```text
auth_bridge.rs         auth sessions and authorized devices
chat_bridge.rs         direct-message sessions and history
cm_bridge.rs           CM envelopes and web logon
family_bridge.rs       family shared library
friend_bridge.rs       friend nickname lists
group_chat_bridge.rs   group chat/history summaries
library_bridge.rs      games, achievements and StoreBrowse metadata
trade_bridge.rs        trade offers
wishlist_bridge.rs     wishlist pages
```

Keep future coarse-grained bridges in a focused domain module instead of growing the crate root again.

## Host tests

```bash
rustup toolchain install 1.75.0 1.77.0

cargo +1.75.0 test \
  --manifest-path rust/monica-steam-core/Cargo.toml \
  --all-targets

cargo +1.77.0 test \
  --manifest-path rust/Cargo.toml \
  --workspace \
  --all-targets
```

## Build Android JNI libraries

Install the Android Rust targets and `cargo-ndk` first:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk --locked
```

Set `ANDROID_NDK_HOME` (or `ANDROID_NDK_ROOT` / `ANDROID_NDK`) to an installed NDK, then run:

```bash
scripts/build-rust-android.sh
```

The script writes:

```text
app/src/main/jniLibs/arm64-v8a/libmonica_steam_android.so
app/src/main/jniLibs/armeabi-v7a/libmonica_steam_android.so
```

Then build Android normally:

```bash
./gradlew assembleDebug
```

`Steam Network CI` also verifies that both `.so` files are actually present inside the generated split APKs. Successful CI runs upload the debug APKs together with `apk-sha256.txt` and `apk-contents.txt` for device testing.

On Windows with an installed NDK and cargo-ndk, run from `rust/`:

```powershell
$env:ANDROID_NDK_HOME = 'D:/AndroidSDK/ndk/28.2.13676358' # Use your installed NDK path.
cargo ndk --platform 26 -t arm64-v8a -t armeabi-v7a -o ../app/src/main/jniLibs build --release --locked -p monica-steam-android
```

Normal Gradle builds do not invoke Cargo. Run the native build first when packaging Rust support; otherwise the app uses Kotlin fallbacks. CI builds and tests a real host JNI library in a separate test job, in addition to the fallback job:

```sh
cargo build --manifest-path rust/Cargo.toml --locked -p monica-steam-android
./gradlew testDebugUnitTest -PsteamNativeTestLibraryDir=../rust/target/debug --tests 'takagi.ru.monica.steam.*'
```

The Gradle property is relative to the `app/` directory and makes the integration tests fail if the native library cannot load. Test without the property to exercise the Kotlin fallbacks. Cargo.lock is committed to keep both CI and native builds reproducible.

## Native bridge policy

`RustSteamCoreNative` loads `libmonica_steam_android.so` opportunistically. Every Android call site keeps a Kotlin implementation as a fallback. If the native library is unavailable, JNI throws, a parser rejects malformed input, or a versioned bridge payload fails validation, the existing Kotlin path remains available.

Do not move tiny JVM-only operations across JNI just for language consistency. A native call should cover enough work to amortize the JNI boundary. In particular, do not serialize an already-decoded Kotlin `JsonObject` back into text merely to parse it again in Rust.

## Versioned binary bridges

The JNI layer returns compact binary payloads instead of constructing large Java/Kotlin object graphs one JNI call at a time.

| Magic | Purpose |
| --- | --- |
| `MSC1` | decoded Steam CM envelopes |
| `MSS1` | direct-message chat sessions |
| `MSM1` | direct-message chat history pages |
| `MSL1` | owned games |
| `MSP1` | achievement progress |
| `MST1` | StoreBrowse metadata |
| `MSA1` | merged achievement definitions + user status |
| `MSF1` | family shared-library apps |
| `MSN1` | friend nickname lists |
| `MAC1` | pending auth-session client IDs |
| `MAI1` | auth-session metadata |
| `MAR1` | mobile auth-session confirmation result |
| `MSD1` | authorized devices |
| `MSG1` | group-chat history pages |
| `MGS1` | group/room summaries |
| `MTO1` | trade offers and merged item descriptions |
| `MSW1` | wishlist pages |

Kotlin decoders validate bridge magic, counts, lengths, enum/boolean values and trailing bytes before producing domain objects.

## Semantic compatibility

Rust parsers are expected to preserve the behavior of the Kotlin fallback, including non-obvious cases such as:

- first-vs-last duplicate protobuf fields;
- `Long.toInt()` truncation where the Kotlin parser uses it;
- fixed32/fixed64 vs varint behavior;
- Kotlin's broader `.bytes` semantics for fixed fields in the few call sites that rely on it;
- insertion-order deduplication;
- stable sorting;
- deliberately lax packed-varint decoding in group member lists;
- malformed nested-item skip/fail ordering.

When migrating another parser, add Rust semantic tests and a Kotlin bridge-layout/validation test before routing production traffic through it.

## Deliberate boundary and next large decomposition

Large Kotlin source files are not automatically Rust candidates. Community, notification, market and confirmation flows often receive an already-decoded JSON tree; moving those mappings through JNI would add serialization and object traffic instead of removing it. Small single-field protobuf reads also stay Kotlin when JNI would cost more than the parse.

`SteamLoginImportService.kt` remains the main mixed legacy outlier. It combines HTTP orchestration, challenge handling, UI-facing error semantics and several authentication protobuf exchanges in one very large file. Its remaining native-worthy protocol pieces should be extracted behind a focused Kotlin protocol boundary first, then moved to Rust in a separate staged change. Replacing the entire orchestrator at once would couple protocol migration to login-flow behavior and make fallback validation unnecessarily risky.
