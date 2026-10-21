package app.originisland.extension;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * Pauses what the app plays as a foldable phone is closed, the way a laptop does as its lid is closed.
 *
 * Foldable phones report the angle between their halves with a hinge angle sensor. It only reports
 * as the angle changes, so it is listened to only while the app plays.
 */
@SuppressWarnings("unused")
public final class PauseOnFoldPatch {

    private static final String TAG = "PauseOnFold";

    /**
     * The phone counts as closed below this angle, and as opened again above the other one,
     * so that a phone that is held almost closed does not pause and resume over and over.
     */
    private static final float CLOSED_ANGLE = 15;
    private static final float OPENED_ANGLE = 45;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static WeakReference<MediaSession> playingSessionRef = new WeakReference<>(null);
    private static SensorManager sensorManager;
    private static boolean listening;
    /**
     * If the phone was opened since the app started to play. Playing on the cover screen of
     * a closed phone is not paused, only closing it.
     */
    private static boolean opened;

    private static final SensorEventListener hingeListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            onHingeAngle(event.values[0]);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private PauseOnFoldPatch() {
    }

    /**
     * Injection point. Called with every playback state the app gives a session.
     */
    public static void onSetPlaybackState(MediaSession session, PlaybackState state) {
        boolean playing = state != null && (state.getState() == PlaybackState.STATE_PLAYING
                || state.getState() == PlaybackState.STATE_BUFFERING);

        mainHandler.post(() -> {
            try {
                if (playing) {
                    playingSessionRef = new WeakReference<>(session);
                    startListening();
                } else if (session == playingSessionRef.get()) {
                    stopListening();
                }
            } catch (Exception ex) {
                Log.e(TAG, "onSetPlaybackState failure", ex);
            }
        });
    }

    private static void startListening() throws Exception {
        if (listening) {
            return;
        }

        if (sensorManager == null) {
            // Hidden, but not restricted.
            Context context = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
            sensorManager = context == null ? null : context.getSystemService(SensorManager.class);
        }

        Sensor hinge = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        if (hinge == null) {
            // Not a foldable phone.
            return;
        }

        // The sensor reports the angle the phone is at as soon as it is listened to.
        opened = false;
        listening = sensorManager.registerListener(hingeListener, hinge, SensorManager.SENSOR_DELAY_NORMAL, mainHandler);
    }

    private static void stopListening() {
        if (listening) {
            sensorManager.unregisterListener(hingeListener);
            listening = false;
        }
    }

    private static void onHingeAngle(float angle) {
        try {
            if (angle >= OPENED_ANGLE) {
                opened = true;
                return;
            }
            if (!opened || angle > CLOSED_ANGLE) {
                return;
            }
            opened = false;

            MediaSession session = playingSessionRef.get();
            if (session != null) {
                Log.i(TAG, "Closed at " + angle + " degrees, pausing");
                session.getController().getTransportControls().pause();
            }
        } catch (Exception ex) {
            Log.e(TAG, "onHingeAngle failure", ex);
        }
    }
}
