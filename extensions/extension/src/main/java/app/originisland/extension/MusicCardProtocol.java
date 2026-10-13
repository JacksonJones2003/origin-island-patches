package app.originisland.extension;

import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * What the OriginOS music card (com.vivo.musicwidgetmix) reads from and sends to the media session
 * and the media browser service of an app it supports.
 */
final class MusicCardProtocol {

    static final String MUSIC_CARD_PACKAGE = "com.vivo.musicwidgetmix";

    /**
     * Action of the media browser service the card looks for. The patch adds it to the service.
     */
    static final String MUSIC_CARD_SERVICE_ACTION = "com.vivo.musicwidgetmix.support.service";

    /**
     * Bit mask of what the card may show and control. Without it the card disables everything.
     */
    static final String KEY_SUPPORT_EVENT = "vivomusicmix.media.metadata.support_event";
    static final long EVENT_PLAY_CONTROL = 1;
    static final long EVENT_MUSIC_INFO = 1 << 1;
    static final long EVENT_MUSIC_IMAGE = 1 << 2;
    static final long EVENT_MUSIC_LRC = 1 << 3;
    static final long EVENT_SEEK_POSITION = 1 << 4;
    static final long EVENT_FAVORITE = 1 << 6;
    static final long EVENT_LOOP_MODE = 1 << 7;
    static final long EVENT_LIST_CURRENT = 1 << 8;
    static final long EVENT_LIST_FAVORITE = 1 << 9;
    static final long EVENT_LIST_LOCAL = 1 << 10;

    static final String KEY_LOOP_MODE = "vivomusicmix.media.metadata.LOOP_MODE";
    static final int LOOP_MODE_LIST = 1;
    static final int LOOP_MODE_SINGLE = 2;
    static final int LOOP_MODE_RANDOM = 3;

    /**
     * Custom action the card sends with the {@link #KEY_LOOP_MODE} it wants to change to.
     */
    static final String ACTION_PLAY_MODE = "vivomusicmix.media.action.PLAY_MODE";

    // Session extras the card reads lyrics from. The keys are misspelled by the card.
    static final String EXTRA_ACTION = "vivomusicmix.meida.extra.key.action";
    static final String EXTRA_ACTION_LYRICS_CHANGED = "vivomusicmix.extra.lrc_change";
    static final String EXTRA_MEDIA_ID = "vivomusicmix.extra.key.meidia_id";
    static final String EXTRA_LYRICS = "vivomusicmix.extra.key.lyric";

    static final String BROWSER_ROOT_ID = "origin_island_music_card";

    /**
     * Media id of the entry that says a list has nothing in it. The card keeps loading
     * a list without entries, and then says that the network failed.
     */
    static final String EMPTY_LIST_MEDIA_ID = "origin_island_empty_list";

    /**
     * Media browser parents of the lists, which the card loads in pages. The card reads
     * from the extras of the last song of a page if there is another page, and its number.
     */
    static final String LIST_CURRENT = "vivomusicmix_current_list";
    static final String LIST_FAVORITE = "vivomusicmix_favorite_list";
    static final String LIST_LOCAL = "vivomusicmix_local_list";
    static final String LIST_KEY_PAGE = "vivomusicmix_key_media_page";
    static final String LIST_KEY_HAS_MORE = "vivomusicmix_key_has_more";

    /**
     * Extra of the list a song is played from, sent with its media id.
     */
    static final String KEY_LIST = "vivomusicmix_key_list";

    private MusicCardProtocol() {
    }

    /**
     * @return The items of a list as the only page the card has to load of it.
     */
    static List<MediaBrowser.MediaItem> toSinglePage(List<MediaDescription> descriptions) {
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
}
