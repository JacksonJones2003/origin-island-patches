package app.originisland.extension;

import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
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
 *     a heart rating. YouTube Music rates with thumbs instead.</li>
 *     <li>The loop mode button is drawn from a mode in the metadata, and sends a custom action.
 *     YouTube Music has a repeat mode and a shuffle mode instead.</li>
 *     <li>Lyrics are read from the session extras. They are taken from the
 *     "Third-party lyrics" patch of Morphe, if it is applied.</li>
 *     <li>The playlist is loaded from the media browser service, and a song of it is played
 *     from its media id. It is the queue of the session here.</li>
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

    private static final String BROWSER_ROOT_ID = "origin_island_music_card";

    /**
     * Media browser parent of the playlist, which the card loads in pages. The card reads
     * from the extras of the last song of a page if there is another page, and its number.
     */
    private static final String LIST_CURRENT = "vivomusicmix_current_list";
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
     * Changing the loop mode changes the repeat and the shuffle mode one after the other.
     */
    private static final long PUBLISH_PLAY_MODES_DELAY_MS = 150;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Runnable publishLastMetadataTask = OriginIslandMusicCardPatch::publishLastMetadata;

    private static WeakReference<MediaSession> sessionRef = new WeakReference<>(null);
    private static WeakReference<MediaBrowserService> browserServiceRef = new WeakReference<>(null);
    private static PlayModes playModes;
    private static Boolean lyricsSupported;

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
                return withMusicCardKeys(metadata);
            }
        } catch (Exception ex) {
            Log.e(TAG, "onSetExtensionMetadata failure", ex);
        }

        return metadata;
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
            if (!LIST_CURRENT.equals(parentId)) {
                return parentId;
            }

            browserServiceRef = new WeakReference<>(browserService);

            int page = options == null ? 0 : options.getInt(LIST_KEY_PAGE);
            List<MediaBrowser.MediaItem> songs = new ArrayList<>();
            // The whole queue is the first page.
            List<MediaSession.QueueItem> queue = page == 0 ? getQueue() : null;
            if (queue != null) {
                for (int i = 0; i < queue.size(); i++) {
                    MediaDescription description = queue.get(i).getDescription();

                    MediaDescription.Builder song = new MediaDescription.Builder()
                            .setMediaId(mediaIdOf(description.getTitle(), description.getSubtitle()))
                            .setTitle(description.getTitle())
                            .setSubtitle(description.getSubtitle());

                    if (i == queue.size() - 1) {
                        Bundle extras = new Bundle();
                        extras.putBoolean(LIST_KEY_HAS_MORE, false);
                        extras.putInt(LIST_KEY_PAGE, page + 1);
                        song.setExtras(extras);
                    }

                    songs.add(new MediaBrowser.MediaItem(song.build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
                }
            }

            Log.i(TAG, "Playlist loaded: " + songs.size() + " songs");
            result.sendResult(songs);

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

    /**
     * Injection point.
     *
     * @return The media id the app should play, or null if it was played here.
     */
    public static synchronized String onPlayFromMediaId(String mediaId, Bundle extras) {
        try {
            if (mediaId == null || extras == null || !LIST_CURRENT.equals(extras.getString(KEY_LIST))) {
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
            playModes = new PlayModes(session, mainHandler, OriginIslandMusicCardPatch::onPlayModesChanged);
        }
    }

    private static void onPlayModesChanged() {
        mainHandler.removeCallbacks(publishLastMetadataTask);
        mainHandler.postDelayed(publishLastMetadataTask, PUBLISH_PLAY_MODES_DELAY_MS);
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
                session.setMetadata(withMusicCardKeys(lastMetadata));
            }
        } catch (Exception ex) {
            Log.e(TAG, "publishLastMetadata failure", ex);
        }
    }

    private static MediaMetadata withMusicCardKeys(MediaMetadata metadata) {
        Rating userRating = metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
        boolean liked = userRating != null && userRating.isThumbUp();

        long supportEvent = EVENT_PLAY_CONTROL | EVENT_MUSIC_INFO | EVENT_MUSIC_IMAGE
                | EVENT_SEEK_POSITION | EVENT_FAVORITE | EVENT_LIST_CURRENT;

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
