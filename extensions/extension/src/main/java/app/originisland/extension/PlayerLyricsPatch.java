package app.originisland.extension;

import android.app.Activity;
import android.content.res.Resources;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows the line of the lyrics that is sung in the player, below the title of the queue
 * at the bottom of it.
 *
 * The lyrics are those of the "Third-party lyrics" patch of Morphe, which has them with
 * the time of each line. Without it, or without lyrics for a song, nothing is shown.
 */
@SuppressWarnings("unused")
public final class PlayerLyricsPatch {

    private static final String TAG = "OriginIslandPlayerLyrics";

    /**
     * View at the bottom of the player. While the queue is not pulled up, it shows a strip
     * with the title of the queue, and otherwise a header and the queue in place of the strip.
     */
    private static final String QUEUE_SHEET_ID = "bottom_sheet_tabbed_view";
    private static final String QUEUE_TITLE_ID = "player_page_tab_title";

    /**
     * Package of the resources of the app, which patches can install under another package.
     */
    private static final String APP_RESOURCES_PACKAGE = "com.google.android.apps.youtube.music";

    private static final Object VIEW_TAG = new Object();

    private static final long UPDATE_INTERVAL_MS = 100;
    private static final long UPDATE_INTERVAL_HIDDEN_MS = 1_000;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Runnable updateTask = PlayerLyricsPatch::update;

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static WeakReference<LyricLineView> viewRef = new WeakReference<>(null);
    /**
     * Strip with the title of the queue, which the line is below.
     */
    private static WeakReference<View> stripRef = new WeakReference<>(null);
    /**
     * Playback state of each session of the app. The app makes more than the one it plays with.
     */
    private static final List<PlaybackState[]> playbackStates = new ArrayList<>();
    private static final List<MediaController> controllers = new ArrayList<>();
    /**
     * Who wrote the lyrics or where they are from, and the line shown before the first one
     * is sung, which names the artist and gives that credit.
     */
    private static String credit = "";
    private static MorpheLyrics.Line creditLine;
    private static Boolean lyricsSupported;
    private static List<MorpheLyrics.Line> lines = new ArrayList<>();
    private static int queueSheetId;
    private static int queueTitleId;

    private PlayerLyricsPatch() {
    }

    /**
     * Injection point. Called as the app makes a session.
     */
    public static void onSessionCreated(MediaSession session) {
        // The app sets the session up after it has made it.
        mainHandler.post(() -> {
            try {
                MediaController controller = session.getController();
                PlaybackState[] playbackState = {controller.getPlaybackState()};
                playbackStates.add(playbackState);
                controllers.add(controller);
                controller.registerCallback(new MediaController.Callback() {
                    @Override
                    public void onPlaybackStateChanged(PlaybackState state) {
                        playbackState[0] = state;
                    }

                    @Override
                    public void onSessionDestroyed() {
                        playbackStates.remove(playbackState);
                        controllers.remove(controller);
                    }
                }, mainHandler);
                Log.i(TAG, "Session " + playbackStates.size() + " of the app is followed");
            } catch (Exception ex) {
                Log.e(TAG, "onSessionCreated failure", ex);
            }
        });
    }

    /**
     * Injection point. Called as the main activity of the app comes to the front.
     */
    public static void onActivityResume(Activity activity) {
        try {
            activityRef = new WeakReference<>(activity);

            if (lyricsSupported == null) {
                lyricsSupported = MorpheLyrics.addLinesListener(PlayerLyricsPatch::onLyricsChanged);
            }
            if (lyricsSupported) {
                mainHandler.removeCallbacks(updateTask);
                mainHandler.post(updateTask);
            }
        } catch (Exception ex) {
            Log.e(TAG, "onActivityResume failure", ex);
        }
    }

    private static void onLyricsChanged(List<MorpheLyrics.Line> changed, String changedCredit) {
        lines = changed;
        credit = changedCredit;
        creditLine = null;

        int withWords = 0;
        for (MorpheLyrics.Line line : changed) {
            if (!line.words.isEmpty()) {
                withWords++;
            }
        }
        Log.i(TAG, "Lyrics changed: " + changed.size() + " lines, " + withWords + " with times for their words");
    }

    private static void update() {
        boolean shown = false;
        try {
            Activity activity = activityRef.get();
            if (activity == null || activity.isDestroyed()) {
                return;
            }

            LyricLineView view = getView(activity);
            if (view != null) {
                MorpheLyrics.Line line = getCurrentLine();
                // The line is only shown with the title of the queue, and not with the queue.
                // It keeps its place without a line, so that the player is not laid out again
                // for every line, which would start the title of the queue over if it scrolls.
                View strip = stripRef.get();
                boolean visible = strip != null && strip.isShown();

                int visibility = visible ? View.VISIBLE : View.GONE;
                if (view.getVisibility() != visibility) {
                    view.setVisibility(visibility);
                }
                if (visible) {
                    view.setLine(line);

                    // The app keeps the header of the queue below the strip, unseen until the
                    // queue is pulled up, so the line is drawn over it and not after it.
                    // In the middle of what is seen below the title: from under its text,
                    // which the strip has some room below, to the bottom of the screen.
                    int[] location = new int[2];
                    strip.getLocationOnScreen(location);
                    float bandTop = strip.getBottom() - strip.getHeight() * 0.3f;
                    float bandHeight = view.getRootView().getHeight()
                            - (location[1] + strip.getHeight() * 0.7f);
                    float translation = bandTop - view.getTop()
                            + Math.max(0, (bandHeight - view.getTextHeight()) / 2);
                    if (view.getTranslationY() != translation) {
                        view.setTranslationY(translation);
                    }
                }
                visible = visible && line != null;
                shown = visible && view.isShown();
            }
        } catch (Exception ex) {
            Log.e(TAG, "update failure", ex);
        } finally {
            Activity activity = activityRef.get();
            if (activity != null && !activity.isDestroyed()) {
                mainHandler.postDelayed(updateTask, shown ? UPDATE_INTERVAL_MS : UPDATE_INTERVAL_HIDDEN_MS);
            }
        }
    }

    /**
     * @return The line that is sung, or null if there is none.
     */
    private static MorpheLyrics.Line getCurrentLine() {
        long position = getPosition();
        if (position < 0) {
            return null;
        }

        MorpheLyrics.Line current = null;
        for (MorpheLyrics.Line line : lines) {
            if (line.startTimeMs > position) {
                break;
            }
            current = line;
        }

        // Until the first line is sung, the artist and the lyrics are given credit.
        if (current == null && !lines.isEmpty()) {
            return getCreditLine();
        }

        return current;
    }

    private static MorpheLyrics.Line getCreditLine() {
        String artist = "";
        try {
            for (MediaController controller : controllers) {
                MediaMetadata metadata = controller.getMetadata();
                String metadataArtist = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (metadataArtist != null && !metadataArtist.trim().isEmpty()) {
                    artist = metadataArtist.trim();
                    break;
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "getCreditLine failure", ex);
        }

        String text = artist.isEmpty() || credit.isEmpty() ? artist + credit : artist + "\n" + credit;
        if (text.isEmpty()) {
            return null;
        }
        // The same line is kept while it is shown, so that it only comes in once.
        if (creditLine == null || !creditLine.text.equals(text)) {
            creditLine = new MorpheLyrics.Line(0, text, new ArrayList<>());
        }

        return creditLine;
    }

    /**
     * @return The position the song is at in milliseconds, or -1 if there is none.
     */
    private static long getPosition() {
        // The session the app plays with is the one that was last given a position.
        PlaybackState state = null;
        for (PlaybackState[] playbackState : playbackStates) {
            if (playbackState[0] != null && (state == null
                    || playbackState[0].getLastPositionUpdateTime() > state.getLastPositionUpdateTime())) {
                state = playbackState[0];
            }
        }
        if (state == null) {
            return -1;
        }

        long position = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime())
                    * state.getPlaybackSpeed());
        }

        return position;
    }

    private static View findShownView(View view, int id) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (view.getId() == id) {
            return view;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findShownView(group.getChildAt(i), id);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private static int getId(Activity activity, String name) {
        Resources resources = activity.getResources();
        for (String resourcesPackage : new String[]{activity.getPackageName(), APP_RESOURCES_PACKAGE}) {
            int id = resources.getIdentifier(name, "id", resourcesPackage);
            if (id != 0) {
                return id;
            }
        }

        return 0;
    }

    /**
     * @return The view of the line in the player, or null if the player is not made yet.
     */
    private static LyricLineView getView(Activity activity) {
        LyricLineView view = viewRef.get();
        if (view != null && view.isAttachedToWindow() && view.getContext() == activity) {
            return view;
        }

        if (queueSheetId == 0 || queueTitleId == 0) {
            queueSheetId = getId(activity, QUEUE_SHEET_ID);
            queueTitleId = getId(activity, QUEUE_TITLE_ID);
            if (queueSheetId == 0 || queueTitleId == 0) {
                return null;
            }
        }

        // The strip is the view of the sheet that the title of the queue is in.
        View strip = findShownView(activity.getWindow().getDecorView(), queueTitleId);
        while (strip != null && !(strip.getParent() instanceof LinearLayout
                && ((View) strip.getParent()).getId() == queueSheetId)) {
            strip = strip.getParent() instanceof View ? (View) strip.getParent() : null;
        }
        if (strip == null) {
            return null;
        }
        LinearLayout sheet = (LinearLayout) strip.getParent();

        for (int i = sheet.getChildCount() - 1; i >= 0; i--) {
            if (sheet.getChildAt(i).getTag() == VIEW_TAG) {
                sheet.removeViewAt(i);
            }
        }

        view = new LyricLineView(activity, PlayerLyricsPatch::getPosition);
        view.setTag(VIEW_TAG);
        view.setVisibility(View.GONE);

        int index = sheet.indexOfChild(strip) + 1;
        sheet.addView(view, index, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        viewRef = new WeakReference<>(view);
        stripRef = new WeakReference<>(strip);
        Log.i(TAG, "Line added to the player, as view " + index + " of " + sheet.getChildCount());

        return view;
    }
}
