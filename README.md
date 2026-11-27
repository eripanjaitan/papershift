# PaperShift

A lightweight Android wallpaper rotator: every 12 hours it sets the home and lock screen
wallpaper from a local folder or Bing's daily image, without storing any images.

The full spec is in [docs/papershift_prd_v1.3.md](docs/papershift_prd_v1.3.md).

## Requirements

- Android Studio (its bundled JDK is used for Gradle)
- Android SDK Platform 37, Build-Tools 36+
- `ANDROID_HOME` pointing at the SDK, or a `local.properties` with `sdk.dir`

## Build

```powershell
.\gradlew.bat assembleDebug
```

Install on a running emulator or connected phone:

```powershell
.\gradlew.bat installDebug
```

## Project layout

| Path | Contents |
| :--- | :--- |
| `app/src/main/java/com/papershift/ui/rotator` | Main screen (PRD §5.1) |
| `app/src/main/java/com/papershift/ui/settings` | Settings screen (PRD §5.2) |
| `app/src/main/java/com/papershift/ui/theme` | Dark-only Material 3 theme (PRD §8) |
| `docs/` | Product requirements |
