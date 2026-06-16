# Decision: JNA pinned to 5.13.0

**Date:** 2026-06-03 (crypto feature)
**Status:** accepted

## Context

The project uses [lazysodium](https://github.com/terl/lazysodium) (libsodium Java bindings) for AEAD encryption and Argon2id key derivation. lazysodium wraps libsodium's native `.so` via JNA. The project targets JDK 17 and Android.

## Decision

Pin JNA to **5.13.0** (2022-10).

## Rationale

The pin flows from a constraint two levels up:

1. **lazysodium 5.1.0** is the newest version published for *both* `lazysodium-java` (JVM tests) and `lazysodium-android` (device), compiled for **Java 8 (class 52)**. lazysodium 5.2.0+ requires Java 21 (class 65) and throws `UnsupportedClassVersionError` under JDK 17.
2. JNA **5.13.0** is the version that is also Java-8 compatible and ships both artifact variants:
   - `@aar` — Android native with `libjnidispatch.so` per ABI
   - JVM jar — used in `./gradlew test`

Newer JNA versions (5.14+) dropped either the `@aar` packaging or Java 8 compatibility, which would break the lazysodium dependency chain.

## Unfreeze condition

When the project upgrades to **JDK 21** and **lazysodium 5.2.0+**, re-evaluate JNA against the latest stable line. At that point both the Java class version constraint and the dual-artifact requirement can be rechecked.

## Alternatives considered

- **JNA 5.15.x (latest):** incompatible with lazysodium 5.1.0 (class version mismatch)
- **lazysodium 5.2.0 + JDK 21:** requires a toolchain migration; deferred
- **Drop lazysodium, use a pure-Kotlin libsodium binding:** no mature alternative with Argon2id + XChaCha20-Poly1305 support at decision time

## References

- `gradle/libs.versions.toml:9-17` — dependency rationale
- `docs/stack-notes.md` — Crypto component notes
