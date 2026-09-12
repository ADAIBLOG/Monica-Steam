# Rust integration compatibility boundary

The native core accelerates batch response parsing and the CM wire codec. Android keeps the established Kotlin code for maFile import, TOTP, confirmation/signature generation, login approval and authenticator enrollment/replacement. Those operations are sensitive, infrequent, and do not justify two production implementations. Removed JNI entry points prevent them from accidentally depending on native library availability.

A synthetic maFile regression exposed different Base64 trailing-bit validation: Java accepts nonzero unused bits, while the default Rust engine rejects them and may incorrectly select Base32. The retained reference Rust parser now uses Java-compatible trailing-bit handling. Android always imports through Kotlin. No production account data is used in the tests.

CI runs protocol tests both with the Kotlin fallback and with a real host JNI library. Required-native tests check library loading, Unicode strings, native CM encoding/decoding, game parsing, invalid payloads, and unchanged secret import while native code is available. Android smoke builds verify both ARM libraries are packaged.

## Liquid glass

Indicator translation, deformation, icon crossfade and sampled icon scale read animation state inside graphics-layer blocks instead of the parent composition. Drag-scale animation returns State without subscribing the parent to every frame. This avoids parent recomposition for those animation values. The original blur, refraction, dispersion and motion parameters are preserved; Android's GPU shader handles the optical effect. A JNI call per frame would not address Compose invalidation or GPU sampling costs.

Local ADB could not start its server during this change, so no device frame-time improvement is claimed. Motion and material regression tests can validate behavior, but a release build should additionally be profiled on the affected device by recording frame timing while repeatedly dragging and tapping across the dock. Sensor-driven highlights and destination-page work may still contribute to jank.
