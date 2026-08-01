# Privacy

Talaria is designed for people who run Hermes Agent themselves and do not want a mobile client that phones home.

## Principles

1. **Zero telemetry by default.** `BuildConfig.DEFAULT_TELEMETRY` is `false`. The in-app “Telemetry” switch is stored locally and currently does **not** transmit anywhere — there is no analytics endpoint in Talaria.
2. **No forced accounts.** You connect to *your* Hermes dashboard. Nous Portal login is only used if *your* dashboard is configured for OAuth and you choose that flow.
3. **Local-first.** Session caches, activity history, and drafts stay in on-device Room storage.
4. **Secrets in Keystore.** Connection tokens/passwords use EncryptedSharedPreferences (AES-256 via Android Keystore master key).
5. **Network scope.** HTTP/WS traffic goes to the base URL(s) you configure. User-started OIDC and MCP OAuth also open the authorization URL advertised by that server.
6. **Optional pinning.** Per-profile SHA-256 certificate pins harden MITM resistance on untrusted networks.
7. **Voice stays local when possible.** Dictation prefers on-device recognition. Cloud speech engines require an explicit opt-in.

## Data Talaria stores on device

| Data | Where | Notes |
|------|-------|-------|
| Connection URLs, auth mode, profile names | Encrypted prefs | Not backed up to cloud by default |
| Session tokens / passwords / bearer tokens | Encrypted prefs | Never logged at BASIC HttpLogging unless you enable debug logging |
| Cached sessions / messages | Room | Cleared when you clear app data |
| Notification / sync preferences | SharedPreferences | Non-secret |
| Chat drafts | Room | Isolated by connection and Hermes management profile |

## Data Talaria does **not** collect

- No crash reporter SDK by default
- No advertising IDs
- No usage analytics backend
- No automatic upload of logs or transcripts to Nous Research or third parties

## Notifications

Notification content (agent reply previews, pairing codes, etc.) is generated locally from Hermes responses. Android may mirror notifications to wearables / OEM clouds according to **your** device settings — disable that at the system level if required.

## Microphone

Microphone permission is used only for dictation you start while Talaria is in the foreground. Talaria does not keep a microphone foreground service running after you leave Chat.

## Backups

Android Auto Backup / cloud backup rules exclude encrypted prefs and databases. Device-to-device transfer is similarly restricted to avoid leaking secrets through OEM backup pipelines.

## Third-party code

Dependencies (AndroidX, OkHttp, Retrofit, Room, etc.) are standard open-source libraries. Review Gradle lockfiles / dependency updates as part of your supply-chain process.

## Contact

For privacy issues with this client, open a GitHub issue on the Talaria repository. For Hermes Agent server privacy, see Nous Research’s Hermes documentation.
