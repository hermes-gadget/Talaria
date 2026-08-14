# EncryptedSharedPreferences → Tink migration plan (M11)

**Status: PLAN ONLY.** `androidx.security:security-crypto` (the Tink-wrapped
EncryptedSharedPreferences used by `SecureConnectionStore` and
`CarHostTrustStore`) is deprecated by Google. It is not currently broken; this
is a planned debt migration, deliberately NOT part of the 2026-08-14 audit
fix waves because a wrong secret-store rewrite can silently lose saved
connections.

## Why

- Tink `EncryptedSharedPreferences` has keystore-invalidation and backup
  edge cases (the store's `RecoverableCorruption` / `PermanentKeystoreLoss`
  states exist precisely because of them).
- `security-crypto:1.1.0` is deprecated; upstream fixes are unlikely.
- The interface seam already exists: `SecureConnectionStorage`
  (`core/data/prefs/SecureConnectionStore.kt`) — the storage backend is
  swappable without touching callers.

## Target design

1. Keep the sealed store state machine (`Available` /
   `RecoverableCorruption` / `PermanentKeystoreLoss`) and the journalled
   reset behavior exactly as-is — callers depend on the recovery UX.
2. Replace the backend with a single encrypted file:
   - Tink `Aead` keyed from Android Keystore (`MasterKey` stays; only the
     preference layer changes).
   - One file per connection (`secrets/<id>.aead`) or one keyed envelope
     file; each write = encrypt-then-atomic-rename (`tmp` + `rename`) so a
     crash mid-write cannot corrupt the secret store.
   - Envelope format: version byte + nonce + ciphertext + Tink key id, so a
     future key rotation is a re-encrypt, not a data loss.
3. `CarHostTrustStore` (M17, fail-closed) migrates to the same envelope or
   stays on a separate `Aead`-encrypted file; keep its Available/Corrupt
   state machine.
4. One-time migration path: read the legacy EncryptedSharedPreferences and
   write the new envelope on first launch; delete the legacy prefs file only
   after the envelope write is fsync-verified.

## Sequencing (when approved)

1. Add Tink dependency (`com.google.crypto.tink:tink-android`).
2. Implement `TinkAeadSecretStorage : SecureConnectionStorage` behind a
   version-selected factory (legacy read path retained for one release).
3. Migration-test on a device with a real saved connection (Ben's phone):
   upgrade → existing connection still connects → THEN enable the purge of
   the legacy file.
4. Keep `CarHostTrustStore` on the same primitive; verify car enrollment
   survives upgrade.
5. Rollout: one release with dual-read, next release removes the legacy
   path. Do NOT bundle this with any other change.

## Explicitly out of scope

- Storing secrets in plain SharedPreferences or DataStore (encrypted file
  only — DataStore adds no security without the same Tink layer).
- Adding a second secret store alongside the existing one.
- Any change that removes the `RecoverableCorruption` recovery UX.
