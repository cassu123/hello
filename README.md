# Hello Reader

Hello Reader is an Android reader and player for [Storyteller](https://storyteller-platform.gitlab.io/storyteller/) readalouds, e-books, and audiobooks, as well as local files. It plays synchronized readalouds (EPUB 3 with SMIL Media Overlays), standard EPUB e-books, and standalone audiobooks.

## Features

- **Storyteller v2 Sync & Multi-Server Support**: Connect to one or more self-hosted Storyteller v2 servers via username/password or QR code setup. Switch between server profiles with isolated local storage, browse series and collections, and sync reading and listening positions bi-directionally (including periodic 30-second background position sync while reading or listening).
- **Reading Statuses**: View and update book statuses (**To read**, **Reading**, **Read**, or custom server statuses) synced with Storyteller, with automatic promotion from *To read* to *Reading* when you advance in a book.
- **Synchronized Readalouds**: Follow sentence-level SMIL highlights synchronized with audio narration, double-tap any sentence to jump narration to that point (tap-to-speak), or page freely through the book while narration continues and jump back with one tap.
- **Integrated Audio Players**:
  - **Docked Reader Mini-Player**: In-book card displaying cover art, chapter title, book progress, play/pause, and expand controls, collapsible into a compact floating pill.
  - **Persistent Mini-Player**: Bottom bar across Home, Library, Series, Collections, Book Detail, and Notebook screens when audio is loaded in the background.
  - **Full-Screen Audio Player**: Modal sheet with cover artwork, chapter scrubber, transport controls (skip 15s, previous/next chapter, play/pause), playback speed (`0.5x`–`3.5x` in `0.05x` steps), sleep timer (15, 30, 60 minutes, or end of chapter with fade-out), smart rewind, and chapter list.
- **Flexible Page Layouts & Page Curl**: Read in paginated mode or continuous vertical scroll. Paginated mode supports interactive page-curl (`Flip`), slide, cover, or instant page turns, two-page book spreads on tablets and unfolded foldables, and half-opened tabletop posture layouts.
- **E-Ink Mode**: Automatic detection (with manual override) for Onyx Boox, Hisense, Kobo, Tolino, PocketBook, Supernote, Boyue/Meebook, Bigme, and InkPalm e-ink devices. Disables animations and translucency, uses flat high-contrast borders, supports volume-button page turns, and performs configurable periodic full-screen refreshes to clear ghosting.
- **Offline Downloads & Local Sideloading**: Download Readaloud, E-book, and Audiobook formats independently for offline use with SHA-256 checksum verification, or import local `.epub`, `.m4b`, `.mp3`, `.m4a`, and `.aac` files from device storage without a server.
- **Annotations, Notebook, Search & Dictionary**: Create color-coded highlights, text notes, and bookmarks; review and export them to Markdown from the in-book sheet or the global Notebook tab; search full book text; and look up word definitions via the Free Dictionary API.
- **Typography & Custom Themes**: Read with bundled fonts (Literata, Atkinson Hyperlegible, Inter, OpenDyslexic) or sideload custom `.ttf`/`.otf` font files. Customize font size, line height, paragraph spacing, letter spacing, margins, text alignment, and built-in or custom day/night color themes.
- **Android Auto & Home-Screen Widget**: Browse and play from Continue Listening, Audiobooks, and Readalouds in Android Auto, and resume your current book from the Jetpack Glance home-screen widget.

## Requirements

- **Android**: Android 8.0 (API 26 / `minSdk = 26`) or higher (`compileSdk = 36`, `targetSdk = 36`)
- **Server (Optional)**: A self-hosted Storyteller v2 server, or local EPUB / audiobook files on your device
- **Build Environment**: JDK 17 and Android SDK (Platform 36, Build Tools 35.0.0)

## Building and Testing

Clone the repository and run Gradle tasks from its root with JDK 17:

```bash
git clone https://github.com/cassu123/hello.git
cd hello

# Build the debug APK (output at app/build/outputs/apk/debug/hello-<version>-debug.apk)
./gradlew assembleDebug

# Run JVM unit tests
./gradlew testDebugUnitTest

# Run Android Lint
./gradlew lintDebug
```

## Project Structure

```text
app/src/main/java/org/hello/reader/
├── app/                # Application entrypoint, DI container, MainActivity & navigation host
├── core/
│   ├── data/           # Book, annotation, preferences & server connection repositories + local importer
│   ├── database/       # Room SQLite database (v7), DAOs, entities & type converters
│   ├── download/       # WorkManager background book/audio download worker
│   ├── engine/
│   │   ├── audio/      # Media3 NarrationPlayerService, PlaybackSession, sleep timer & smart rewind
│   │   ├── reader/     # ReaderStateMachine & mode transitions
│   │   └── smil/       # EPUB 3 Media Overlay extractor, SMIL 3 parser & sync engine
│   ├── model/          # Domain models (Book, SyncCheckpoint, ReaderSettings, ServerProfile, etc.)
│   ├── network/        # Retrofit StorytellerApi, dynamic host/auth interceptor & CatalogParser
│   ├── security/       # Keystore-backed EncryptedSharedPreferences profile vault
│   └── util/           # E-ink DeviceProfile detection, hardware volume-key dispatcher & comparators
├── feature/
│   ├── library/        # Home shelves, Library browser, Series/Collection details & BookDetailScreen
│   ├── notebook/       # Cross-book highlights, notes & bookmarks browser
│   ├── onboarding/     # Server URL setup, login, QR code scanner & server switcher
│   ├── player/         # Persistent MiniPlayer bar & FullAudioPlayerSheet
│   ├── reader/         # Compose EPUB reader, pagination, PageCurlFlipper, foldable layouts & sheets
│   ├── settings/       # Reader typography, custom theme editor, reading profiles & legal/about screens
│   └── widget/         # Jetpack Glance Continue Reading home-screen widget
└── ui/
    ├── components/     # Shared Compose UI components, cover images & format badges
    └── theme/          # Material 3 color schemes, typography & spacing tokens
```

## License

Copyright (C) 2026 River Song

Hello is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; see [`LICENSE`](LICENSE) for the full text.

Bundled fonts (Literata, Atkinson Hyperlegible, Inter, Baloo 2 and OpenDyslexic) are under the SIL Open Font License 1.1; their licenses are in [`app/src/main/assets/licenses/`](app/src/main/assets/licenses/) and viewable in-app under **Settings → About → Open Source Licenses**.

## Community & Privacy

- **Discord**: [https://discord.gg/MVCdrfwFpE](https://discord.gg/MVCdrfwFpE)
- **Source code & issues**: [https://github.com/cassu123/hello](https://github.com/cassu123/hello)
- **Privacy Policy**: See [PRIVACY.md](PRIVACY.md) for full details on data handling and permissions.
