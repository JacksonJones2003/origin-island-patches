package app.originisland.extension;

import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Log;

/**
 * Reads and changes the repeat and shuffle modes of a media session of the AndroidX media library.
 *
 * The platform session has no repeat or shuffle mode. The library keeps them itself, and
 * controllers in other apps reach them through the binder and the custom actions used here.
 * These are a contract between apps, so they do not change with the obfuscation of the app.
 */
final class PlayModes {

    interface Listener {
        /**
         * Called on the main thread once the modes are known, and whenever one of them changes.
         */
        void onPlayModesChanged();
    }

    // PlaybackStateCompat
    static final int REPEAT_MODE_NONE = 0;
    static final int REPEAT_MODE_ONE = 1;
    static final int REPEAT_MODE_ALL = 2;
    static final int SHUFFLE_MODE_NONE = 0;
    static final int SHUFFLE_MODE_ALL = 1;

    private static final String TAG = "OriginIslandMusicCard";

    // MediaControllerCompat
    private static final String COMMAND_GET_EXTRA_BINDER =
            "android.support.v4.media.session.command.GET_EXTRA_BINDER";
    private static final String EXTRA_BINDER = "android.support.v4.media.session.EXTRA_BINDER";

    // MediaSessionCompat
    private static final String ACTION_SET_REPEAT_MODE =
            "android.support.v4.media.session.action.SET_REPEAT_MODE";
    private static final String ACTION_ARGUMENT_REPEAT_MODE =
            "android.support.v4.media.session.action.ARGUMENT_REPEAT_MODE";
    private static final String ACTION_SET_SHUFFLE_MODE =
            "android.support.v4.media.session.action.SET_SHUFFLE_MODE";
    private static final String ACTION_ARGUMENT_SHUFFLE_MODE =
            "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE";

    // IMediaSession.aidl
    private static final String SESSION_INTERFACE = "android.support.v4.media.session.IMediaSession";
    private static final int SESSION_REGISTER_CALLBACK_LISTENER = 3;
    private static final int SESSION_GET_REPEAT_MODE = 37;
    private static final int SESSION_GET_SHUFFLE_MODE = 47;

    // IMediaControllerCallback.aidl
    private static final String CALLBACK_INTERFACE = "android.support.v4.media.session.IMediaControllerCallback";
    private static final int CALLBACK_ON_REPEAT_MODE_CHANGED = 9;
    private static final int CALLBACK_ON_SHUFFLE_MODE_CHANGED = 12;

    private final MediaController controller;
    private final Handler mainHandler;
    private final Listener listener;

    private volatile boolean known;
    private volatile int repeatMode;
    private volatile int shuffleMode;

    /**
     * The session of the library calls this like the callback of a controller in another app.
     */
    private final Binder callback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != CALLBACK_ON_REPEAT_MODE_CHANGED && code != CALLBACK_ON_SHUFFLE_MODE_CHANGED) {
                return super.onTransact(code, data, reply, flags);
            }

            data.enforceInterface(CALLBACK_INTERFACE);
            int mode = data.readInt();
            if (code == CALLBACK_ON_REPEAT_MODE_CHANGED) {
                repeatMode = mode;
            } else {
                shuffleMode = mode;
            }

            // Called while the session holds its lock.
            mainHandler.post(listener::onPlayModesChanged);

            return true;
        }
    };

    PlayModes(MediaSession session, Handler mainHandler, Listener listener) {
        this.controller = session.getController();
        this.mainHandler = mainHandler;
        this.listener = listener;

        controller.sendCommand(COMMAND_GET_EXTRA_BINDER, null, new ResultReceiver(mainHandler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                IBinder sessionBinder = resultData == null ? null : resultData.getBinder(EXTRA_BINDER);
                if (sessionBinder != null) {
                    attach(sessionBinder);
                }
            }
        });
    }

    boolean isKnown() {
        return known;
    }

    int getRepeatMode() {
        return repeatMode;
    }

    int getShuffleMode() {
        return shuffleMode;
    }

    void setRepeatMode(int repeatMode) {
        Bundle arguments = new Bundle();
        arguments.putInt(ACTION_ARGUMENT_REPEAT_MODE, repeatMode);
        controller.getTransportControls().sendCustomAction(ACTION_SET_REPEAT_MODE, arguments);
    }

    void setShuffleMode(int shuffleMode) {
        Bundle arguments = new Bundle();
        arguments.putInt(ACTION_ARGUMENT_SHUFFLE_MODE, shuffleMode);
        controller.getTransportControls().sendCustomAction(ACTION_SET_SHUFFLE_MODE, arguments);
    }

    private void attach(IBinder sessionBinder) {
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(SESSION_INTERFACE);
                data.writeStrongBinder(callback);
                sessionBinder.transact(SESSION_REGISTER_CALLBACK_LISTENER, data, reply, 0);
                reply.readException();
            } finally {
                data.recycle();
                reply.recycle();
            }

            repeatMode = getMode(sessionBinder, SESSION_GET_REPEAT_MODE);
            shuffleMode = getMode(sessionBinder, SESSION_GET_SHUFFLE_MODE);
            known = true;

            listener.onPlayModesChanged();
        } catch (Exception ex) {
            Log.e(TAG, "Could not read the repeat and shuffle modes", ex);
        }
    }

    private static int getMode(IBinder sessionBinder, int transaction) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SESSION_INTERFACE);
            sessionBinder.transact(transaction, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
