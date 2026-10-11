package app.originisland.extension;

import android.media.MediaMetadata;
import android.media.Rating;
import android.util.Log;

import java.util.Objects;

/**
 * The OriginOS music card (com.vivo.musicwidgetmix) draws its heart button from a heart
 * {@link Rating} in the session metadata, and sends a heart rating through
 * {@link android.media.session.MediaController.TransportControls#setRating(Rating)} when the
 * button is tapped. YouTube Music rates with thumbs instead, so this translates between the two.
 */
@SuppressWarnings("unused")
public final class OriginIslandLikeButtonPatch {

    private static final String TAG = "OriginIslandLike";

    private static final String MUSIC_CARD_PACKAGE = "com.vivo.musicwidgetmix";

    /**
     * Bit mask of what the card may show and control. Without it the card disables everything.
     */
    private static final String KEY_SUPPORT_EVENT = "vivomusicmix.media.metadata.support_event";
    private static final long EVENT_PLAY_CONTROL = 1;
    private static final long EVENT_MUSIC_INFO = 1 << 1;
    private static final long EVENT_MUSIC_IMAGE = 1 << 2;
    private static final long EVENT_SEEK_POSITION = 1 << 4;
    private static final long EVENT_FAVORITE = 1 << 6;
    private static final long SUPPORT_EVENT = EVENT_PLAY_CONTROL | EVENT_MUSIC_INFO
            | EVENT_MUSIC_IMAGE | EVENT_SEEK_POSITION | EVENT_FAVORITE;

    private OriginIslandLikeButtonPatch() {
    }

    /**
     * Injection point.
     */
    public static boolean isMusicCard(String clientPackageName) {
        return MUSIC_CARD_PACKAGE.equals(clientPackageName);
    }

    /**
     * Injection point.
     */
    public static MediaMetadata onSetMetadata(MediaMetadata metadata) {
        try {
            if (metadata == null) {
                return null;
            }

            Rating userRating = metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
            boolean liked = userRating != null && userRating.isThumbUp();

            MediaMetadata.Builder builder = new MediaMetadata.Builder(metadata)
                    .putRating(MediaMetadata.METADATA_KEY_RATING, Rating.newHeartRating(liked))
                    .putLong(KEY_SUPPORT_EVENT, SUPPORT_EVENT);

            // The card ignores a song change unless the media id changes as well.
            if (metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) == null) {
                builder.putString(MediaMetadata.METADATA_KEY_MEDIA_ID, trackIdOf(metadata));
            }

            return builder.build();
        } catch (Exception ex) {
            Log.e(TAG, "onSetMetadata failure", ex);
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

    private static String trackIdOf(MediaMetadata metadata) {
        return Integer.toHexString(Objects.hash(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        ));
    }
}
