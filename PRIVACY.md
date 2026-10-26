# Privacy Notice for Hello Reader

**Effective Date:** October 23, 2026  
**Application:** Hello Reader (`org.hello.reader`)  

Hello Reader is designed with privacy in mind. This document outlines how data is handled by the application, including network communications, on-device storage, permissions, and third-party services.

---

## 1. Data Collection & Processing
The developers of Hello Reader do not operate analytics servers, user tracking databases, or telemetry collectors for this application:
- The app does not include advertising networks, tracking pixels, or user profiling SDKs.
- The app does not collect your name, email address, physical location, or reading activity.

## 2. Network Communications
- **Storyteller Server:** When connected to a Storyteller server, network requests (such as catalog synchronization, book downloads, and reading position updates) are sent directly between your Android device and your designated server endpoint. No developer-operated proxy or relay is used.
- **Dictionary Lookups (Third-Party Service):** When you highlight a word and tap "Define", the app sends an HTTPS request containing that word to the Free Dictionary API (`api.dictionaryapi.dev`). The service receives the looked-up word and your device's IP address to return definitions. This lookup occurs only when you explicitly request a definition; no account tokens or device identifiers are included.

## 3. Storage, Encryption, and Backups
- **Downloaded Media & Library Data:** Downloaded e-books, audio files, covers, reading checkpoints, and bookmarks are stored in private application storage on your device.
- **Authentication Tokens:** When you sign in to a Storyteller server, the server issues an authentication token. This token (not your password) is stored in encrypted storage backed by the Android Keystore (`EncryptedSharedPreferences`). Passwords are not retained once authentication is complete.
- **Android Cloud Backup:** The app allows standard Android system backups (`allowBackup=true`). If Android Backup is active on your device, local databases (including book metadata, reading positions, bookmarks, and preferences) may be backed up to your personal Google account. The encrypted credential vault (`hello_secure_vault.xml`) is explicitly excluded from backups and device-to-device transfers.

## 4. Device Permissions
- **Camera (`android.permission.CAMERA`):** Used solely for scanning QR codes during server connection setup. Optical barcode scanning runs on-device using the Google ML Kit Barcode Scanning SDK (`com.google.mlkit:barcode-scanning`). Camera frames are processed in memory and are not stored or transmitted. As documented by Google, the ML Kit library may transmit diagnostic and usage metrics to Google.
- **No Storage Permission Requested:** The app does not request Android storage permissions (`READ_EXTERNAL_STORAGE` or `MANAGE_EXTERNAL_STORAGE`). Importing books utilizes the Android system file picker (Storage Access Framework), which runs as a system process and grants the app temporary read access only to the file you select.
- **Media Playback (`android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`):** Used to maintain audio narration playback when the screen is turned off or while switching apps.
- **Notifications (`android.permission.POST_NOTIFICATIONS`):** Used to display playback controls in the system notification shade and to indicate book download progress.
- **Network (`android.permission.INTERNET`, `android.permission.ACCESS_NETWORK_STATE`):** Used to communicate with your configured Storyteller server and the dictionary lookup endpoint.

## 5. Data Retention & Deletion
All local data remains under your physical control on your device:
- Downloaded books, audiobooks, and bookmarks can be removed within the app.
- Disconnecting or removing a server profile deletes the associated authentication token from the device's secure vault.
- Selecting "Clear Storage" or "Clear Data" in Android System Settings, or uninstalling the app, permanently removes all local databases, cached media, bookmarks, and credentials.

---

## Contact
For questions or requests regarding this privacy notice:  
**Email:** [christianhokeprogramming@gmail.com](mailto:christianhokeprogramming@gmail.com)  
**Community (Discord):** [https://discord.gg/MVCdrfwFpE](https://discord.gg/MVCdrfwFpE)  
**Source code & issues (GitHub):** [https://github.com/cassu123/hello](https://github.com/cassu123/hello)
