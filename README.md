# Origin Island Patches

Morphe patches that integrate apps with the OriginOS music card
(lock screen and Origin Island) on vivo and iQOO phones.

Hobby project, not affiliated with Morphe, vivo or Google.

## Patches list

<!-- PATCHES_START EXPANDED -->

The patches list is generated when a release is created.

<!-- PATCHES_END -->

## Origin Island like button (YouTube Music)

Makes the heart button of the OriginOS music card like the current song.

### Using it

1. Add this patch source to Morphe Manager, next to the official Morphe patches.
2. Patch YouTube Music in Expert mode, and enable **Origin Island like button** on the tab of
   this source together with the Morphe patches you normally use.
3. Restart the phone once after installing. The card only looks for apps that support the
   heart button when it starts.

YouTube Music has to be signed in, since it does not accept likes otherwise.

### How it works

The card (`com.vivo.musicwidgetmix`) controls most apps through a generic media session
controller that has no favorite support, which is why the heart is dimmed for them.
It only offers the heart to apps it controls through a media browser service that declares
the `com.vivo.musicwidgetmix.support.service` action. For those apps it draws the heart from
a heart `Rating` in the session metadata, and sends `setRating()` with a heart rating when
it is tapped.

The patch opts YouTube Music into that path:

- declares the action on YouTube Music's media browser service, and lets the card connect to it
- adds to the metadata YouTube Music publishes: the heart rating (from its own thumb rating),
  the capabilities the card may use, and a media id so the card notices song changes
- turns the heart rating sent by the card into the thumb rating YouTube Music expects,
  since YouTube Music would otherwise treat a heart as a dislike

Reverse engineered from `com.vivo.musicwidgetmix` 17.0.8.3 (OriginOS 7)
and tested with YouTube Music 9.40.51.

## Building locally

Requires JDK 17+, the Android SDK, and a GitHub token with the `read:packages` scope
set as `gpr.user` / `gpr.key` in `~/.gradle/gradle.properties`
(see the [Morphe patcher setup](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/2_1_setup.md#-prepare-the-environment)).

- Run `./gradlew buildAndroid`
- The built patches file is found in `patches/build/libs/patches-*.mpp`
- Add it to Morphe Manager as a local patch source, or apply it with
  [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop)

## License

Licensed under the [GNU General Public License v3.0](LICENSE). See [NOTICE](NOTICE) for the
additional terms inherited from the Morphe patches template.
