package app.originisland.extension;

import android.content.Context;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Makes YouTube Music speak the protocol of the OriginOS music card (com.vivo.musicwidgetmix).
 *
 * The card reads what it shows from the session metadata and the session extras, and controls
 * the app through the transport controls of the session:
 * <ul>
 *     <li>The heart button is drawn from a heart {@link Rating} in the metadata, and sends
 *     a heart rating. YouTube Music rates with thumbs instead, and tells if a song is liked
 *     with a custom action of the playback state.</li>
 *     <li>The loop mode button is drawn from a mode in the metadata, and sends a custom action.
 *     YouTube Music has a repeat mode and a shuffle mode instead.</li>
 *     <li>Lyrics are read from the session extras. They are taken from the
 *     "Third-party lyrics" patch of Morphe, if it is applied.</li>
 *     <li>The playlist, the favorites and the downloads are loaded from the media browser
 *     service, and what is tapped in them is played from its media id. The playlist is the
 *     queue of the session here, and the other two are playlists of the library of the app.</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class OriginIslandMusicCardPatch {

    private static final String TAG = "OriginIslandMusicCard";

    private static final String MUSIC_CARD_PACKAGE = "com.vivo.musicwidgetmix";

    /**
     * Bit mask of what the card may show and control. Without it the card disables everything.
     */
    private static final String KEY_SUPPORT_EVENT = "vivomusicmix.media.metadata.support_event";
    private static final long EVENT_PLAY_CONTROL = 1;
    private static final long EVENT_MUSIC_INFO = 1 << 1;
    private static final long EVENT_MUSIC_IMAGE = 1 << 2;
    private static final long EVENT_MUSIC_LRC = 1 << 3;
    private static final long EVENT_SEEK_POSITION = 1 << 4;
    private static final long EVENT_FAVORITE = 1 << 6;
    private static final long EVENT_LOOP_MODE = 1 << 7;
    private static final long EVENT_LIST_CURRENT = 1 << 8;
    private static final long EVENT_LIST_FAVORITE = 1 << 9;
    private static final long EVENT_LIST_LOCAL = 1 << 10;

    private static final String KEY_LOOP_MODE = "vivomusicmix.media.metadata.LOOP_MODE";
    private static final int LOOP_MODE_LIST = 1;
    private static final int LOOP_MODE_SINGLE = 2;
    private static final int LOOP_MODE_RANDOM = 3;

    /**
     * Custom action the card sends with the {@link #KEY_LOOP_MODE} it wants to change to.
     */
    private static final String ACTION_PLAY_MODE = "vivomusicmix.media.action.PLAY_MODE";

    // Session extras the card reads lyrics from. The keys are misspelled by the card.
    private static final String EXTRA_ACTION = "vivomusicmix.meida.extra.key.action";
    private static final String EXTRA_ACTION_LYRICS_CHANGED = "vivomusicmix.extra.lrc_change";
    private static final String EXTRA_MEDIA_ID = "vivomusicmix.extra.key.meidia_id";
    private static final String EXTRA_LYRICS = "vivomusicmix.extra.key.lyric";

    /**
     * Custom actions of the playback state the app likes a song with, or removes the like with.
     * They are named after which of the two they do, with these strings of the app.
     */
    private static final String[] LIKE_ACTIONS = {"thumbs_up_action", "waze.thumbUp"};
    private static final String LIKE_ACTION_NAME_LIKE = "accessibility_like_video";
    private static final String LIKE_ACTION_NAME_REMOVE_LIKE = "accessibility_undo_like_video";

    /**
     * Package of the resources of the app, which patches can install under another package.
     */
    private static final String APP_RESOURCES_PACKAGE = "com.google.android.apps.youtube.music";

    private static final String BROWSER_ROOT_ID = "origin_island_music_card";

    /**
     * Media browser parents of the lists, which the card loads in pages. The card reads
     * from the extras of the last song of a page if there is another page, and its number.
     */
    private static final String LIST_CURRENT = "vivomusicmix_current_list";
    private static final String LIST_FAVORITE = "vivomusicmix_favorite_list";
    private static final String LIST_LOCAL = "vivomusicmix_local_list";
    private static final String LIST_KEY_PAGE = "vivomusicmix_key_media_page";
    private static final String LIST_KEY_HAS_MORE = "vivomusicmix_key_has_more";

    /**
     * Extra of the list a song is played from, sent with its media id.
     */
    private static final String KEY_LIST = "vivomusicmix_key_list";

    /**
     * The card only listens for lyrics once it has connected, which takes a moment.
     */
    private static final long SEND_LYRICS_AFTER_CONNECTING_DELAY_MS = 1500;

    /**
     * Changing the loop mode changes the repeat and the shuffle mode one after the other,
     * and the app changes its playback state several times as it starts a song.
     */
    private static final long PUBLISH_LAST_METADATA_DELAY_MS = 150;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Runnable publishLastMetadataTask = OriginIslandMusicCardPatch::publishLastMetadata;

    private static WeakReference<MediaSession> sessionRef = new WeakReference<>(null);
    private static WeakReference<MediaBrowserService> browserServiceRef = new WeakReference<>(null);
    private static PlayModes playModes;
    private static Boolean lyricsSupported;
    private static Context appContext;
    private static boolean libraryRequested;

    /**
     * If the song is liked according to the playback state, or null if it does not tell.
     */
    private static Boolean likedByPlaybackState;

    /**
     * Metadata as last given to the session, before the keys of the card are added.
     */
    private static MediaMetadata lastMetadata;
    private static String trackId = "";
    private static String lyrics = "";

    private OriginIslandMusicCardPatch() {
    }

    /**
     * Injection point.
     *
     * The app only answers clients of the media library it knows, and answers them through
     * a channel of that library. The card is given a root of the platform instead,
     * so that everything it asks the media browser service for goes through the platform.
     *
     * @return The root for the client connecting to the media browser service,
     *         or null if the client is not the music card.
     */
    public static MediaBrowserService.BrowserRoot onGetBrowserRoot(String clientPackageName) {
        if (!MUSIC_CARD_PACKAGE.equals(clientPackageName)) {
            return null;
        }

        mainHandler.postDelayed(OriginIslandMusicCardPatch::sendLyrics, SEND_LYRICS_AFTER_CONNECTING_DELAY_MS);

        return new MediaBrowserService.BrowserRoot(BROWSER_ROOT_ID, null);
    }

    /**
     * Injection point. Called on the main thread with the metadata of the app.
     */
    public static synchronized MediaMetadata onSetMetadata(MediaSession session, MediaMetadata metadata) {
        try {
            attachSession(session);

            if (metadata != null) {
                // The card ignores a song change unless the media id changes as well.
                String mediaId = mediaIdOf(
                        metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                        metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                );

                if (!mediaId.equals(trackId)) {
                    trackId = mediaId;
                    // The lyrics patch may have changed the lyrics before or after this call.
                    mainHandler.post(OriginIslandMusicCardPatch::sendLyrics);

                    // The app adds songs to the queue as it plays.
                    MediaBrowserService browserService = browserServiceRef.get();
                    if (browserService != null) {
                        browserService.notifyChildrenChanged(LIST_CURRENT);
                    }
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "onSetMetadata failure", ex);
        }

        return onSetExtensionMetadata(session, metadata);
    }

    /**
     * Injection point. Called with the metadata other patches publish in place of the app,
     * which can describe the track differently, such as with a line of the lyrics as the title.
     */
    public static synchronized MediaMetadata onSetExtensionMetadata(MediaSession session, MediaMetadata metadata) {
        try {
            // Other patches can have a session of their own.
            if (session == sessionRef.get()) {
                lastMetadata = metadata;
            }

            if (metadata != null) {
                return withMusicCardKeys(metadata, session == sessionRef.get());
            }
        } catch (Exception ex) {
            Log.e(TAG, "onSetExtensionMetadata failure", ex);
        }

        return metadata;
    }

    /**
     * Injection point. Called with the playback state of the app.
     *
     * The app does not always update the rating of the metadata when it learns that a song
     * is liked, such as for the first song it plays after it is started. It does update
     * the custom action that likes the song.
     */
    public static synchronized void onSetPlaybackState(MediaSession session, PlaybackState state) {
        try {
            if (session != sessionRef.get()) {
                return;
            }

            Boolean liked = likedOf(state);
            if (!Objects.equals(liked, likedByPlaybackState)) {
                likedByPlaybackState = liked;
                publishLastMetadataSoon();
            }
        } catch (Exception ex) {
            Log.e(TAG, "onSetPlaybackState failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * The app likes the song for a thumb up, removes the like for an unrated thumb,
     * and dislikes the song for any other rating, which includes every heart rating.
     */
    public static Rating onSetRating(Rating rating) {
        try {
            if (rating == null || rating.getRatingStyle() != Rating.RATING_HEART) {
                return rating;
            }

            boolean like = rating.hasHeart();
            Log.i(TAG, "Heart rating received: " + like);

            return like
                    ? Rating.newThumbRating(true)
                    : Rating.newUnratedRating(Rating.RATING_THUMB_UP_DOWN);
        } catch (Exception ex) {
            Log.e(TAG, "onSetRating failure", ex);
        }

        return rating;
    }

    /**
     * Injection point.
     *
     * @return The custom action the app should handle, or null if it was handled here.
     */
    public static synchronized String onCustomAction(String action, Bundle extras) {
        try {
            if (!ACTION_PLAY_MODE.equals(action)) {
                return action;
            }

            int loopMode = extras == null ? 0 : extras.getInt(KEY_LOOP_MODE);
            Log.i(TAG, "Loop mode received: " + loopMode);

            if (playModes != null) {
                if (loopMode == LOOP_MODE_SINGLE) {
                    playModes.setRepeatMode(PlayModes.REPEAT_MODE_ONE);
                } else {
                    // The app can refuse to repeat all, so the repeat mode is turned off first.
                    // Otherwise repeat one would be kept, and the card would be stuck on single.
                    playModes.setRepeatMode(PlayModes.REPEAT_MODE_NONE);
                    playModes.setRepeatMode(PlayModes.REPEAT_MODE_ALL);
                }
                playModes.setShuffleMode(loopMode == LOOP_MODE_RANDOM
                        ? PlayModes.SHUFFLE_MODE_ALL
                        : PlayModes.SHUFFLE_MODE_NONE);
            }

            return null;
        } catch (Exception ex) {
            Log.e(TAG, "onCustomAction failure", ex);
        }

        return action;
    }

    /**
     * Injection point.
     *
     * @return The parent the app should load the children of, or null if they were loaded here.
     */
    public static synchronized String onLoadChildren(MediaBrowserService browserService, String parentId,
                                                     MediaBrowserService.Result<List<MediaBrowser.MediaItem>> result,
                                                     Bundle options) {
        try {
            LibraryBrowser.LibraryList libraryList;
            if (LIST_FAVORITE.equals(parentId)) {
                libraryList = LibraryBrowser.PLAYLISTS;
            } else if (LIST_LOCAL.equals(parentId)) {
                libraryList = LibraryBrowser.DOWNLOADS;
            } else if (LIST_CURRENT.equals(parentId)) {
                libraryList = null;
            } else {
                return parentId;
            }

            // Every list is a single page.
            int page = options == null ? 0 : options.getInt(LIST_KEY_PAGE);

            if (libraryList != null) {
                if (page == 0) {
                    result.detach();
                    LibraryBrowser.load(browserService, libraryList, children ->
                            sendLibraryList(result, children));
                } else {
                    result.sendResult(new ArrayList<>());
                }

                return null;
            }

            browserServiceRef = new WeakReference<>(browserService);

            // The card loads the playlist as its lists are opened. The app takes a while to load
            // its library the first time, so it is asked to before the card loads the favorites.
            if (!libraryRequested) {
                libraryRequested = true;
                LibraryBrowser.load(browserService, LibraryBrowser.PLAYLISTS, children -> {
                });
            }

            List<MediaDescription> songs = new ArrayList<>();
            List<MediaSession.QueueItem> queue = page == 0 ? getQueue() : null;
            if (queue != null) {
                for (MediaSession.QueueItem song : queue) {
                    MediaDescription description = song.getDescription();
                    songs.add(new MediaDescription.Builder()
                            .setMediaId(mediaIdOf(description.getTitle(), description.getSubtitle()))
                            .setTitle(description.getTitle())
                            .setSubtitle(description.getSubtitle())
                            .build());
                }
            }

            Log.i(TAG, "Playlist loaded: " + songs.size() + " songs");
            result.sendResult(toSinglePage(songs));

            return null;
        } catch (Exception ex) {
            Log.e(TAG, "onLoadChildren failure", ex);
        }

        return parentId;
    }

    /**
     * Injection point.
     */
    public static String onLoadChildren(MediaBrowserService browserService, String parentId,
                                        MediaBrowserService.Result<List<MediaBrowser.MediaItem>> result) {
        return onLoadChildren(browserService, parentId, result, null);
    }

    private static void sendLibraryList(MediaBrowserService.Result<List<MediaBrowser.MediaItem>> result,
                                        List<MediaBrowser.MediaItem> children) {
        try {
            List<MediaDescription> playlists = new ArrayList<>();
            if (children != null) {
                for (MediaBrowser.MediaItem child : children) {
                    if (!child.isPlayable()) {
                        continue;
                    }

                    // The app plays these from their media ids for every client,
                    // so the card is given them as they are.
                    MediaDescription description = child.getDescription();
                    playlists.add(new MediaDescription.Builder()
                            .setMediaId(child.getMediaId())
                            .setTitle(description.getTitle())
                            // The card shows what is not set as text.
                            .setSubtitle(description.getSubtitle() == null ? "" : description.getSubtitle())
                            .build());
                }
            }

            result.sendResult(toSinglePage(playlists));
        } catch (Exception ex) {
            Log.e(TAG, "sendLibraryList failure", ex);
        }
    }

    /**
     * @return The items of a list as the only page the card has to load of it.
     */
    private static List<MediaBrowser.MediaItem> toSinglePage(List<MediaDescription> descriptions) {
        List<MediaBrowser.MediaItem> items = new ArrayList<>();
        for (int i = 0; i < descriptions.size(); i++) {
            MediaDescription description = descriptions.get(i);

            if (i == descriptions.size() - 1) {
                Bundle extras = new Bundle();
                extras.putBoolean(LIST_KEY_HAS_MORE, false);
                extras.putInt(LIST_KEY_PAGE, 1);

                description = new MediaDescription.Builder()
                        .setMediaId(description.getMediaId())
                        .setTitle(description.getTitle())
                        .setSubtitle(description.getSubtitle())
                        .setExtras(extras)
                        .build();
            }

            items.add(new MediaBrowser.MediaItem(description, MediaBrowser.MediaItem.FLAG_PLAYABLE));
        }

        return items;
    }

    /**
     * Injection point.
     *
     * @return The media id the app should play, or null if it was played here.
     */
    public static synchronized String onPlayFromMediaId(String mediaId, Bundle extras) {
        try {
            String list = extras == null ? null : extras.getString(KEY_LIST);
            if (LIST_FAVORITE.equals(list) || LIST_LOCAL.equals(list)) {
                Log.i(TAG, "Playing from " + list + ": " + mediaId);
            }

            if (mediaId == null || !LIST_CURRENT.equals(list)) {
                return mediaId;
            }

            MediaSession session = sessionRef.get();
            List<MediaSession.QueueItem> queue = getQueue();
            if (session != null && queue != null) {
                for (MediaSession.QueueItem song : queue) {
                    MediaDescription description = song.getDescription();
                    if (mediaId.equals(mediaIdOf(description.getTitle(), description.getSubtitle()))) {
                        session.getController().getTransportControls().skipToQueueItem(song.getQueueId());
                        break;
                    }
                }
            }

            return null;
        } catch (Exception ex) {
            Log.e(TAG, "onPlayFromMediaId failure", ex);
        }

        return mediaId;
    }

    private static List<MediaSession.QueueItem> getQueue() {
        MediaSession session = sessionRef.get();
        return session == null ? null : session.getController().getQueue();
    }

    /**
     * The app gives its songs no media id that is the same in the metadata and in the queue.
     */
    private static String mediaIdOf(CharSequence title, CharSequence artist) {
        return Integer.toHexString(Objects.hash(
                title == null ? null : title.toString(),
                artist == null ? null : artist.toString()
        ));
    }

    private static void attachSession(MediaSession session) {
        if (lyricsSupported == null) {
            lyricsSupported = MorpheLyrics.addListener(OriginIslandMusicCardPatch::onLyricsChanged);
        }

        if (session != null && sessionRef.get() != session) {
            sessionRef = new WeakReference<>(session);
            playModes = new PlayModes(session, mainHandler, OriginIslandMusicCardPatch::publishLastMetadataSoon);
            // The app can have set the playback state of the session before its metadata.
            likedByPlaybackState = likedOf(session.getController().getPlaybackState());
        }
    }

    private static void publishLastMetadataSoon() {
        mainHandler.removeCallbacks(publishLastMetadataTask);
        mainHandler.postDelayed(publishLastMetadataTask, PUBLISH_LAST_METADATA_DELAY_MS);
    }

    /**
     * @return If the song is liked according to the playback state, or null if it does not tell.
     */
    private static Boolean likedOf(PlaybackState state) {
        if (state == null) {
            return null;
        }

        for (PlaybackState.CustomAction action : state.getCustomActions()) {
            for (String likeAction : LIKE_ACTIONS) {
                if (!likeAction.equals(action.getAction())) {
                    continue;
                }

                String name = String.valueOf(action.getName());
                if (name.equals(getAppString(LIKE_ACTION_NAME_REMOVE_LIKE))) {
                    return true;
                }
                if (name.equals(getAppString(LIKE_ACTION_NAME_LIKE))) {
                    return false;
                }
            }
        }

        return null;
    }

    /**
     * @return The string of the app with this name, or null if it has none.
     */
    private static String getAppString(String name) {
        try {
            if (appContext == null) {
                // Hidden, but not restricted.
                appContext = (Context) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication")
                        .invoke(null);
            }
            if (appContext == null) {
                return null;
            }

            for (String resourcesPackage : new String[]{appContext.getPackageName(), APP_RESOURCES_PACKAGE}) {
                int id = appContext.getResources().getIdentifier(name, "string", resourcesPackage);
                if (id != 0) {
                    return appContext.getString(id);
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "getAppString failure", ex);
        }

        return null;
    }

    private static synchronized void onLyricsChanged(String lrc) {
        if (!lrc.equals(lyrics)) {
            lyrics = lrc;
            // The app may still be about to publish the track these lyrics are for.
            mainHandler.post(OriginIslandMusicCardPatch::sendLyrics);
        }
    }

    private static synchronized void sendLyrics() {
        try {
            MediaSession session = sessionRef.get();
            if (session == null || !Boolean.TRUE.equals(lyricsSupported)) {
                return;
            }

            // The app keeps extras of its own on the session.
            Bundle appExtras = session.getController().getExtras();
            Bundle extras = appExtras == null ? new Bundle() : new Bundle(appExtras);
            extras.putString(EXTRA_ACTION, EXTRA_ACTION_LYRICS_CHANGED);
            extras.putString(EXTRA_MEDIA_ID, trackId);
            extras.putString(EXTRA_LYRICS, lyrics);
            session.setExtras(extras);

            Log.i(TAG, "Lyrics sent: " + lyrics.length() + " characters");
        } catch (Exception ex) {
            Log.e(TAG, "sendLyrics failure", ex);
        }
    }

    /**
     * The card only reads the loop mode and what it may control when the metadata changes.
     */
    private static synchronized void publishLastMetadata() {
        try {
            MediaSession session = sessionRef.get();
            if (session != null && lastMetadata != null) {
                session.setMetadata(withMusicCardKeys(lastMetadata, true));
            }
        } catch (Exception ex) {
            Log.e(TAG, "publishLastMetadata failure", ex);
        }
    }

    /**
     * @param isAppSession If the metadata is for the session of the app,
     *                     and not for a session of another patch.
     */
    private static MediaMetadata withMusicCardKeys(MediaMetadata metadata, boolean isAppSession) {
        Rating userRating = metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
        boolean liked = isAppSession && likedByPlaybackState != null
                ? likedByPlaybackState
                : userRating != null && userRating.isThumbUp();

        long supportEvent = EVENT_PLAY_CONTROL | EVENT_MUSIC_INFO | EVENT_MUSIC_IMAGE
                | EVENT_SEEK_POSITION | EVENT_FAVORITE
                | EVENT_LIST_CURRENT | EVENT_LIST_FAVORITE | EVENT_LIST_LOCAL;

        MediaMetadata.Builder builder = new MediaMetadata.Builder(metadata)
                .putRating(MediaMetadata.METADATA_KEY_RATING, Rating.newHeartRating(liked))
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, trackId);

        if (Boolean.TRUE.equals(lyricsSupported)) {
            supportEvent |= EVENT_MUSIC_LRC;
        }

        if (playModes != null && playModes.isKnown()) {
            supportEvent |= EVENT_LOOP_MODE;

            int loopMode;
            if (playModes.getShuffleMode() >= PlayModes.SHUFFLE_MODE_ALL) {
                loopMode = LOOP_MODE_RANDOM;
            } else if (playModes.getRepeatMode() == PlayModes.REPEAT_MODE_ONE) {
                loopMode = LOOP_MODE_SINGLE;
            } else {
                loopMode = LOOP_MODE_LIST;
            }
            builder.putLong(KEY_LOOP_MODE, loopMode);
        }

        return builder.putLong(KEY_SUPPORT_EVENT, supportEvent).build();
    }
}
