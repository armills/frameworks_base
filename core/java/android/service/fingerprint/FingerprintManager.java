/**
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.service.fingerprint;

import android.content.ContentResolver;
import android.content.Context;
import android.hardware.fingerprint.Fingerprint;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;

import java.util.Collections;
import java.util.List;

/**
 * A class that coordinates access to the fingerprint hardware.
 * @hide
 */

public class FingerprintManager {
    private static final String TAG = "FingerprintManager";
    private static final boolean DEBUG = false;
    private static final int MSG_ENROLL_RESULT = 100;
    private static final int MSG_ACQUIRED = 101;
    private static final int MSG_PROCESSED = 102;
    private static final int MSG_ERROR = 103;
    private static final int MSG_REMOVED = 104;
    private static final int MSG_STATE_CHANGE = 105;

    // Errors generated by layers above HAL
    public static final int FINGERPRINT_ERROR_NO_RECEIVER = -10;

    // Message types.  Must agree with HAL (fingerprint.h)
    public static final int FINGERPRINT_ERROR = -1;
    public static final int FINGERPRINT_ACQUIRED = 1;
    public static final int FINGERPRINT_PROCESSED = 2;
    public static final int FINGERPRINT_TEMPLATE_ENROLLING = 3;
    public static final int FINGERPRINT_TEMPLATE_REMOVED = 4;

    // Error messages. Must agree with HAL (fingerprint.h)
    public static final int FINGERPRINT_ERROR_HW_UNAVAILABLE = 1;
    public static final int FINGERPRINT_ERROR_UNABLE_TO_PROCESS = 2;
    public static final int FINGERPRINT_ERROR_TIMEOUT = 3;
    public static final int FINGERPRINT_ERROR_NO_SPACE = 4;
    public static final int FINGERPRINT_ERROR_CANCELED = 5;
    public static final int FINGERPRINT_ERROR_UNABLE_TO_REMOVE = 6;

    // FINGERPRINT_ACQUIRED messages.  Must agree with HAL (fingerprint.h)
    public static final int FINGERPRINT_ACQUIRED_GOOD = 0;
    public static final int FINGERPRINT_ACQUIRED_PARTIAL = 1;
    public static final int FINGERPRINT_ACQUIRED_INSUFFICIENT = 2;
    public static final int FINGERPRINT_ACQUIRED_IMAGER_DIRTY = 4;
    public static final int FINGERPRINT_ACQUIRED_TOO_SLOW = 8;
    public static final int FINGERPRINT_ACQUIRED_TOO_FAST = 16;

    // Fingerprint States
    public static final int STATE_UNKNOWN = -1;
    public static final int STATE_IDLE = 0;
    public static final int STATE_AUTHENTICATING = 1;
    public static final int STATE_ENROLLING = 2;

    /**
     * The time, in milliseconds, to run the device vibrator after a fingerprint
     * image has been aquired or enrolled by the fingerprint sensor.
     */
    public static final long FINGERPRINT_EVENT_VIBRATE_DURATION = 100;

    private IFingerprintService mService;
    private FingerprintManagerReceiver mClientReceiver;
    private Context mContext;
    private IBinder mToken = new Binder();

    private Handler mHandler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            if (mClientReceiver != null) {
                switch(msg.what) {
                    case MSG_ENROLL_RESULT:
                        mClientReceiver.onEnrollResult(msg.arg1, msg.arg2);
                        break;
                    case MSG_ACQUIRED:
                        mClientReceiver.onAcquired(msg.arg1);
                        break;
                    case MSG_PROCESSED:
                        mClientReceiver.onProcessed(msg.arg1);
                        break;
                    case MSG_ERROR:
                        mClientReceiver.onError(msg.arg1);
                        break;
                    case MSG_REMOVED:
                        mClientReceiver.onRemoved(msg.arg1);
                        break;
                    case MSG_STATE_CHANGE:
                        mClientReceiver.onStateChanged(msg.arg1);
                        break;
                }
            }
        }
    };

    /**
     * @hide
     */
    public FingerprintManager(Context context, IFingerprintService service) {
        mContext = context;
        mService = service;
        if (mService == null) {
            Slog.v(TAG, "FingerprintManagerService was null");
        }
    }

    private IFingerprintServiceReceiver mServiceReceiver = new IFingerprintServiceReceiver.Stub() {

        public void onEnrollResult(int fingerprintId,  int remaining) {
            mHandler.obtainMessage(MSG_ENROLL_RESULT, fingerprintId, remaining).sendToTarget();
        }

        public void onAcquired(int acquireInfo) {
            mHandler.obtainMessage(MSG_ACQUIRED, acquireInfo, 0).sendToTarget();
        }

        public void onProcessed(int fingerprintId) {
            mHandler.obtainMessage(MSG_PROCESSED, fingerprintId, 0).sendToTarget();
        }

        public void onError(int error) {
            mHandler.obtainMessage(MSG_ERROR, error, 0).sendToTarget();
        }

        public void onRemoved(int fingerprintId) {
            mHandler.obtainMessage(MSG_REMOVED, fingerprintId, 0).sendToTarget();
        }

        public void onStateChanged(int state) {
            mHandler.obtainMessage(MSG_STATE_CHANGE, state, 0).sendToTarget();
        }

    };

    /**
     * Determine whether the user has at least one fingerprint enrolled.
     *
     * @return true if at least one is enrolled
     */
    public boolean userEnrolled() {
        ContentResolver res = mContext.getContentResolver();
        return FingerprintUtils.getFingerprintsForUser(res, getCurrentUserId()).size() > 0;
    }

    /**
     * Start the authentication process.
     */
    public void authenticate() {
        authenticate(false);
    }

    /**
     * Start the authentication process.
     *
     * @param disableVibration True to disable vibration when print scanned
     */
    public void authenticate(boolean disableVibration) {
        if (mServiceReceiver == null) {
            sendError(FINGERPRINT_ERROR_NO_RECEIVER, 0, 0);
            return;
        }
        if (mService != null) try {
            mService.authenticate(mToken, getCurrentUserId(), disableVibration);
        } catch (RemoteException e) {
            Log.v(TAG, "Remote exception while enrolling: ", e);
            sendError(FINGERPRINT_ERROR_HW_UNAVAILABLE, 0, 0);
        }
    }

    /**
     * Start the enrollment process.  Timeout dictates how long to wait for the user to
     * enroll a fingerprint.
     *
     * @param timeout
     */
    public void enroll(long timeout) {
        if (mServiceReceiver == null) {
            sendError(FINGERPRINT_ERROR_NO_RECEIVER, 0, 0);
            return;
        }
        if (mService != null) try {
            mService.enroll(mToken, timeout, getCurrentUserId());
        } catch (RemoteException e) {
            Log.v(TAG, "Remote exception while enrolling: ", e);
            sendError(FINGERPRINT_ERROR_HW_UNAVAILABLE, 0, 0);
        }
    }

    /**
     * Remove the given fingerprintId from the system.  FingerprintId of 0 has special meaning
     * which is to delete all fingerprint data for the current user. Use with caution.
     * @param fingerprintId
     */
    public void remove(int fingerprintId) {
        if (mServiceReceiver == null) {
            sendError(FINGERPRINT_ERROR_NO_RECEIVER, 0, 0);
            return;
        }
        if (mService != null) {
            try {
                mService.remove(mToken, fingerprintId, getCurrentUserId());
            } catch (RemoteException e) {
                Log.v(TAG, "Remote exception during remove of fingerprintId: " + fingerprintId, e);
            }
        } else {
            Log.w(TAG, "remove(): Service not connected!");
            sendError(FINGERPRINT_ERROR_HW_UNAVAILABLE, 0, 0);
        }
    }

    /**
     * Rename the fingerprint
     */
    public void setFingerprintName(int fingerprintId, String newName) {
        if (mServiceReceiver == null) {
            sendError(FINGERPRINT_ERROR_NO_RECEIVER, 0, 0);
            return;
        }
        if (mService != null) {
            try {
                mService.setFingerprintName(mToken, fingerprintId, newName, getCurrentUserId());
            } catch (RemoteException e) {
                Log.v(TAG, "Remote exception renaming fingerprintId: " + fingerprintId, e);
            }
        } else {
            Log.w(TAG, "setFingerprintName(): Service not connected!");
            sendError(FINGERPRINT_ERROR_HW_UNAVAILABLE, 0, 0);
        }
    }

    /**
     * Starts listening for fingerprint events.  When a finger is scanned or recognized, the
     * client will be notified via the callback.
     */
    public void startListening(FingerprintManagerReceiver receiver) {
        mClientReceiver = receiver;
        if (mService != null) {
            try {
                mService.startListening(mToken, mServiceReceiver, getCurrentUserId());
            } catch (RemoteException e) {
                Log.v(TAG, "Remote exception in startListening(): ", e);
            }
        } else {
            Log.w(TAG, "startListening(): Service not connected!");
            sendError(FINGERPRINT_ERROR_HW_UNAVAILABLE, 0, 0);
        }
    }

    public int getState() {
        if (mService != null) {
            try {
                return mService.getState();
            } catch (RemoteException e) {
                Log.v(TAG, "Remote exception in getState(): ", e);
            }
        } else {
            Log.w(TAG, "getState(): Service not connected!");
            sendError(FINGERPRINT_ERROR_HW_UNAVAILABLE, 0, 0);
        }
        return STATE_UNKNOWN;
    }

    private int getCurrentUserId() {
        return Process.myUserHandle().getIdentifier();
    }

    /**
     * Stops the client from listening to fingerprint events.
     */
    public void stopListening() {
        if (mService != null) {
            try {
                mService.stopListening(mToken, getCurrentUserId());
                mClientReceiver = null;
            } catch (RemoteException e) {
                Log.v(TAG, "Remote exception in stopListening(): ", e);
            }
        } else {
            Log.w(TAG, "stopListening(): Service not connected!");
            sendError(FINGERPRINT_ERROR_HW_UNAVAILABLE, 0, 0);
        }
    }

    public void cancel() {
        if (mServiceReceiver == null) {
            sendError(FINGERPRINT_ERROR_NO_RECEIVER, 0, 0);
            return;
        }
        if (mService != null) {
            try {
                mService.cancel(mToken, getCurrentUserId());
            } catch (RemoteException e) {
                Log.v(TAG, "Remote exception in enrollCancel(): ", e);
                sendError(FINGERPRINT_ERROR_HW_UNAVAILABLE, 0, 0);
            }
        } else {
            Log.w(TAG, "cancel(): Service not connected!");
        }
    }

    public List<Fingerprint> getEnrolledFingerprints() {
        if (mService != null) {
            try {
                return mService.getEnrolledFingerprints(mToken, getCurrentUserId());
            } catch (RemoteException e) {
                Log.v(TAG, "Remote exception in getEnrolledFingerprints(): ", e);
            }
        } else {
            Log.w(TAG, "getEnrolledFingerprints(): Service not connected!");
        }
        return Collections.emptyList();
    }

    public int getNumEnrollmentSteps() {
        if (mService != null) {
            try {
                return mService.getNumEnrollmentSteps(mToken);
            } catch (RemoteException e) {
                Log.v(TAG, "Remote exception in getNumEnrollmentSteps(): ", e);
            }
        } else {
            Log.w(TAG, "getNumEnrollmentSteps(): Service not connected!");
        }
        return -1;
    }

    /**
     * Set the fingerprint hardware into wake up mode.
     * During wakeup mode, vibrations will be disabled.
     *
     * Requires the caller hold {@link android.Manifest.permission#CONTROL_KEYGUARD} permission.
     *
     * @param wakeup whether to enable wakeup mode or not.
     */
    public void setWakeup(boolean wakeup) {
        if (mService != null) {
            try {
                mService.setWakeup(mToken, getCurrentUserId(), wakeup);
            } catch (RemoteException e) {
                Log.v(TAG, "Remote exception in setWakeup(): ", e);
            }
        } else {
            Log.w(TAG, "setWakeup(): Service not connected!");
        }
    }

    private void sendError(int msg, int arg1, int arg2) {
        mHandler.obtainMessage(msg, arg1, arg2);
    }
}
