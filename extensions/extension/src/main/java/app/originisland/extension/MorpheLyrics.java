package app.originisland.extension;

import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;

/**
 * Reads the lyrics of the current track from the "Third-party lyrics" patch of Morphe.
 *
 * That patch is part of another patch bundle and may not be applied, or may change,
 * so it is only reached through reflection and everything here fails quietly.
 */
final class MorpheLyrics {

    interface Listener {
        /**
         * Called on the main thread.
         *
         * @param lrc Synced lyrics of the current track in the LRC format,
         *            or an empty string if there are none.
         */
        void onLyricsChanged(String lrc);
    }

    private static final String TAG = "OriginIslandMusicCard";

    private static final String LYRICS_PACKAGE = "app.morphe.extension.music.patches.lyrics.";

    private static Method lyricsSynced;
    private static Method lyricsLines;
    private static Method lineStartTimeMs;
    private static Method lineText;

    private MorpheLyrics() {
    }

    /**
     * Must be called on the main thread.
     *
     * @return If the lyrics patch is applied and the listener was added to it.
     */
    static boolean addListener(Listener listener) {
        try {
            Class<?> managerClass = Class.forName(LYRICS_PACKAGE + "LyricsManager");
            Class<?> listenerClass = Class.forName(LYRICS_PACKAGE + "LyricsManager$Listener");
            Class<?> lyricsClass = Class.forName(LYRICS_PACKAGE + "model.Lyrics");
            Class<?> lineClass = Class.forName(LYRICS_PACKAGE + "model.LyricsLine");

            lyricsSynced = lyricsClass.getMethod("synced");
            lyricsLines = lyricsClass.getMethod("lines");
            lineStartTimeMs = lineClass.getMethod("startTimeMs");
            lineText = lineClass.getMethod("text");

            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "onLyricsChanged":
                        // onLyricsChanged(State state, Lyrics lyrics)
                        listener.onLyricsChanged(toLrc(args[1]));
                        return null;
                    // The manager keeps its listeners in a list.
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        return null;
                }
            };

            Object manager = managerClass.getMethod("getInstance").invoke(null);
            managerClass.getMethod("addListener", listenerClass).invoke(manager,
                    Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[]{listenerClass}, handler));

            return true;
        } catch (ClassNotFoundException ex) {
            Log.i(TAG, "Third-party lyrics patch is not applied");
        } catch (Exception ex) {
            Log.e(TAG, "Could not listen to the third-party lyrics patch", ex);
        }

        return false;
    }

    private static String toLrc(Object lyrics) {
        try {
            if (lyrics == null || !((boolean) lyricsSynced.invoke(lyrics))) {
                return "";
            }

            StringBuilder lrc = new StringBuilder();
            for (Object line : (List<?>) lyricsLines.invoke(lyrics)) {
                long startTimeMs = (long) lineStartTimeMs.invoke(line);
                String text = (String) lineText.invoke(line);
                if (startTimeMs < 0 || text == null || text.trim().isEmpty()) {
                    continue;
                }

                lrc.append(String.format(Locale.US, "[%02d:%02d.%02d]",
                        startTimeMs / 60000, (startTimeMs / 1000) % 60, (startTimeMs % 1000) / 10));
                lrc.append(text.replace('\n', ' ')).append('\n');
            }

            return lrc.toString();
        } catch (Exception ex) {
            Log.e(TAG, "Could not read the lyrics", ex);
        }

        return "";
    }
}
