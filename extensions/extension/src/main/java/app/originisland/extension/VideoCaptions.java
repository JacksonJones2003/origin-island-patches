package app.originisland.extension;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads the captions of a video as lyrics for the music card.
 *
 * The player response of a video lists its caption tracks, each with an address of the servers
 * of the app the captions are loaded from. The app loads them the same way to show them.
 */
final class VideoCaptions {

    static final class Track {
        final String url;
        final String languageCode;
        /**
         * If the captions were made by speech recognition.
         */
        final boolean generated;

        Track(String url, String languageCode, boolean generated) {
            this.url = url;
            this.languageCode = languageCode;
            this.generated = generated;
        }
    }

    // PlayerCaptionsTracklistRenderer
    private static final int FIELD_CAPTION_TRACKS = 1;
    private static final int FIELD_AUDIO_TRACKS = 2;
    private static final int FIELD_DEFAULT_AUDIO_TRACK_INDEX = 4;
    // CaptionTrack
    private static final int FIELD_TRACK_BASE_URL = 1;
    private static final int FIELD_TRACK_LANGUAGE_CODE = 4;
    private static final int FIELD_TRACK_KIND = 5;
    // AudioTrack
    private static final int FIELD_AUDIO_DEFAULT_CAPTION_TRACK_INDEX = 3;
    private static final int FIELD_AUDIO_HAS_DEFAULT_TRACK = 6;

    private static final String KIND_SPEECH_RECOGNITION = "asr";

    /**
     * Format of the captions that has a start time and the text of every line.
     */
    private static final String FORMAT_PARAMETER = "fmt=json3";

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    /**
     * The card reads the minutes of a line from at most two digits.
     */
    private static final long MAX_LINE_START_MS = 100 * 60 * 1000 - 1;

    /**
     * Lyrics are sent to the card in the extras of the session, which have to stay well below
     * what a call between apps can carry.
     */
    private static final int MAX_LYRICS_LENGTH = 100000;

    private VideoCaptions() {
    }

    /**
     * @param trackList A PlayerCaptionsTracklistRenderer.
     * @return The track to show as the lyrics of the video, or null if the video has none.
     */
    static Track chooseTrack(byte[] trackList) {
        ProtoReader renderer = new ProtoReader(trackList);

        List<Track> tracks = new ArrayList<>();
        for (ProtoReader track : renderer.messages(FIELD_CAPTION_TRACKS)) {
            String url = track.string(FIELD_TRACK_BASE_URL);
            String languageCode = track.string(FIELD_TRACK_LANGUAGE_CODE);
            // Kept in place if it cannot be loaded, since the default track is given as an index.
            tracks.add(new Track(
                    url != null && url.startsWith("https://") ? url : null,
                    languageCode == null ? "" : languageCode,
                    KIND_SPEECH_RECOGNITION.equals(track.string(FIELD_TRACK_KIND))
            ));
        }

        String language = Locale.getDefault().getLanguage();
        Track generatedInLanguage = null;
        Track generated = null;
        Track first = null;
        for (Track track : tracks) {
            if (track.url == null) {
                continue;
            }

            boolean inLanguage = !language.isEmpty() && language.equals(languageOf(track.languageCode));
            if (inLanguage && !track.generated) {
                return track;
            }
            if (inLanguage && generatedInLanguage == null) {
                generatedInLanguage = track;
            }
            if (track.generated && generated == null) {
                generated = track;
            }
            if (first == null) {
                first = track;
            }
        }
        if (generatedInLanguage != null) {
            return generatedInLanguage;
        }

        // The track the app would show if captions are turned on.
        List<ProtoReader> audioTracks = renderer.messages(FIELD_AUDIO_TRACKS);
        long audioTrackIndex = renderer.number(FIELD_DEFAULT_AUDIO_TRACK_INDEX, 0);
        if (audioTrackIndex >= 0 && audioTrackIndex < audioTracks.size()) {
            ProtoReader audioTrack = audioTracks.get((int) audioTrackIndex);
            long trackIndex = audioTrack.number(FIELD_AUDIO_DEFAULT_CAPTION_TRACK_INDEX, -1);
            if (audioTrack.number(FIELD_AUDIO_HAS_DEFAULT_TRACK, 0) != 0
                    && trackIndex >= 0 && trackIndex < tracks.size()
                    && tracks.get((int) trackIndex).url != null) {
                return tracks.get((int) trackIndex);
            }
        }

        // Speech recognition is of the language that is spoken.
        return generated != null ? generated : first;
    }

    private static String languageOf(String languageCode) {
        int end = languageCode.indexOf('-');
        return (end < 0 ? languageCode : languageCode.substring(0, end)).toLowerCase(Locale.ROOT);
    }

    /**
     * Must not be called on the main thread.
     *
     * @return The captions of the track in the LRC format, which is empty if the track has no lines.
     */
    static String loadLrc(Track track) throws Exception {
        // The address asks for the format the app shows captions from.
        String url = track.url.replaceAll("&fmt=[^&]*", "");
        url += (url.contains("?") ? "&" : "?") + FORMAT_PARAMETER;

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Captions were answered with status " + status);
            }

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream stream = connection.getInputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = stream.read(buffer)) > 0) {
                    body.write(buffer, 0, read);
                    if (body.size() > MAX_RESPONSE_BYTES) {
                        throw new IOException("Captions are too long");
                    }
                }
            }

            return toLrc(new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8)));
        } finally {
            connection.disconnect();
        }
    }

    private static String toLrc(JSONObject captions) {
        JSONArray events = captions.optJSONArray("events");
        if (events == null) {
            return "";
        }

        StringBuilder lrc = new StringBuilder();
        String lastText = null;
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            JSONArray segments = event == null ? null : event.optJSONArray("segs");
            if (segments == null) {
                continue;
            }

            long startTimeMs = event.optLong("tStartMs", -1);
            if (startTimeMs < 0 || startTimeMs > MAX_LINE_START_MS) {
                continue;
            }

            StringBuilder line = new StringBuilder();
            for (int j = 0; j < segments.length(); j++) {
                JSONObject segment = segments.optJSONObject(j);
                if (segment != null) {
                    line.append(segment.optString("utf8"));
                }
            }

            // The card reads square brackets as the start time of a line.
            String text = line.toString()
                    .replace('[', '(')
                    .replace(']', ')')
                    .replaceAll("\\s+", " ")
                    .trim();
            // Speech recognition repeats a line as it scrolls up.
            if (text.isEmpty() || text.equals(lastText)) {
                continue;
            }
            lastText = text;

            String tag = String.format(Locale.US, "[%02d:%02d.%02d]",
                    startTimeMs / 60000, (startTimeMs / 1000) % 60, (startTimeMs % 1000) / 10);
            if (lrc.length() + tag.length() + text.length() + 1 > MAX_LYRICS_LENGTH) {
                break;
            }
            lrc.append(tag).append(text).append('\n');
        }

        return lrc.toString();
    }
}
