package app.originisland.extension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the app plays after a video, read from the watch next response the app loads for the video.
 * That is the playlist or mix the video is played from, or otherwise the video autoplay
 * continues with and the videos the app suggests below the video and as the video ends.
 */
final class UpNextVideos {

    static final class Video {
        final String id;
        final String title;
        final String channel;
        /**
         * Playlist the video is played as a part of, or null if it is played by itself.
         */
        final String playlistId;

        Video(String id, String title, String channel, String playlistId) {
            this.id = id;
            this.title = title;
            this.channel = channel;
            this.playlistId = playlistId;
        }
    }

    // WatchNextResponse
    private static final int FIELD_CONTENTS = 7;
    private static final int FIELD_CURRENT_VIDEO_ENDPOINT = 9;
    private static final int FIELD_PLAYER_OVERLAYS = 14;

    private static final int FIELD_SINGLE_COLUMN_WATCH_NEXT_RESULTS = 51779735;
    private static final int FIELD_RESULTS_RESULTS = 1;
    private static final int FIELD_RESULTS_PLAYLIST = 2;

    // The videos the app suggests below the video, each an element with a model of its own.
    private static final int[] PATH_SUGGESTION_SECTIONS = {49399797, 1};
    private static final int[] PATH_SECTION_ITEMS = {50195462, 1};
    private static final int[] PATH_ITEM_VIDEO = {153515154, 172660663, 1, 168777401, 5, 232954548, 18, 1};
    private static final int FIELD_SUGGESTED_VIDEO_ADDRESS = 6;
    private static final int[] PATH_SUGGESTED_VIDEO_METADATA = {21, 8178};
    private static final int[] PATH_SUGGESTED_VIDEO_TITLE = {1, 1};
    private static final int[] PATH_SUGGESTED_VIDEO_DETAILS = {3, 514549827, 1};
    private static final int[] PATH_SUGGESTED_VIDEO_DETAILS_TEXT = {1, 2, 1};
    private static final String ADDRESS_VIDEO_ID_PARAMETER = "v";
    private static final int FIELD_PLAYLIST_PANEL_RENDERER = 50631000;
    private static final int FIELD_PLAYLIST_PANEL_CONTENTS = 2;
    private static final int FIELD_PLAYLIST_PANEL_VIDEO_RENDERER = 51779701;
    private static final int FIELD_PLAYLIST_PANEL_VIDEO_TITLE = 1;
    private static final int FIELD_PLAYLIST_PANEL_VIDEO_BYLINE = 2;
    private static final int FIELD_PLAYLIST_PANEL_VIDEO_ENDPOINT = 7;

    private static final int FIELD_PLAYER_OVERLAY_RENDERER = 78882851;
    private static final int FIELD_PLAYER_OVERLAY_END_SCREEN = 3;
    private static final int FIELD_PLAYER_OVERLAY_AUTOPLAY = 4;
    private static final int FIELD_PLAYER_OVERLAY_AUTOPLAY_RENDERER = 92155347;
    private static final int FIELD_AUTOPLAY_VIDEO_TITLE = 2;
    private static final int FIELD_AUTOPLAY_BYLINE = 3;
    private static final int FIELD_AUTOPLAY_VIDEO_ID = 21;
    private static final int FIELD_WATCH_NEXT_END_SCREEN_RENDERER = 57158578;
    private static final int FIELD_END_SCREEN_RESULTS = 1;
    private static final int FIELD_END_SCREEN_VIDEO_RENDERER = 57153017;
    private static final int FIELD_END_SCREEN_VIDEO_ID = 1;
    private static final int FIELD_END_SCREEN_VIDEO_TITLE = 3;
    private static final int FIELD_END_SCREEN_VIDEO_BYLINE = 5;

    private static final int FIELD_WATCH_ENDPOINT = 48687757;
    private static final int FIELD_WATCH_ENDPOINT_VIDEO_ID = 1;
    private static final int FIELD_WATCH_ENDPOINT_PLAYLIST_ID = 2;

    // Text
    private static final int FIELD_TEXT_RUNS = 1;
    private static final int FIELD_TEXT_RUN_TEXT = 1;
    private static final int FIELD_TEXT_SIMPLE_TEXT = 4;

    private static final int MAX_VIDEOS = 200;

    /**
     * Id of the video these play after.
     */
    final String videoId;
    final List<Video> videos;

    private UpNextVideos(String videoId, List<Video> videos) {
        this.videoId = videoId;
        this.videos = videos;
    }

    Video find(String id) {
        for (Video video : videos) {
            if (video.id.equals(id)) {
                return video;
            }
        }

        return null;
    }

    /**
     * @return What plays after the video of the response, or null if the response is not for a video.
     */
    static UpNextVideos parse(byte[] watchNextResponse) {
        ProtoReader response = new ProtoReader(watchNextResponse);

        ProtoReader currentVideo = response.message(FIELD_CURRENT_VIDEO_ENDPOINT, FIELD_WATCH_ENDPOINT);
        String videoId = currentVideo == null ? null : currentVideo.string(FIELD_WATCH_ENDPOINT_VIDEO_ID);
        if (videoId == null || videoId.isEmpty()) {
            return null;
        }

        List<Video> videos = new ArrayList<>();
        Set<String> ids = new HashSet<>();

        ProtoReader playlist = response.message(FIELD_CONTENTS, FIELD_SINGLE_COLUMN_WATCH_NEXT_RESULTS,
                FIELD_RESULTS_PLAYLIST, FIELD_PLAYLIST_PANEL_RENDERER);
        if (playlist != null) {
            for (ProtoReader content : playlist.messages(FIELD_PLAYLIST_PANEL_CONTENTS)) {
                ProtoReader video = content.message(FIELD_PLAYLIST_PANEL_VIDEO_RENDERER);
                ProtoReader endpoint = video == null ? null
                        : video.message(FIELD_PLAYLIST_PANEL_VIDEO_ENDPOINT, FIELD_WATCH_ENDPOINT);
                if (endpoint == null) {
                    continue;
                }

                add(videos, ids, endpoint.string(FIELD_WATCH_ENDPOINT_VIDEO_ID),
                        textOf(video.message(FIELD_PLAYLIST_PANEL_VIDEO_TITLE)),
                        textOf(video.message(FIELD_PLAYLIST_PANEL_VIDEO_BYLINE)),
                        endpoint.string(FIELD_WATCH_ENDPOINT_PLAYLIST_ID));
            }
        }

        // A playlist is what plays next by itself.
        int playlistVideos = videos.size();
        ProtoReader overlay = playlistVideos == 0
                ? response.message(FIELD_PLAYER_OVERLAYS, FIELD_PLAYER_OVERLAY_RENDERER)
                : null;
        if (overlay != null) {
            ids.add(videoId);

            ProtoReader autoplay = overlay.message(FIELD_PLAYER_OVERLAY_AUTOPLAY,
                    FIELD_PLAYER_OVERLAY_AUTOPLAY_RENDERER);
            if (autoplay != null) {
                add(videos, ids, autoplay.string(FIELD_AUTOPLAY_VIDEO_ID),
                        textOf(autoplay.message(FIELD_AUTOPLAY_VIDEO_TITLE)),
                        textOf(autoplay.message(FIELD_AUTOPLAY_BYLINE)),
                        null);
            }

            ProtoReader endScreen = overlay.message(FIELD_PLAYER_OVERLAY_END_SCREEN,
                    FIELD_WATCH_NEXT_END_SCREEN_RENDERER);
            if (endScreen != null) {
                for (ProtoReader result : endScreen.messages(FIELD_END_SCREEN_RESULTS)) {
                    ProtoReader video = result.message(FIELD_END_SCREEN_VIDEO_RENDERER);
                    if (video == null) {
                        continue;
                    }

                    add(videos, ids, video.string(FIELD_END_SCREEN_VIDEO_ID),
                            textOf(video.message(FIELD_END_SCREEN_VIDEO_TITLE)),
                            textOf(video.message(FIELD_END_SCREEN_VIDEO_BYLINE)),
                            null);
                }
            }
        }

        ProtoReader results = videos.size() >= MAX_VIDEOS || playlistVideos > 0 ? null
                : response.message(FIELD_CONTENTS, FIELD_SINGLE_COLUMN_WATCH_NEXT_RESULTS, FIELD_RESULTS_RESULTS);
        if (results != null) {
            for (ProtoReader section : results.messagesAt(PATH_SUGGESTION_SECTIONS)) {
                for (ProtoReader item : section.messagesAt(PATH_SECTION_ITEMS)) {
                    ProtoReader video = item.message(PATH_ITEM_VIDEO);
                    ProtoReader metadata = video == null ? null : video.message(PATH_SUGGESTED_VIDEO_METADATA);
                    ProtoReader title = metadata == null ? null : metadata.message(PATH_SUGGESTED_VIDEO_TITLE);
                    if (title == null) {
                        continue;
                    }

                    add(videos, ids, videoIdOf(video.string(FIELD_SUGGESTED_VIDEO_ADDRESS)),
                            title.text(), detailsOf(metadata), null);
                }
            }
        }

        return new UpNextVideos(videoId, videos);
    }

    /**
     * @return The id of the video of an address of the app for sharing a video, or null if it has none.
     */
    private static String videoIdOf(String address) {
        if (address == null) {
            return null;
        }

        int query = address.indexOf('?');
        if (query < 0) {
            return null;
        }
        for (String parameter : address.substring(query + 1).split("&")) {
            if (parameter.startsWith(ADDRESS_VIDEO_ID_PARAMETER + "=")) {
                return parameter.substring(ADDRESS_VIDEO_ID_PARAMETER.length() + 1);
            }
        }

        return null;
    }

    /**
     * @return The line below the title of a suggested video, which has its channel, its views
     *         and its age in columns.
     */
    private static String detailsOf(ProtoReader metadata) {
        for (ProtoReader line : metadata.messagesAt(PATH_SUGGESTED_VIDEO_DETAILS)) {
            ProtoReader text = line.message(PATH_SUGGESTED_VIDEO_DETAILS_TEXT);
            String details = text == null ? "" : text.text().trim();
            if (!details.isEmpty()) {
                return details.replaceAll("\\s{2,}", " \u00b7 ");
            }
        }

        return "";
    }

    private static void add(List<Video> videos, Set<String> ids, String id, String title, String channel,
                            String playlistId) {
        if (id == null || id.isEmpty() || title.isEmpty() || videos.size() >= MAX_VIDEOS || !ids.add(id)) {
            return;
        }

        videos.add(new Video(id, title, channel, playlistId == null || playlistId.isEmpty() ? null : playlistId));
    }

    private static String textOf(ProtoReader text) {
        if (text == null) {
            return "";
        }

        StringBuilder runs = new StringBuilder();
        for (ProtoReader run : text.messages(FIELD_TEXT_RUNS)) {
            String runText = run.string(FIELD_TEXT_RUN_TEXT);
            if (runText != null) {
                runs.append(runText);
            }
        }
        if (runs.length() > 0) {
            return runs.toString();
        }

        String simpleText = text.string(FIELD_TEXT_SIMPLE_TEXT);
        return simpleText == null ? "" : simpleText;
    }
}
