# Weather

An Android weather app built with Kotlin, XML layouts, ViewBinding, and a lifecycle-aware ViewModel. Search for a city, choose the correct location, and view current conditions, the next 24 hours, and a seven-day forecast.

## Features

- Live forecasts and city search from [Open-Meteo](https://open-meteo.com/), with no API key required for its free non-commercial endpoint.
- Temperature, feels-like temperature, humidity, wind, daily maximum UV index, and rain probability.
- Celsius / km/h and Fahrenheit / mph, with the preference saved between launches.
- Location-specific dates and times, including forecasts across time zones.
- Last successful forecast saved locally, clearly marked as cached while refreshing or when offline. A failed request keeps the previous forecast and its original city label.
- Loading, empty-search, missing-measurement, and connection-error states. Tap Refresh to retry.
- Johannesburg is the first-launch default; your last successfully loaded city is restored afterward.

## Build and run

Use Android Studio with support for Android Gradle Plugin 9.0.1, JDK 17 or newer, and Android SDK 36. The included Gradle wrapper is 9.1.0. AGP supplies Kotlin support; no separate Kotlin Android plugin is needed.

1. Clone this repository and open its root folder in Android Studio.
2. Install SDK Platform 36 and Build Tools 36.0.0 through SDK Manager.
3. Let Gradle sync, then run the `app` configuration on Android 7.0 / API 24 or newer.

Command-line checks (set `JAVA_HOME` and `ANDROID_HOME` for your machine):

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest  # requires an emulator or device
```

On Windows, use `gradlew.bat` instead of `./gradlew`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. It is a development build; a Play Store release still needs your own signing configuration and release review.

GitHub Actions builds the APK, runs unit tests, Android lint, and device tests on an Android 15 emulator, then uploads the APK and reports. See [Android checks](https://github.com/Manqoba275/Weather-APP/actions/workflows/android.yml).

## How it works

- `WeatherData.kt`: city and forecast models, HTTP requests, JSON parsing, weather-code descriptions, and unit/time formatting.
- `WeatherViewModel.kt`: loading/search/selection state, request cancellation, persisted preferences, and cached forecasts. Network requests run off the main thread; stale responses cannot replace newer results.
- `MainActivity.kt`: renders state through ViewBinding and forecast RecyclerViews.
- `res/layout`: the blue gradient weather interface, with scrolling content and system-bar / keyboard insets.

Unit tests cover forecast windows, missing data, weather codes, time zones, city validation, URL encoding, and unit conversion. Device tests exercise persistence, failure recovery, and the activity controls.

## Data and privacy

The app sends your typed city query to Open-Meteo's geocoding service and the selected city's coordinates to its forecast service over HTTPS. It requests internet access only: no device-location permission, account, analytics, or advertising SDK. The selected city, forecast snapshot, fetch time, and unit preference are stored in app-local SharedPreferences. Android backup behavior is controlled by the included manifest and backup rules.

Weather data is provided by [Open-Meteo](https://open-meteo.com/) under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/); geocoding uses [GeoNames](https://www.geonames.org/). Review the provider's [terms](https://open-meteo.com/en/terms) before commercial distribution. Forecasts depend on the service's availability and update schedule; cached data may be old and is labelled with its saved time.

## Manual acceptance checks

1. On a fresh install, confirm Johannesburg loads and all three forecast sections populate.
2. Search a city with multiple matches, select the intended country/region, and confirm the location and its time zone change together.
3. Switch units, restart the app, and confirm the preference remains selected.
4. Load a forecast, disable connectivity, then refresh. Confirm the saved forecast remains visible with a cached label and an error message.
5. Try an empty query and an unknown city; confirm helpful messages and no fabricated weather values.
6. Rotate the device and try a larger font size; confirm the page remains scrollable and the controls usable.
