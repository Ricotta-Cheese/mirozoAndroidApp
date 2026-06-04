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

Create a local `.env` file when you need to override the default API endpoint
or enable Google sign-in on a device:

```properties
MIROZO_API_BASE_URL=https://mirozo.kr/
GOOGLE_SERVER_CLIENT_ID=your-google-web-client-id.apps.googleusercontent.com
```

The `.env.example` file provides the default value used by local debug builds.
Keep `.env` and `local.properties` local; they are intentionally ignored. When
`GOOGLE_SERVER_CLIENT_ID` is left as the example placeholder, the app still
builds but Google login/linking shows a setup error.

## Build And Test

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The debug app uses `https://mirozo.kr/` by default and stores session cookies
through the app CookieJar. Release builds must not log HTTP request or response
bodies.

CI runs `:app:compileDebugKotlin` and `:app:testDebugUnitTest` on pushes to
`main` and pull requests.

## Notes

- Application ID: `kr.mirozo.app`
- Display name: `Mirozo`
- Google login/linking uses Android Credential Manager with server-issued nonce
  and Google ID token exchange.
- Timetable onboarding uploads a real image selected from the Android file
  picker.
