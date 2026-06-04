# Mirozo Android

Android client for Mirozo, the AI-assisted scheduler for students.

This repository is managed separately from the web application. The app talks to
the existing Mirozo mobile BFF under `/api/mobile/v1/*`.

## Requirements

- Android Studio or Android SDK command-line tools
- JDK 17 or newer
- Android SDK Platform 36.1
- Android SDK Build-Tools 36.0.0

## Configuration

Create a local `.env` file when you need to override the default API endpoint:

```properties
MIROZO_API_BASE_URL=https://mirozo.kr/
```

The `.env.example` file provides the default value used by local debug builds.
Keep `.env` and `local.properties` local; they are intentionally ignored.

## Build And Test

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The debug app uses `https://mirozo.kr/` by default and stores session cookies
through the app CookieJar. Release builds must not log HTTP request or response
bodies.

## Notes

- Application ID: `kr.mirozo.app`
- Display name: `Mirozo`
- Google Calendar token entry and timetable mock upload are prototype features
  and will be replaced in later work.
