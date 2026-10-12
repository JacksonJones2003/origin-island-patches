# Origin Island Patches

Patches for use with Morphe that make YouTube Music work with the OriginOS music card,
the player on the Origin Island and the lock screen of vivo and iQOO phones.

> [!NOTE]
> This project was made entirely by Claude, the AI model of Anthropic: the reverse engineering,
> the patches and this README. It was tested by the owner of this repository on one vivo phone
> running OriginOS 7.

## Disclaimer

This is an unofficial hobby project. It is not affiliated with, endorsed by or supported by
vivo, iQOO, Google, YouTube or the Morphe project, and all names and trademarks belong to
their owners.

The patches change how another app behaves, which can be against the terms of service of that
app. They are provided as they are, without warranty of any kind, and you use them at your own
risk. See the [license](LICENSE) for details.

## Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.1.0](https://github.com/JacksonJones2003/origin-island-patches/releases/tag/v1.1.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
<details open>
<summary>📦 YouTube Music&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 9.40.51 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Origin Island music card](#origin-island-music-card) | Lets the OriginOS music card on the lock screen and Origin Island like songs, switch between repeat and shuffle, show the playlist, the playlists of the library and the downloads, and show the lyrics of the Third-party lyrics patch. |  |

</details>

<!-- PATCHES_END -->

## Origin Island music card (YouTube Music)

Makes the OriginOS music card work with YouTube Music the way it does with the music apps
vivo supports:

- the heart button likes the current song, and shows if it is liked
- the loop mode button switches between repeat all, repeat one and shuffle
- the playlist shows the queue, and plays the song that is tapped
- the favorites list shows the playlists of the library, and plays the one that is tapped
- the download list shows the downloaded playlists and mixes, and plays the one that is tapped
- synced lyrics are shown, if the **Third-party lyrics** patch of Morphe is applied and enabled

### Using it

1. Add this patch source to Morphe Manager, next to the official Morphe patches:
   https://morphe.software/add-source?github=JacksonJones2003/origin-island-patches
2. Patch YouTube Music in Expert mode, and enable **Origin Island music card** on the tab of
   this source together with the Morphe patches you normally use.
3. Restart the phone once after installing. The card only looks for apps that support these
   features when it starts.

YouTube Music has to be signed in, since it does not accept likes otherwise.

The favorites list can take a few seconds to load the first time after YouTube Music is
started, while YouTube Music loads the library.

### How it works

The card (`com.vivo.musicwidgetmix`) controls most apps through a generic media session
controller that only knows the basic playback controls, which is why the other buttons are
dimmed for them. It offers the rest to apps it controls through a media browser service that
declares the `com.vivo.musicwidgetmix.support.service` action, and reads what it shows from
keys of its own in the session metadata and the session extras.

The patch opts YouTube Music into that path and translates between the two:

- declares the action on YouTube Music's media browser service, and lets the card connect to it
- adds to the metadata YouTube Music publishes what the card may show and control,
  and a media id so the card notices song changes
- heart button: adds a heart rating from the like action of YouTube Music's playback state,
  which is kept up to date better than its own thumb rating, and turns the heart rating sent
  by the card into a thumb rating, since YouTube Music would otherwise treat a heart as a
  dislike
- loop mode button: maps the loop mode of the card to the repeat and shuffle modes of the session
- playlist: answers the card's request for the current list with the queue of the session
- favorites and download list: YouTube Music only lets clients it knows, such as Android Auto,
  browse its library. The patch connects to YouTube Music's media browser service from inside
  the app, has the app take that connection for Android Auto, and answers the card with the
  playlists and the downloads it finds. The card plays them from their own media ids
- lyrics: sends the lyrics the Third-party lyrics patch loaded to the card in the LRC format

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
