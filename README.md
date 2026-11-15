# Cortado ☕👶

An Android app for finding pram- and baby-friendly cafés in Copenhagen or any other city.

- Map and list of cafés (optionally restaurants and ice-cream shops) from **OpenStreetMap**
- Filters: baby friendly, changing table, pram / step-free access (ramps), highchairs, play corner, breastfeeding friendly, accessible toilet, toilets, outdoor seating
- City and neighbourhood search, "my location", directions, website and phone
- Saved favourites (stored on the device)
- **Add or correct info:** parents' reports are posted as anonymous [OpenStreetMap Notes](https://wiki.openstreetmap.org/wiki/Notes), and OSM volunteers add them to the map
- No backend, no account, no ads, no tracking

## Tech
Kotlin · Jetpack Compose (Material 3) · osmdroid (OSM raster map) · Overpass API · Nominatim · DataStore.
minSdk 26, targetSdk 36. Package: `app.cortado` (change `applicationId` in `app/build.gradle.kts` **before** your first Play upload, because it can never change afterwards).

```
app/src/main/java/app/cortado/
  CortadoApp.kt          app setup: HTTP user agent, osmdroid config, simple dependency wiring
  data/Place.kt          model + Feature enum (what we filter on)
  data/OsmTags.kt        OSM tags → feature status (yes / limited / no / unknown)
  data/Filters.kt        filter + sort logic
  data/Network.kt        Overpass, Nominatim and OSM Notes clients
  data/UserStore.kt      favourites, filters, last camera (DataStore)
  ui/MainActivity.kt     screen layout: search, filter chips, Map/List/Saved tabs
  ui/MapPane.kt          osmdroid map + coloured pins + my-location
  ui/PlaceViews.kt       list rows + details bottom sheet
  ui/ReportDialog.kt     "Add or correct info" form
```

### How tags are read
| Feature | OSM tags (first one present wins) |
|---|---|
| Changing table | `changing_table`, `diaper` (legacy), `toilets:changing_table` |
| Pram / step-free | `stroller`, `wheelchair`, `entrance:step_count=0`, `ramp=yes` |
| Highchairs | `highchair` (yes or a count) |
| Play corner | `kids_area`, `kids_area:indoor`, `playground` |
| Breastfeeding | `baby_feeding`, `breastfeeding` |
| Accessible toilet | `toilets:wheelchair` |
| "Baby friendly" | at least one of changing table / highchair / play corner / breastfeeding, or `stroller=yes` |

## Data: offline by default, refreshed daily

The public Overpass servers are often overloaded (30–60 s per request), so the app keeps its own copy of the data on the phone:

1. **Daily snapshot.** `.github/workflows/data.yml` runs `scripts/build_snapshot.py` every night. It downloads all cafés, restaurants, ice-cream and fast-food places for the regions in `scripts/regions.json` (currently Denmark: about 13,500 places, **0.5 MB** compressed), keeps only the tags the app uses and publishes them to GitHub Pages:
   `https://raghavian.github.io/cortado/v1/index.json` and `.../v1/regions/dk.json.gz`
2. **Offline on the phone.** On first launch the app downloads the whole region file, then searches and filters it locally: instant and with no signal needed. `SyncWorker` (WorkManager) re-checks once a day and only downloads again when a newer snapshot has been published.
3. **Live Overpass fallback** for areas outside the downloaded regions (e.g. Malmö). The last 12 such areas are cached on the device.

To cover another country, add it to `scripts/regions.json`. (Every phone currently downloads every region. Once there are many countries, the app should pick regions by location.) Snapshot data is © OpenStreetMap contributors (ODbL), and the attribution is included in every file.

**One-time setup:** in the GitHub repository go to *Settings → Pages → Build and deployment → Source* and choose **GitHub Actions**. Then run the workflow once from the *Actions* tab ("Publish café data" → *Run workflow*).

The map pictures themselves still load online (and are cached as you browse). See "Ideas for later" for a fully offline map.

## Build

Requires JDK 17+ and the Android SDK (`local.properties` → `sdk.dir=...`).

```bash
./gradlew testDebugUnitTest            # unit tests
./gradlew testDebugUnitTest -Plive     # also hit the real Overpass/Nominatim servers
./gradlew assembleDebug                # app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

The **debug** build sends reports to the OSM *sandbox* (`master.apis.dev.openstreetmap.org`), so testing never touches the real map. Only the **release** build posts to openstreetmap.org.

## Publishing to Google Play

1. **Create an upload key** (once; keep it backed up and out of git):
   ```bash
   keytool -genkey -v -keystore ~/cortado-upload.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload
   ```
   Then create `keystore.properties` in the project root:
   ```properties
   storeFile=/home/you/cortado-upload.jks
   storePassword=...
   keyAlias=upload
   keyPassword=...
   ```
2. **Build the bundle:** `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`
3. **Play Console** (https://play.google.com/console, one-time $25 fee plus identity verification):
   - Create the app, enrol in Play App Signing, and upload the `.aab` to a testing track.
   - New *personal* developer accounts must run a **closed test with at least 12 testers for 14 days** before production access is unlocked.
   - Fill in the store listing, data safety, content rating and target audience using `store/listing.md`.
   - The privacy policy must be at a public URL. Host `PRIVACY.md` (e.g. GitHub Pages) and add your contact email to it first.
4. Bump `versionCode` in `app/build.gradle.kts` for every upload.

## Before going big: usage policies
- **Map tiles:** the app uses `tile.openstreetmap.org`, which is fine for a small app but has a [usage policy](https://operations.osmfoundation.org/policies/tiles/) that discourages heavy app traffic. If the app grows, switch to a hosted tile provider (MapTiler, Stadia, Thunderforest, …) in `MapPane.kt` (`setTileSource`).
- **Overpass / Nominatim:** public, free instances with fair-use limits. The app only queries when the user searches an area (max ~10×10 km) and only geocodes on submit, as their policies require.
- **OSM Notes:** reports are public and anonymous. The form asks users to report only what they saw.

## Ideas for later
- Fully offline map: switch to MapLibre with a self-hosted PMTiles file (~30–60 MB for Copenhagen)
- Marker clustering when zoomed out
- Show the user's own pending reports on the map until OSM is updated
- A Danish translation (`res/values-da/strings.xml`; UI strings would first need moving into resources)
- Optional backend (e.g. Firebase) for instant community ratings
