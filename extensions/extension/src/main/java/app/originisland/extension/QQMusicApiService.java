package app.originisland.extension;

import android.app.Service;
import android.content.Intent;
import android.media.session.MediaController;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Lets the Jovi voice assistant of vivo ask the app for songs, the way it asks QQ Music.
 *
 * Jovi only takes requests for songs to a few music apps it knows. QQ Music is the one it asks
 * through an interface for other apps: it binds this service of QQ Music, says hi, searches for
 * what was asked for, and plays a song of the results by its id. A copy of the app installed under
 * the package of QQ Music is given the service. It answers a search with what was asked for as
 * the only song, and has the app play that from a search, as it does for a voice assistant.
 */
public final class QQMusicApiService extends Service {

    private static final String TAG = "OriginIslandJovi";

    static final String QQ_MUSIC_PACKAGE = "com.tencent.qqmusic";

    private static final String API_DESCRIPTOR = "com.tencent.qqmusic.third.api.contract.IQQMusicApi";
    private static final String CALLBACK_DESCRIPTOR = "com.tencent.qqmusic.third.api.contract.IQQMusicApiCallback";

    private static final int TRANSACTION_EXECUTE = 1;
    private static final int TRANSACTION_EXECUTE_ASYNC = 2;
    private static final int TRANSACTION_REGISTER_EVENT_LISTENER = 3;
    private static final int TRANSACTION_UNREGISTER_EVENT_LISTENER = 4;
    private static final int TRANSACTION_ON_RETURN = 1;
    private static final int TRANSACTION_ON_EVENT = 1;

    private static final String LISTENER_DESCRIPTOR = "com.tencent.qqmusic.third.api.contract.IQQMusicApiEventListener";
    private static final String EVENT_PLAY_STATE_CHANGED = "API_EVENT_PLAY_STATE_CHANGED";
    private static final String EVENT_PLAY_SONG_CHANGED = "API_EVENT_PLAY_SONG_CHANGED";
    private static final int PLAY_STATE_STARTED = 4;

    /**
     * How long playback is waited for after a song is asked for.
     */
    private static final long WAIT_FOR_PLAYBACK_MS = 15_000;

    private static final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private final List<IBinder> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final String KEY_CODE = "code";
    private static final String KEY_DATA = "data";

    private static final int CODE_OK = 0;

    /**
     * Start of the ids of the songs of the results, which are what was asked for.
     */
    private static final String SEARCH_ID_PREFIX = "ytm_search:";

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(API_DESCRIPTOR);
                return true;
            }

            try {
                switch (code) {
                    case TRANSACTION_EXECUTE: {
                        data.enforceInterface(API_DESCRIPTOR);
                        String action = data.readString();
                        Bundle result = execute(action, readBundle(data));
                        reply.writeNoException();
                        writeBundle(reply, result);
                        return true;
                    }
                    case TRANSACTION_EXECUTE_ASYNC: {
                        data.enforceInterface(API_DESCRIPTOR);
                        String action = data.readString();
                        Bundle params = readBundle(data);
                        IBinder callback = data.readStrongBinder();
                        returnTo(callback, execute(action, params));
                        return true;
                    }
                    case TRANSACTION_REGISTER_EVENT_LISTENER:
                    case TRANSACTION_UNREGISTER_EVENT_LISTENER: {
                        data.enforceInterface(API_DESCRIPTOR);
                        List<String> events = data.createStringArrayList();
                        IBinder listener = data.readStrongBinder();
                        if (listener != null) {
                            if (code == TRANSACTION_REGISTER_EVENT_LISTENER) {
                                listeners.add(listener);
                            } else {
                                listeners.remove(listener);
                            }
                        }
                        Log.i(TAG, (code == TRANSACTION_REGISTER_EVENT_LISTENER ? "Registered" : "Unregistered")
                                + " for " + events);
                        reply.writeNoException();
                        writeBundle(reply, result(CODE_OK));
                        return true;
                    }
                }
            } catch (Exception ex) {
                Log.e(TAG, "onTransact failure", ex);
                if (reply != null && code != TRANSACTION_EXECUTE_ASYNC) {
                    reply.writeNoException();
                    writeBundle(reply, result(CODE_OK));
                    return true;
                }
            }

            return super.onTransact(code, data, reply, flags);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        // Only a copy of the app under the package of QQ Music is asked by Jovi.
        if (!QQ_MUSIC_PACKAGE.equals(getPackageName())) {
            return null;
        }
        Log.i(TAG, "Bound by " + getPackageManager().getNameForUid(Binder.getCallingUid()));
        return binder;
    }

    private Bundle execute(String action, Bundle params) {
        Log.i(TAG, "Asked to " + action + " with " + describe(params));
        if (action == null) {
            return result(CODE_OK);
        }

        switch (action) {
            case "search": {
                String keyword = params == null ? null : params.getString("keyword");
                if (keyword == null || keyword.trim().isEmpty()) {
                    return listResult(new JSONArray());
                }
                return listResult(new JSONArray().put(searchResult(keyword.trim())));
            }

            case "playSongMidAtIndex":
            case "playSongMid":
            case "playSongIdAtIndex":
            case "playSongId": {
                String id = idOf(params);
                String query = queryOf(id);
                if (query != null) {
                    Log.i(TAG, "Playing what was asked for: " + query);
                    playFromSearch(query, id);
                }
                return result(CODE_OK);
            }

            case "playMusic":
            case "resumeMusic":
                control(MediaController.TransportControls::play);
                return result(CODE_OK);

            case "pauseMusic":
            case "stopMusic":
                control(MediaController.TransportControls::pause);
                return result(CODE_OK);

            case "skipToNext":
                control(MediaController.TransportControls::skipToNext);
                return result(CODE_OK);

            case "skipToPrevious":
                control(MediaController.TransportControls::skipToPrevious);
                return result(CODE_OK);

            case "getPlayList":
            case "getFolderList":
            case "getSongList":
                return listResult(new JSONArray());

            case "getCurrTime":
            case "getTotalTime": {
                Bundle result = result(CODE_OK);
                result.putLong(KEY_DATA, 0);
                return result;
            }

            case "isFavouriteMid":
            case "isQQMusicForeground": {
                Bundle result = result(CODE_OK);
                result.putBoolean(KEY_DATA, false);
                return result;
            }

            case "getLoginState": {
                Bundle result = result(CODE_OK);
                result.putBoolean(KEY_DATA, true);
                return result;
            }

            default:
                // Says hi, and takes every other request, so that Jovi does not ask for an authorization.
                return result(CODE_OK);
        }
    }

    private static JSONObject searchResult(String keyword) {
        try {
            String id = SEARCH_ID_PREFIX + keyword;
            return new JSONObject()
                    .put("id", id)
                    .put("mid", id)
                    .put("title", keyword)
                    .put("type", 0)
                    .put("singer", new JSONObject().put("title", "YouTube Music").put("mid", "").put("id", 0))
                    .put("album", new JSONObject().put("title", "").put("coverUri", "").put("mid", "").put("id", 0));
        } catch (Exception ex) {
            Log.e(TAG, "searchResult failure", ex);
            return new JSONObject();
        }
    }

    /**
     * @return The id of the song of the results Jovi plays.
     */
    private static String idOf(Bundle params) {
        if (params == null) {
            return null;
        }

        List<String> ids = params.getStringArrayList("midList");
        if (ids == null) {
            ids = params.getStringArrayList("songIdList");
        }
        if (ids != null && !ids.isEmpty()) {
            int index = Math.max(0, Math.min(ids.size() - 1, params.getInt("index", 0)));
            return ids.get(index);
        }
        return params.getString("mid", params.getString("songId"));
    }

    /**
     * @return What was asked for, from the id of a song of the results.
     */
    private static String queryOf(String id) {
        if (id == null || !id.startsWith(SEARCH_ID_PREFIX)) {
            return null;
        }
        // Jovi puts the singer and the name of the song together without a space.
        return id.substring(SEARCH_ID_PREFIX.length()).replaceAll("(?<=\\p{Ll})(?=\\p{Lu})", " ").trim();
    }

    /**
     * Has the app play what was asked for, and tells Jovi once it plays, which it waits for.
     */
    private void playFromSearch(String query, String id) {
        LibraryBrowser.connectAsAndroidAuto(getApplicationContext(), controller -> {
            MediaController.Callback[] callback = {null};
            callback[0] = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(android.media.session.PlaybackState state) {
                    if (state == null || state.getState() != android.media.session.PlaybackState.STATE_PLAYING) {
                        return;
                    }
                    Log.i(TAG, "Plays what was asked for");
                    controller.unregisterCallback(callback[0]);
                    sendPlaying(id);
                }
            };
            controller.registerCallback(callback[0], mainHandler);
            mainHandler.postDelayed(() -> controller.unregisterCallback(callback[0]), WAIT_FOR_PLAYBACK_MS);

            Log.i(TAG, "Asking the app to play from a search: " + query);
            controller.getTransportControls().playFromSearch(query, new Bundle());
        });
    }

    private void sendPlaying(String id) {
        Bundle state = new Bundle();
        state.putInt("playState", PLAY_STATE_STARTED);
        sendEvent(EVENT_PLAY_STATE_CHANGED, state);

        try {
            Bundle song = new Bundle();
            song.putString("playSong", searchResult(queryOf(id)).put("mid", id).put("id", id).toString());
            sendEvent(EVENT_PLAY_SONG_CHANGED, song);
        } catch (Exception ex) {
            Log.e(TAG, "sendPlaying failure", ex);
        }
    }

    private void sendEvent(String event, Bundle extras) {
        for (IBinder listener : listeners) {
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(LISTENER_DESCRIPTOR);
                data.writeString(event);
                writeBundle(data, extras);
                listener.transact(TRANSACTION_ON_EVENT, data, null, IBinder.FLAG_ONEWAY);
            } catch (Exception ex) {
                Log.e(TAG, "sendEvent failure", ex);
                listeners.remove(listener);
            } finally {
                data.recycle();
            }
        }
    }

    private static String describe(Bundle bundle) {
        if (bundle == null) {
            return "nothing";
        }
        StringBuilder text = new StringBuilder("{");
        for (String key : bundle.keySet()) {
            text.append(key).append('=').append(bundle.get(key)).append(", ");
        }
        return text.append('}').toString();
    }

    private void control(LibraryBrowser.Control control) {
        LibraryBrowser.control(getApplicationContext(), control);
    }

    private static Bundle result(int code) {
        Bundle result = new Bundle();
        result.putInt(KEY_CODE, code);
        return result;
    }

    private static Bundle listResult(JSONArray list) {
        Bundle result = result(CODE_OK);
        result.putString(KEY_DATA, list.toString());
        result.putBoolean("hasMore", false);
        return result;
    }

    private static void returnTo(IBinder callback, Bundle result) {
        if (callback == null) {
            return;
        }
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(CALLBACK_DESCRIPTOR);
            writeBundle(data, result);
            callback.transact(TRANSACTION_ON_RETURN, data, null, IBinder.FLAG_ONEWAY);
        } catch (Exception ex) {
            Log.e(TAG, "returnTo failure", ex);
        } finally {
            data.recycle();
        }
    }

    private static Bundle readBundle(Parcel parcel) {
        return parcel.readInt() != 0 ? Bundle.CREATOR.createFromParcel(parcel) : null;
    }

    private static void writeBundle(Parcel parcel, Bundle bundle) {
        if (bundle == null) {
            parcel.writeInt(0);
        } else {
            parcel.writeInt(1);
            bundle.writeToParcel(parcel, 0);
        }
    }
}
