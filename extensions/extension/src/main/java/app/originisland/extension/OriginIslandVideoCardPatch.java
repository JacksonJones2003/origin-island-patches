package app.originisland.extension;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.media.MediaBrowserService;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static app.originisland.extension.MusicCardProtocol.*;

/**
 * Makes YouTube speak the protocol of the OriginOS music card (com.vivo.musicwidgetmix).
 *
 * The card reads what it shows from the session metadata and the session extras, and controls
 * the app through the transport controls of the session:
 * <ul>
 *     <li>The heart button is drawn from a heart {@link Rating} in the metadata, and sends
 *     a heart rating. YouTube rates with thumbs instead, and its session does not tell if a video
 *     is liked. That is read from the entity the app keeps it in.</li>
 *     <li>Lyrics are read from the session extras. They are made from the captions of the video,
 *     which are loaded from the address the player response of the video has for them.</li>
 *     <li>The playlist is loaded from the media browser service, and what is tapped in it is
 *     played from its media id. It is what the app plays after the video here, which is read
 *     from the watch next response of the video, and played from its address.</li>
 * </ul>
 * The session does not tell which video plays either, which the patch adds a getter for.
 *
 * The app has nothing to play or to like once a video is closed, and answers the play button
 * with an error. The video that played last is remembered here, with what the card shows of it.
 * It is played again from its address for the play button of the card, and liked by its id.
 */
@SuppressWarnings("unused")
public final class OriginIslandVideoCardPatch {

    /**
     * Implemented by the media session callback of the app, with a method the patch adds to it.
     */
    public interface VideoIdProvider {
        /**
         * @return The id of the video that plays, or null if none does.
         */
        String originIslandGetVideoId();
    }

    private static final String TAG = "OriginIslandVideoCard";

    private static final String WATCH_URL = "https://www.youtube.com/watch";

    /**
     * A video that is played again continues where it was closed, unless that is
     * this close to its start or its end.
     */
    private static final long MIN_RESUME_POSITION_MS = 10000;

    /**
     * For how long the app is told to play a video without opening, after it was asked to play one
     * while it is not in view. The app looks the address of the video up before it plays it.
     */
    private static final long PLAY_WITHOUT_ACTIVITY_TIMEOUT_MS = 20000;

    /**
     * For how long after a heart rating it is logged what the app has the video as.
     */
    private static final long LOG_LIKE_STATUS_AFTER_RATING_MS = 15000;


    // The key of an entity is a message with the type of the entity and its id in that type.
    private static final int FIELD_ENTITY_KEY_ID = 2;
    // LikeStatusEntity
    private static final int FIELD_LIKE_STATUS_KEY = 1;
    private static final int FIELD_LIKE_STATUS = 2;
    private static final int LIKE_STATUS_LIKE = 0;

    /**
     * The card only listens for lyrics once it has connected, which takes a moment.
     */
    private static final long SEND_LYRICS_AFTER_CONNECTING_DELAY_MS = 1500;

    /**
     * The app changes its playback state several times as it starts a video.
     */
    private static final long PUBLISH_LAST_METADATA_DELAY_MS = 150;

    /**
     * Folder in the files of the app on the shared storage. While it exists, the watch next
     * responses of the app are written to it, to find out why what plays next is not read from one.
     */
    private static final String DEBUG_FOLDER = "origin_island_debug";

    private static final int MAX_REMEMBERED_LIKES = 500;
    private static final int MAX_REMEMBERED_VIDEOS = 12;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Runnable publishLastMetadataTask = OriginIslandVideoCardPatch::publishLastMetadata;
    private static final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "OriginIslandVideoCard");
        thread.setDaemon(true);
        return thread;
    });

    private static WeakReference<MediaSession> sessionRef = new WeakReference<>(null);
    private static WeakReference<MediaBrowserService> browserServiceRef = new WeakReference<>(null);
    private static WeakReference<VideoIdProvider> videoIdProviderRef = new WeakReference<>(null);

    /**
     * If videos are liked, by their ids. The app tells of videos it shows as well as the one that plays.
     */
    private static final Map<String, Boolean> likedByVideoId = newCache(MAX_REMEMBERED_LIKES);
    /**
     * The lists of caption tracks the app made for videos, which it can do before they play.
     */
    private static final Map<String, byte[]> captionTracksByVideoId = newCache(MAX_REMEMBERED_VIDEOS);
    private static final Map<String, String> lyricsByVideoId = newCache(MAX_REMEMBERED_VIDEOS);
    private static final Set<String> lyricsLoading = new HashSet<>();
    private static final Map<String, UpNextVideos> upNextByVideoId = newCache(MAX_REMEMBERED_VIDEOS);

    private static Method toByteArrayMethod;
    private static Method likeStatusEntityBytesMethod;

    /**
     * Metadata as last given to the session, before the keys of the card are added.
     */
    private static MediaMetadata lastMetadata;
    /**
     * Id of the video that plays, or of its title and channel if the app does not tell the id.
     */
    private static String videoId = "";
    private static String lyrics = "";

    /**
     * If the video was closed in the app. The card keeps showing it, so it stays
     * the video of everything here until another one plays.
     */
    private static boolean videoClosed;
    private static long videoClosedRealtimeMs;
    /**
     * Metadata and playback state of the video as the app last gave them while it was open.
     */
    private static MediaMetadata videoMetadata;
    private static PlaybackState videoPlaybackState;

    /**
     * Until when the app is to play a video without opening. Zero if it is not.
     */
    private static long playWithoutActivityUntilRealtimeMs;
    private static long ratingRealtimeMs;

    private OriginIslandVideoCardPatch() {
    }

    /**
     * @return A map that forgets what was used the longest ago as it grows past the size.
     */
    private static <K, V> Map<K, V> newCache(int maxSize) {
        return new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxSize;
            }
        };
    }

    /**
     * Injection point.
     *
     * The app answers clients of the media library through a channel of that library.
     * The card is given a root of the platform instead, so that everything it asks
     * the media browser service for goes through the platform.
     *
     * @return The root for the client connecting to the media browser service,
     *         or null if the client is not the music card.
     */
    public static MediaBrowserService.BrowserRoot onGetBrowserRoot(String clientPackageName) {
        if (!MUSIC_CARD_PACKAGE.equals(clientPackageName)) {
            return null;
        }

        Log.i(TAG, "Music card connected");
        mainHandler.postDelayed(OriginIslandVideoCardPatch::sendLyrics, SEND_LYRICS_AFTER_CONNECTING_DELAY_MS);
        mainHandler.postDelayed(publishLastMetadataTask, SEND_LYRICS_AFTER_CONNECTING_DELAY_MS);

        return new MediaBrowserService.BrowserRoot(BROWSER_ROOT_ID, null);
    }

    /**
     * Injection point. Called as the app makes a session, which it can do long before
     * it gives the session metadata.
     */
    public static void onSessionCreated(MediaSession session) {
        // The app sets the session up after it has made it.
        mainHandler.post(() -> {
            synchronized (OriginIslandVideoCardPatch.class) {
                if (sessionRef.get() == null) {
                    sessionRef = new WeakReference<>(session);
                }
            }
        });
    }

    /**
     * Injection point. Called as the media session callback of the app is made.
     */
    public static synchronized void setVideoIdProvider(VideoIdProvider provider) {
        videoIdProviderRef = new WeakReference<>(provider);
    }

    /**
     * Injection point. Called on the main thread with the metadata of the app.
     */
    public static synchronized MediaMetadata onSetMetadata(MediaSession session, MediaMetadata metadata) {
        try {
            if (session != null && sessionRef.get() != session) {
                sessionRef = new WeakReference<>(session);
            }
        } catch (Exception ex) {
            Log.e(TAG, "onSetMetadata failure", ex);
        }

        return onSetExtensionMetadata(session, metadata);
    }

    /**
     * Injection point. Called with the metadata other patches publish in place of the app,
     * which can describe the video differently, such as with its original title.
     */
    public static synchronized MediaMetadata onSetExtensionMetadata(MediaSession session, MediaMetadata metadata) {
        try {
            // Other patches can have a session of their own.
            if (session == sessionRef.get()) {
                lastMetadata = metadata;
                updateVideoId();

                if (videoClosed) {
                    // The app clears its metadata as a video is closed.
                    if (videoMetadata != null) {
                        return withMusicCardKeys(videoMetadata);
                    }
                } else if (hasTitle(metadata)) {
                    videoMetadata = metadata;
                }
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
     * Injection point. Called with the playback state of the app.
     *
     * The app can publish the metadata of a video before it tells that the video plays.
     *
     * @return The playback state to give the session.
     */
    public static synchronized PlaybackState onSetPlaybackState(MediaSession session, PlaybackState state) {
        try {
            if (session != sessionRef.get()) {
                return state;
            }

            if (updateVideoId()) {
                publishLastMetadataSoon();
            }

            boolean hasPosition = false;
            if (state != null) {
                switch (state.getState()) {
                    case PlaybackState.STATE_PLAYING:
                    case PlaybackState.STATE_PAUSED:
                    case PlaybackState.STATE_BUFFERING:
                        hasPosition = true;
                }
            }

            if (videoClosed) {
                // The app has no state for a video that is closed, or one that says it cannot play.
                // It can publish the state of a video before it tells that the video plays.
                PlaybackState closedState = hasPosition ? null : closedPlaybackState();
                return closedState == null ? state : closedState;
            }
            if (hasPosition) {
                videoPlaybackState = state;
            }
        } catch (Exception ex) {
            Log.e(TAG, "onSetPlaybackState failure", ex);
        }

        return state;
    }

    /**
     * Injection point. Called as the session of the app is asked to play.
     *
     * @return If the video that was closed is played again here, and the app should not be asked to play.
     */
    public static synchronized boolean onPlay() {
        try {
            MediaSession session = sessionRef.get();
            updateVideoId();
            if (!videoClosed || session == null || !isVideoId(videoId)) {
                return false;
            }

            // The play button of a headset has the app play as it does without this patch.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P
                    || !MUSIC_CARD_PACKAGE.equals(session.getCurrentControllerInfo().getPackageName())) {
                return false;
            }

            Uri.Builder address = watchAddress(videoId, playlistIdOf(videoId));
            long positionMs = resumePositionMs();
            if (positionMs > 0) {
                address.appendQueryParameter("t", positionMs / 1000 + "s");
            }

            Log.i(TAG, "Playing the closed video again: " + videoId + " from " + positionMs / 1000 + " s");
            playFromAddress(session, address);

            return true;
        } catch (Exception ex) {
            Log.e(TAG, "onPlay failure", ex);
        }

        return false;
    }

    /**
     * Injection point. Called as the app is about to play a video from its address.
     *
     * @param withoutActivity If the app plays the video without opening, which it does
     *                        while it plays in the background or on another device.
     * @return If the app should play the video without opening.
     */
    public static synchronized boolean playWithoutActivity(boolean withoutActivity) {
        try {
            if (!withoutActivity && playWithoutActivityUntilRealtimeMs > SystemClock.elapsedRealtime()) {
                Log.i(TAG, "Playing without opening the app");
                playWithoutActivityUntilRealtimeMs = 0;
                return true;
            }
        } catch (Exception ex) {
            Log.e(TAG, "playWithoutActivity failure", ex);
        }

        return withoutActivity;
    }

    /**
     * Injection point. Called as the app is about to rate the video that plays.
     *
     * @param id The id of the video that plays, or null if none does.
     * @return The id of the video to rate.
     */
    public static synchronized String getVideoIdToRate(String id) {
        try {
            if ((id == null || id.isEmpty()) && videoClosed && isVideoId(videoId)) {
                Log.i(TAG, "Rating the closed video: " + videoId);
                return videoId;
            }
        } catch (Exception ex) {
            Log.e(TAG, "getVideoIdToRate failure", ex);
        }

        return id;
    }

    /**
     * Injection point.
     *
     * The app likes the video for a thumb up, removes the like for an unrated thumb,
     * and dislikes the video for any other rating, which includes every heart rating.
     */
    public static Rating onSetRating(Rating rating) {
        try {
            if (rating == null || rating.getRatingStyle() != Rating.RATING_HEART) {
                return rating;
            }

            boolean like = rating.hasHeart();
            Log.i(TAG, "Heart rating received: " + like);

            // Shown right away. The app tells if it turns out differently.
            synchronized (OriginIslandVideoCardPatch.class) {
                updateVideoId();
                ratingRealtimeMs = SystemClock.elapsedRealtime();
                if (!videoId.isEmpty()) {
                    likedByVideoId.put(videoId, like);
                    publishLastMetadataSoon();
                }
            }

            return like
                    ? Rating.newThumbRating(true)
                    : Rating.newUnratedRating(Rating.RATING_THUMB_UP_DOWN);
        } catch (Exception ex) {
            Log.e(TAG, "onSetRating failure", ex);
        }

        return rating;
    }

    /**
     * Injection point. Called on any thread with the model of an entity that tells if a video
     * is liked, as the app reads the entity or changes it.
     */
    public static void onLikeStatusEntity(Object entity) {
        try {
            Method bytesMethod = likeStatusEntityBytesMethod;
            if (bytesMethod == null) {
                // The model has a single getter of its entity as bytes.
                for (Method method : entity.getClass().getDeclaredMethods()) {
                    if (method.getReturnType() == byte[].class && method.getParameterTypes().length == 0) {
                        method.setAccessible(true);
                        likeStatusEntityBytesMethod = bytesMethod = method;
                        break;
                    }
                }
                if (bytesMethod == null) {
                    return;
                }
            }

            ProtoReader likeStatus = new ProtoReader((byte[]) bytesMethod.invoke(entity));
            String key = likeStatus.string(FIELD_LIKE_STATUS_KEY);
            // An entity that is not set up yet has no status.
            long status = likeStatus.number(FIELD_LIKE_STATUS, -1);
            if (key == null || status < 0) {
                return;
            }

            String id = new ProtoReader(Base64.decode(URLDecoder.decode(key, "UTF-8"), Base64.URL_SAFE))
                    .string(FIELD_ENTITY_KEY_ID);
            if (id == null || id.isEmpty()) {
                return;
            }

            boolean liked = status == LIKE_STATUS_LIKE;
            synchronized (OriginIslandVideoCardPatch.class) {
                Boolean wasLiked = likedByVideoId.put(id, liked);
                if (!id.equals(videoId)) {
                    return;
                }

                boolean changed = !Objects.equals(wasLiked, liked);
                if (changed || SystemClock.elapsedRealtime() - ratingRealtimeMs < LOG_LIKE_STATUS_AFTER_RATING_MS) {
                    Log.i(TAG, "The app has the video as liked: " + liked);
                }
                if (changed) {
                    publishLastMetadataSoon();
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "onLikeStatusEntity failure", ex);
        }
    }

    /**
     * Injection point. Called on any thread as the app makes the list of the caption tracks
     * of a video, which it does for the video that plays and for videos it is about to play.
     *
     * @param trackList A PlayerCaptionsTracklistRenderer.
     */
    public static void onCaptionTracks(String id, boolean forOffline, Object trackList) {
        try {
            if (id == null || id.isEmpty() || trackList == null) {
                return;
            }

            byte[] bytes = toByteArray(trackList);
            synchronized (OriginIslandVideoCardPatch.class) {
                captionTracksByVideoId.put(id, bytes);
                if (id.equals(videoId)) {
                    loadLyrics();
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "onCaptionTracks failure", ex);
        }
    }

    /**
     * Injection point. Called on any thread as the app makes the model of a watch next response.
     */
    public static void onWatchNextResponse(Object response) {
        try {
            if (response == null) {
                return;
            }

            // Not read on another thread, since the app may be about to change the response.
            byte[] bytes = toByteArray(response);
            backgroundExecutor.execute(() -> {
                try {
                    UpNextVideos upNext = UpNextVideos.parse(bytes);
                    writeDebugFile("watch_next_" + (upNext == null ? "unknown" : upNext.videoId) + ".bin", bytes);
                    if (upNext == null) {
                        return;
                    }
                    Log.i(TAG, "Up next loaded: " + upNext.videos.size() + " videos");

                    synchronized (OriginIslandVideoCardPatch.class) {
                        upNextByVideoId.put(upNext.videoId, upNext);
                        if (upNext.videoId.equals(videoId)) {
                            mainHandler.post(OriginIslandVideoCardPatch::notifyUpNextChanged);
                        }
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Could not read what plays next", ex);
                }
            });
        } catch (Exception ex) {
            Log.e(TAG, "onWatchNextResponse failure", ex);
        }
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

            // The list is a single page.
            int page = options == null ? 0 : options.getInt(LIST_KEY_PAGE);
            List<MediaDescription> videos = new ArrayList<>();
            UpNextVideos upNext = page == 0 ? upNextByVideoId.get(videoId) : null;
            if (upNext != null) {
                for (UpNextVideos.Video video : upNext.videos) {
                    videos.add(new MediaDescription.Builder()
                            .setMediaId(video.id)
                            .setTitle(video.title)
                            .setSubtitle(video.channel)
                            .build());
                }
            }

            Log.i(TAG, "Playlist loaded: " + videos.size() + " videos");
            if (videos.isEmpty() && page == 0) {
                videos.add(new MediaDescription.Builder()
                        .setMediaId(EMPTY_LIST_MEDIA_ID)
                        .setTitle("Nothing up next")
                        .setSubtitle("What plays after a video shows here")
                        .build());
            }
            result.sendResult(toSinglePage(videos));

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
            if (EMPTY_LIST_MEDIA_ID.equals(mediaId)) {
                return null;
            }

            UpNextVideos upNext = upNextByVideoId.get(videoId);
            UpNextVideos.Video video = upNext == null || mediaId == null ? null : upNext.find(mediaId);
            MediaSession session = sessionRef.get();
            if (video == null || session == null) {
                return mediaId;
            }

            Log.i(TAG, "Playing from the playlist: " + video.id);
            playFromAddress(session, watchAddress(video.id, video.playlistId));

            return null;
        } catch (Exception ex) {
            Log.e(TAG, "onPlayFromMediaId failure", ex);
        }

        return mediaId;
    }

    private static Uri.Builder watchAddress(String id, String playlistId) {
        Uri.Builder address = Uri.parse(WATCH_URL).buildUpon().appendQueryParameter("v", id);
        if (playlistId != null) {
            address.appendQueryParameter("list", playlistId);
        }

        return address;
    }

    private static void playFromAddress(MediaSession session, Uri.Builder address) {
        // The app opens to play a video unless it plays in the background already, and Android
        // does not let an app open that is not in view. The video plays without the app as well.
        if (!isAppShown()) {
            playWithoutActivityUntilRealtimeMs = SystemClock.elapsedRealtime() + PLAY_WITHOUT_ACTIVITY_TIMEOUT_MS;
        }

        // The app plays the address as it does for a voice assistant, and reads the extras.
        session.getController().getTransportControls().playFromUri(address.build(), new Bundle());
    }

    /**
     * @return The playlist the video is played as a part of, or null if it is played by itself.
     */
    private static String playlistIdOf(String id) {
        UpNextVideos upNext = upNextByVideoId.get(id);
        UpNextVideos.Video video = upNext == null ? null : upNext.find(id);
        return video == null ? null : video.playlistId;
    }

    /**
     * @return Where the video was as it was closed.
     */
    private static long closedPositionMs() {
        PlaybackState state = videoPlaybackState;
        if (state == null) {
            return 0;
        }

        long positionMs = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            positionMs += (long) ((videoClosedRealtimeMs - state.getLastPositionUpdateTime())
                    * state.getPlaybackSpeed());
        }

        long durationMs = videoMetadata == null ? 0 : videoMetadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        return Math.max(0, durationMs > 0 ? Math.min(positionMs, durationMs) : positionMs);
    }

    /**
     * @return Where the video is to be played again from, or zero if that is its start.
     */
    private static long resumePositionMs() {
        long positionMs = closedPositionMs();
        long durationMs = videoMetadata == null ? 0 : videoMetadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        boolean nearEnd = durationMs > 0 && positionMs > durationMs - MIN_RESUME_POSITION_MS;
        return positionMs < MIN_RESUME_POSITION_MS || nearEnd ? 0 : positionMs;
    }

    /**
     * @return The playback state of the video that was closed, paused where it was closed,
     *         or null if the video had none.
     */
    private static PlaybackState closedPlaybackState() {
        PlaybackState state = videoPlaybackState;
        if (state == null || !isVideoId(videoId)) {
            return null;
        }

        return new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, closedPositionMs(), 1.0f)
                .setActions(state.getActions() | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)
                .build();
    }

    private static synchronized void publishClosedPlaybackState() {
        try {
            MediaSession session = sessionRef.get();
            PlaybackState closedState = videoClosed ? closedPlaybackState() : null;
            if (session != null && closedState != null) {
                session.setPlaybackState(closedState);
            }
        } catch (Exception ex) {
            Log.e(TAG, "publishClosedPlaybackState failure", ex);
        }
    }

    private static boolean hasTitle(MediaMetadata metadata) {
        String title = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        return title != null && !title.isEmpty();
    }

    /**
     * @return If the id is one of a video, and not one made here from a title.
     */
    private static boolean isVideoId(String id) {
        return id.length() == 11;
    }

    private static Application getApplication() throws Exception {
        // Hidden, but not restricted.
        return (Application) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null);
    }

    /**
     * @return If an activity of the app is in view.
     */
    private static boolean isAppShown() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityManager manager = getApplication().getSystemService(ActivityManager.class);
                for (ActivityManager.AppTask task : manager.getAppTasks()) {
                    if (task.getTaskInfo().isVisible()) {
                        return true;
                    }
                }

                return false;
            }
        } catch (Exception ex) {
            Log.e(TAG, "isAppShown failure", ex);
        }

        // Also the case while an app that is in view is connected to this one, as the card can be.
        ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(process);
        return process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    private static void writeDebugFile(String name, byte[] bytes) {
        try {
            Context context = getApplication();
            File filesFolder = context == null ? null : context.getExternalFilesDir(null);
            File folder = filesFolder == null ? null : new File(filesFolder, DEBUG_FOLDER);
            if (folder == null || !folder.isDirectory()) {
                return;
            }

            try (FileOutputStream stream = new FileOutputStream(new File(folder, name))) {
                stream.write(bytes);
            }
            Log.i(TAG, "Wrote " + name);
        } catch (Exception ex) {
            Log.e(TAG, "writeDebugFile failure", ex);
        }
    }

    /**
     * @return The bytes of a message of the app.
     */
    private static byte[] toByteArray(Object message) throws Exception {
        Method method = toByteArrayMethod;
        if (method == null) {
            // A method of the protocol buffers library every message has, which keeps its name.
            toByteArrayMethod = method = message.getClass().getMethod("toByteArray");
        }

        return (byte[]) method.invoke(message);
    }

    /**
     * @return If another video plays than before.
     */
    private static boolean updateVideoId() {
        String id = null;
        try {
            VideoIdProvider provider = videoIdProviderRef.get();
            if (provider != null) {
                id = provider.originIslandGetVideoId();
            }
        } catch (Exception ex) {
            // The app has nothing to read the id from while nothing plays.
        }

        if (id == null || id.isEmpty()) {
            MediaMetadata metadata = lastMetadata;
            if (!hasTitle(metadata)) {
                // Nothing plays. The card keeps showing the video that played last.
                if (!videoClosed && isVideoId(videoId)) {
                    videoClosed = true;
                    videoClosedRealtimeMs = SystemClock.elapsedRealtime();
                    Log.i(TAG, "Video closed: " + videoId);
                    mainHandler.post(OriginIslandVideoCardPatch::publishClosedPlaybackState);
                }
                return false;
            }

            // The card ignores a change of what plays unless the media id changes as well.
            id = Integer.toHexString(Objects.hash(
                    metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ));
        }

        boolean wasClosed = videoClosed;
        videoClosed = false;
        if (id.equals(videoId)) {
            // The card was told of the video as it was closed, and not as it plays again.
            return wasClosed;
        }
        videoId = id;
        videoPlaybackState = null;
        Log.i(TAG, "Video changed: " + id);

        String knownLyrics = lyricsByVideoId.get(id);
        lyrics = knownLyrics == null ? "" : knownLyrics;
        if (knownLyrics == null) {
            loadLyrics();
        }
        // The app may still be about to publish the video these lyrics are for.
        mainHandler.post(OriginIslandVideoCardPatch::sendLyrics);
        mainHandler.post(OriginIslandVideoCardPatch::notifyUpNextChanged);

        return true;
    }

    /**
     * Loads the lyrics of the video that plays, if the app has made the list of its caption tracks.
     */
    private static void loadLyrics() {
        String id = videoId;
        byte[] trackList = captionTracksByVideoId.get(id);
        if (trackList == null || lyricsByVideoId.containsKey(id) || !lyricsLoading.add(id)) {
            return;
        }

        backgroundExecutor.execute(() -> {
            String lrc = null;
            try {
                VideoCaptions.Track track = VideoCaptions.chooseTrack(trackList);
                lrc = track == null ? "" : VideoCaptions.loadLrc(track);
                Log.i(TAG, "Captions loaded: " + lrc.length() + " characters"
                        + (track == null ? "" : " of " + track.languageCode));
            } catch (Exception ex) {
                Log.e(TAG, "Could not load the captions", ex);
            }

            synchronized (OriginIslandVideoCardPatch.class) {
                lyricsLoading.remove(id);
                // Captions that failed to load are loaded again the next time the video plays.
                if (lrc == null) {
                    return;
                }

                lyricsByVideoId.put(id, lrc);
                if (id.equals(videoId)) {
                    lyrics = lrc;
                    mainHandler.post(OriginIslandVideoCardPatch::sendLyrics);
                }
            }
        });
    }

    private static synchronized void notifyUpNextChanged() {
        try {
            MediaBrowserService browserService = browserServiceRef.get();
            if (browserService != null) {
                browserService.notifyChildrenChanged(LIST_CURRENT);
            }
        } catch (Exception ex) {
            Log.e(TAG, "notifyUpNextChanged failure", ex);
        }
    }

    private static synchronized void sendLyrics() {
        try {
            MediaSession session = sessionRef.get();
            if (session == null) {
                return;
            }

            // The app keeps extras of its own on the session.
            Bundle appExtras = session.getController().getExtras();
            Bundle extras = appExtras == null ? new Bundle() : new Bundle(appExtras);
            extras.putString(EXTRA_ACTION, EXTRA_ACTION_LYRICS_CHANGED);
            extras.putString(EXTRA_MEDIA_ID, videoId);
            extras.putString(EXTRA_LYRICS, lyrics);
            session.setExtras(extras);

            Log.i(TAG, "Lyrics sent: " + lyrics.length() + " characters");
        } catch (Exception ex) {
            Log.e(TAG, "sendLyrics failure", ex);
        }
    }

    private static void publishLastMetadataSoon() {
        mainHandler.removeCallbacks(publishLastMetadataTask);
        mainHandler.postDelayed(publishLastMetadataTask, PUBLISH_LAST_METADATA_DELAY_MS);
    }

    /**
     * The card only reads if the video is liked and what it may control when the metadata changes.
     */
    private static synchronized void publishLastMetadata() {
        try {
            MediaSession session = sessionRef.get();
            MediaMetadata metadata = videoClosed && videoMetadata != null ? videoMetadata : lastMetadata;
            if (session != null && metadata != null) {
                session.setMetadata(withMusicCardKeys(metadata));
            }
        } catch (Exception ex) {
            Log.e(TAG, "publishLastMetadata failure", ex);
        }
    }

    private static MediaMetadata withMusicCardKeys(MediaMetadata metadata) {
        boolean liked = Boolean.TRUE.equals(likedByVideoId.get(videoId));

        return new MediaMetadata.Builder(metadata)
                .putRating(MediaMetadata.METADATA_KEY_RATING, Rating.newHeartRating(liked))
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, videoId)
                .putLong(KEY_SUPPORT_EVENT, EVENT_PLAY_CONTROL | EVENT_MUSIC_INFO | EVENT_MUSIC_IMAGE
                        | EVENT_MUSIC_LRC | EVENT_SEEK_POSITION | EVENT_FAVORITE | EVENT_LIST_CURRENT)
                .build();
    }
}
