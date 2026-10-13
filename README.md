# Origin Island Patches

Patches for use with Morphe that make YouTube Music and YouTube work with the OriginOS music
card, the player on the Origin Island and the lock screen of vivo and iQOO phones.

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
> **[v1.4.0](https://github.com/JacksonJones2003/origin-island-patches/releases/tag/v1.4.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;3 patches total
<details open>
<summary>📦 YouTube Music&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 9.40.51 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Lyrics in player](#lyrics-in-player) | Shows the line of the lyrics that is sung below the title of the queue in the player. Requires the Third-party lyrics patch. |  |
| [Origin Island music card](#origin-island-music-card) | Lets the OriginOS music card on the lock screen and Origin Island like songs, switch between repeat and shuffle, show the playlist, the playlists of the library and the downloads, and show the lyrics of the Third-party lyrics patch. |  |

</details>

<details open>
<summary>📦 YouTube&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 21.38.123 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Origin Island music card](#origin-island-music-card) | Lets the OriginOS music card on the lock screen and Origin Island like videos, show the captions of a video as its lyrics, and show what plays next as the playlist. |  |

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
- a list with nothing in it says so, instead of loading until the card reports a network error
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

### A copy under a package the card knows

The card only lists the apps of a list of its own to pick from while nothing plays, and
YouTube Music is not one of them. A second, patched copy of YouTube Music that is installed
under the package of one that is, such as `com.luna.music`, can be picked there. For such a
copy the patch does the following:

- the card opens the copy to have it play, and the copy then resumes what it last played
  and moves out of view again
- if YouTube Music is installed under its own package and patched as well, the copy has it
  play instead, and shows its downloads in the download list. A copy cannot read the
  downloads of YouTube Music, so YouTube Music plays the ones that are tapped

The card only offers the lists of an app it has no list support built in for while that
app plays, so the playlist button of a copy stays disabled until something plays.

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
- a copy under another package: the card opens some apps with an extra in the intent to have
  them play, which the main activity of YouTube Music is made to act on. The copy reaches
  YouTube Music through its media browser service, which is made to answer a patched copy

Reverse engineered from `com.vivo.musicwidgetmix` 17.0.8.3 (OriginOS 7)
and tested with YouTube Music 9.40.51.

## Origin Island music card (YouTube)

Makes the OriginOS music card work with YouTube the way it does with the music apps vivo
supports:

- the heart button likes the video that plays, and shows if it is liked
- the captions of the video are shown as synced lyrics
- the playlist shows what plays next, and plays the video that is tapped: the videos of the
  playlist or mix a video is played from, or otherwise the video autoplay continues with
  and the videos YouTube suggests for the video
- a video that was closed in YouTube stays on the card, paused where it was closed. The play
  button plays it again from there, also while YouTube is not in view, and the heart button
  still likes it

### Using it

1. Add this patch source to Morphe Manager, next to the official Morphe patches:
   https://morphe.software/add-source?github=JacksonJones2003/origin-island-patches
2. Patch YouTube in Expert mode, and enable **Origin Island music card** on the tab of this
   source together with the Morphe patches you normally use.
3. Restart the phone once after installing. The card only looks for apps that support these
   features when it starts.

YouTube has to be signed in, since it does not accept likes otherwise.

The captions shown are the ones in the language of the phone, if the video has them, and
otherwise the ones YouTube would show, which can be made by speech recognition. A video
without captions has no lyrics. The card reads the time of a line with two digits for the
minutes, so the lyrics end after 100 minutes of a video.

### How it works

The card finds YouTube the same way as YouTube Music, through the action the patch declares
on the media browser service of YouTube. The session of YouTube tells less than the one of
YouTube Music, so the patch reads the rest from inside the app:

- which video plays: the session does not tell. The media session callback of YouTube reads
  the id of the video to rate it, which the patch makes a getter of
- heart button: YouTube likes the video for a thumb rating, so the heart rating sent by the
  card is turned into one. If a video is liked is kept by YouTube in an entity of its own,
  which the patch reads as YouTube reads or changes it
- lyrics: the player response of a video lists its caption tracks. The patch loads the track
  from the address in that list, the way YouTube does to show captions, and sends the lines
  to the card in the LRC format
- playlist: the watch next response of a video has its playlist, the video autoplay
  continues with and the videos YouTube suggests. The card is answered with these, and the
  video that is tapped is played from its address, as YouTube does for a voice assistant
- a closed video: YouTube has nothing to play or to like once a video is closed, and answers
  the play button with an error. The patch keeps the metadata of the video on the session,
  with a paused playback state, plays the video again from its address, and has YouTube like
  the video by its id. YouTube opens to play a video from an address unless it plays in the
  background already, which Android does not let an app do that is not in view, so the patch
  has YouTube play the video without opening then

Tested with YouTube 21.38.123.

## Lyrics in player (YouTube Music)

Shows the line of the lyrics that is sung in the player, below the title of the queue at the
bottom of it:

- a new line swoops in from below, and each word lights up, swells and shimmers as it is sung
- until the first line is sung, the artist and who wrote the lyrics are named, or where the
  lyrics are from if that is not known
- the line is hidden while the queue is pulled up

The lyrics are those of the **Third-party lyrics** patch of Morphe, which has to be applied
and enabled. Words are only brought out one by one for lyrics that have a time for each word,
which depends on where that patch found them. Other lyrics are shown a line at a time.

### How it works

The line is a view of its own that is added to the player from the main activity, found by
the names of the views of YouTube Music, so that nothing of the player has to be patched. It
is drawn over the header of the queue, which YouTube Music keeps below the title, unseen,
until the queue is pulled up. The position of the song is read from the media session.

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
