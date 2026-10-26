# Meta Stamp

Android app that adds a GPS / date stamp to photos using their own metadata.

- Reads latitude, longitude and the original capture date from each photo's EXIF.
- Looks up the address (with Plus Code) and a small OpenStreetMap thumbnail.
- Draws the stamp along the bottom: map, address, `Lat … Long …`, and the date in a yellow-on-black box.
- Rotate (90° turns and a fine straighten slider) and crop each photo before stamping (free, original, 1:1, 4:3, 3:4, 16:9, 9:16).
- Everything can be edited before saving. Saved photos go to **Pictures/MetaStamp** with full metadata: the original camera details plus your edited GPS location, date and address (in the image description), so the gallery shows the right date and place.
- Pick photos in the app, or share them to Meta Stamp from your gallery. Batch save is supported.

## Download

**[MetaStamp.apk (latest)](https://github.com/Tajmalookcs/metadatastemp_app/releases/latest/download/MetaStamp.apk)**

Every push to `main` builds a new APK with GitHub Actions and publishes it as the latest release.

## Notes

- Allow the photo and **media location** permissions, otherwise Android hides GPS data from the app.
- Address lookup and the map need internet. Offline, the stamp still shows the Plus Code, coordinates and date.
- Map data © OpenStreetMap contributors.
