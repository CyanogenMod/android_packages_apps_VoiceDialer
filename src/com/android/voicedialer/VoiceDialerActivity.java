/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.voicedialer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.DialogInterface;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.util.Config;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;

/**
 * TODO: get rid of the anonymous classes
 * TODO: merge with BluetoothVoiceDialerActivity
 *
 * This class is the user interface of the VoiceDialer application.
 * Its life cycle is as follows:
 * <ul>
 * <li>The user presses the recognize key, and the VoiceDialerActivity starts.
 * <li>A {@link RecognizerEngine} instance is created.
 * <li>The RecognizerEngine signals the user to speak with the Vibrator.
 * <li>The RecognizerEngine captures, processes, and recognizes speech
 * against the names in the contact list.
 * <li>The RecognizerEngine calls onRecognizerSuccess with a list of
 * sentences and corresponding Intents.
 * <li>If the list is one element long, the corresponding Intent is dispatched.
 * <li>Else an {@link AlertDialog} containing the list of sentences is
 * displayed.
 * <li>The user selects the desired sentence from the list,
 * and the corresponding Intent is dispatched.
 * <ul>
 * Notes:
 * <ul>
 * <li>The RecognizerEngine is kept and reused for the next recognition cycle.
 * </ul>
 */
public class VoiceDialerActivity extends Activity {

    private static final String TAG = "VoiceDialerActivity";

    private static final String MICROPHONE_EXTRA = "microphone";
    private static final String CONTACTS_EXTRA = "contacts";
    private static final String SAMPLE_RATE_EXTRA = "samplerate";
    private static final String INTENTS_KEY = "intents";

    private static final int FAIL_PAUSE_MSEC = 5000;
    private static final int SAMPLE_RATE = 11025;

    private static final int DIALOG_ID = 1;

    private final static CommandRecognizerEngine mCommandEngine =
            new CommandRecognizerEngine();
    private CommandRecognizerClient mCommandClient;
    private VoiceDialerTester mVoiceDialerTester;
    private Handler mHandler;
    private Thread mRecognizerThread = null;
    private AudioManager mAudioManager;
    private ToneGenerator mToneGenerator;
    private AlertDialog mAlertDialog;

    @Override
    protected void onCreate(Bundle icicle) {
        if (Config.LOGD) Log.d(TAG, "onCreate");
        super.onCreate(icicle);
        mHandler = new Handler();
        mAudioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        mToneGenerator = new ToneGenerator(AudioManager.STREAM_RING,
                ToneGenerator.MAX_VOLUME);
    }

    protected void onStart() {
        if (Config.LOGD) Log.d(TAG, "onStart "  + getIntent());
        super.onStart();
        mAudioManager.requestAudioFocus(
                null, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);

        mCommandEngine.setContactsFile(newFile(getArg(CONTACTS_EXTRA)));
        mCommandClient = new CommandRecognizerClient();
        mCommandEngine.setMinimizeResults(false);
        mCommandEngine.setAllowOpenEntries(true);

        // open main window
        setTheme(android.R.style.Theme_Dialog);
        setTitle(R.string.title);
        setContentView(R.layout.voice_dialing);
        findViewById(R.id.microphone_view).setVisibility(View.INVISIBLE);
        findViewById(R.id.retry_view).setVisibility(View.INVISIBLE);
        findViewById(R.id.microphone_loading_view).setVisibility(View.VISIBLE);
        if (RecognizerLogger.isEnabled(this)) {
            ((TextView)findViewById(R.id.substate)).setText(R.string.logging_enabled);
        }

        // start the tester, if present
        mVoiceDialerTester = null;
        File micDir = newFile(getArg(MICROPHONE_EXTRA));
        if (micDir != null && micDir.isDirectory()) {
            mVoiceDialerTester = new VoiceDialerTester(micDir);
            startNextTest();
            return;
        }

        startWork();
    }

    private void startWork() {
        // start the engine
        mRecognizerThread = new Thread() {
            public void run() {
                if (Config.LOGD) Log.d(TAG, "onCreate.Runnable.run");
                String sampleRateStr = getArg(SAMPLE_RATE_EXTRA);
                int sampleRate = SAMPLE_RATE;
                if (sampleRateStr != null) {
                    sampleRate = Integer.parseInt(sampleRateStr);
                }
                mCommandEngine.recognize(mCommandClient, VoiceDialerActivity.this,
                        newFile(getArg(MICROPHONE_EXTRA)),
                        sampleRate);
            }
        };
        mRecognizerThread.start();
    }

    private String getArg(String name) {
        if (name == null) return null;
        String arg = getIntent().getStringExtra(name);
        if (arg != null) return arg;
        arg = SystemProperties.get("app.voicedialer." + name);
        return arg != null && arg.length() > 0 ? arg : null;
    }

    private static File newFile(String name) {
        return name != null ? new File(name) : null;
    }

    private void startNextTest() {
        mHandler.postDelayed(new Runnable() {
            public void run() {
                if (mVoiceDialerTester == null) {
                    return;
                }
                if (!mVoiceDialerTester.stepToNextTest()) {
                    mVoiceDialerTester.report();
                    notifyText("Test completed!");
                    finish();
                    return;
                }
                File microphone = mVoiceDialerTester.getWavFile();
                File contacts = newFile(getArg(CONTACTS_EXTRA));

                notifyText("Testing\n" + microphone + "\n" + contacts);
                mCommandEngine.recognize(mCommandClient, VoiceDialerActivity.this,
                        microphone, SAMPLE_RATE);
            }
        }, 2000);
    }

    private int playSound(int toneType) {
        int msecDelay = 1;

        // use the MediaPlayer to prompt the user
        if (mToneGenerator != null) {
            mToneGenerator.startTone(toneType);
            msecDelay = StrictMath.max(msecDelay, 300);
        }

        // use the Vibrator to prompt the user
        if ((mAudioManager != null) &&
                (mAudioManager.shouldVibrate(AudioManager.VIBRATE_TYPE_RINGER))) {
            final int VIBRATOR_TIME = 150;
            final int VIBRATOR_GUARD_TIME = 150;
            Vibrator vibrator = new Vibrator();
            vibrator.vibrate(VIBRATOR_TIME);
            msecDelay = StrictMath.max(msecDelay,
                    VIBRATOR_TIME + VIBRATOR_GUARD_TIME);
        }

        return msecDelay;
    }

    @Override
    protected void onStop() {
        if (Config.LOGD) Log.d(TAG, "onStop");

        mAudioManager.abandonAudioFocus(null);

        // no more tester
        mVoiceDialerTester = null;

        // shut down recognizer and wait for the thread to complete
        if (mRecognizerThread !=  null) {
            mRecognizerThread.interrupt();
            try {
                mRecognizerThread.join();
            } catch (InterruptedException e) {
                if (Config.LOGD) Log.d(TAG, "onStop mRecognizerThread.join exception " + e);
            }
            mRecognizerThread = null;
        }

        // clean up UI
        mHandler.removeCallbacks(mMicFlasher);
        mHandler.removeMessages(0);

        // clean up ToneGenerator
        if (mToneGenerator != null) {
            mToneGenerator.release();
            mToneGenerator = null;
        }

        super.onStop();

        // It makes no sense to have this activity maintain state when in
        // background.  When it stops, it should just be destroyed.
        finish();
    }

    private void notifyText(final CharSequence msg) {
        Toast.makeText(VoiceDialerActivity.this, msg, Toast.LENGTH_SHORT).show();
    }

    private Runnable mMicFlasher = new Runnable() {
        int visible = View.VISIBLE;

        public void run() {
            findViewById(R.id.microphone_view).setVisibility(visible);
            findViewById(R.id.state).setVisibility(visible);
            visible = visible == View.VISIBLE ? View.INVISIBLE : View.VISIBLE;
            mHandler.postDelayed(this, 750);
        }
    };


    protected Dialog onCreateDialog(int id, Bundle args) {
        final Intent intents[] = (Intent[])args.getParcelableArray(INTENTS_KEY);

        DialogInterface.OnClickListener clickListener =
            new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                if (Config.LOGD) Log.d(TAG, "clickListener.onClick " + which);
                startActivityHelp(intents[which]);
                dismissDialog(DIALOG_ID);
                mAlertDialog = null;
                finish();
            }

        };

        DialogInterface.OnCancelListener cancelListener =
            new DialogInterface.OnCancelListener() {

            public void onCancel(DialogInterface dialog) {
                if (Config.LOGD) Log.d(TAG, "cancelListener.onCancel");
                dismissDialog(DIALOG_ID);
                mAlertDialog = null;
                finish();
            }

        };

        DialogInterface.OnClickListener positiveListener =
            new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                if (Config.LOGD) Log.d(TAG, "positiveListener.onClick " + which);
                if (intents.length == 1 && which == -1) which = 0;
                startActivityHelp(intents[which]);
                dismissDialog(DIALOG_ID);
                mAlertDialog = null;
                finish();
            }

        };

        DialogInterface.OnClickListener negativeListener =
            new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                if (Config.LOGD) Log.d(TAG, "negativeListener.onClick " + which);
                dismissDialog(DIALOG_ID);
                mAlertDialog = null;
                finish();
            }

        };

        String[] sentences = new String[intents.length];
        for (int i = 0; i < intents.length; i++) {
            sentences[i] = intents[i].getStringExtra(
                    RecognizerEngine.SENTENCE_EXTRA);
        }

        mAlertDialog = intents.length > 1 ?
                new AlertDialog.Builder(VoiceDialerActivity.this)
                .setTitle(R.string.title)
                .setItems(sentences, clickListener)
                .setOnCancelListener(cancelListener)
                .setNegativeButton(android.R.string.cancel, negativeListener)
                .show()
                :
                new AlertDialog.Builder(VoiceDialerActivity.this)
                .setTitle(R.string.title)
                .setItems(sentences, clickListener)
                .setOnCancelListener(cancelListener)
                .setPositiveButton(android.R.string.ok, positiveListener)
                .setNegativeButton(android.R.string.cancel, negativeListener)
                .show();

        return mAlertDialog;
    }

    private class CommandRecognizerClient implements RecognizerClient {
        static final int MIN_VOLUME_TO_SKIP = 2;
        /**
         * Called by the {@link RecognizerEngine} when the microphone is started.
         */
        public void onMicrophoneStart(InputStream mic) {
            if (Config.LOGD) Log.d(TAG, "onMicrophoneStart");
            playSound(ToneGenerator.TONE_PROP_BEEP);

            int ringVolume = mAudioManager.getStreamVolume(
                    AudioManager.STREAM_RING);
            Log.d(TAG, "ringVolume " + ringVolume);

            if (ringVolume >= MIN_VOLUME_TO_SKIP) {
                // now we're playing a sound, and corrupting the input sample.
                // So we need to pull that junk off of the input stream so that the
                // recognizer won't see it.
                try {
                    skipBeep(mic);
                } catch (java.io.IOException e) {
                    Log.e(TAG, "IOException " + e);
                }
            } else {
                Log.d(TAG, "no tone");
            }

            if (mVoiceDialerTester != null) return;

            mHandler.post(new Runnable() {
                public void run() {
                    findViewById(R.id.microphone_loading_view).setVisibility(View.INVISIBLE);
                    ((TextView)findViewById(R.id.state)).setText(R.string.listening);
                    mHandler.post(mMicFlasher);
                }
            });
        }

        /**
         *  Beep detection
         */
        private static final int START_WINDOW_MS = 500;  // Beep detection window duration in ms
        private static final int SINE_FREQ = 400;        // base sine frequency on beep
        private static final int NUM_PERIODS_BLOCK = 10; // number of sine periods in one energy averaging block
        private static final int THRESHOLD = 8;          // absolute pseudo energy threshold
        private static final int START = 0;              // beep detection start
        private static final int RISING = 1;             // beep rising edge start
        private static final int TOP = 2;                // beep constant energy detected

        void skipBeep(InputStream is) throws IOException {
            int sampleCount = ((SAMPLE_RATE / SINE_FREQ) * NUM_PERIODS_BLOCK);
            int blockSize = 2 * sampleCount; // energy averaging block

            if (is == null || blockSize == 0) {
                return;
            }

            byte[] buf = new byte[blockSize];
            int maxBytes = 2 * ((START_WINDOW_MS * SAMPLE_RATE) / 1000);
            maxBytes = ((maxBytes-1) / blockSize + 1) * blockSize;

            int count = 0;
            int state = START;  // detection state
            long prevE = 0; // previous pseudo energy
            long peak = 0;
            int threshold =  THRESHOLD*sampleCount;  // absolute energy threshold
            Log.d(TAG, "blockSize " + blockSize);

            while (count < maxBytes) {
                int cnt = 0;
                while (cnt < blockSize) {
                    int n = is.read(buf, cnt, blockSize-cnt);
                    if (n < 0) {
                        throw new java.io.IOException();
                    }
                    cnt += n;
                }

                // compute pseudo energy
                cnt = blockSize;
                long sumx = 0;
                long sumxx = 0;
                while (cnt >= 2) {
                    short smp = (short)((buf[cnt - 1] << 8) + (buf[cnt - 2] & 0xFF));
                    sumx += smp;
                    sumxx += smp*smp;
                    cnt -= 2;
                }
                long energy = (sumxx*sampleCount - sumx*sumx)/(sampleCount*sampleCount);
                Log.d(TAG, "sumx " + sumx + " sumxx " + sumxx + " ee " + energy);

                switch (state) {
                    case START:
                        if (energy > threshold && energy > (prevE * 2) && prevE != 0) {
                            // rising edge if energy doubled and > abs threshold
                            state = RISING;
                            if (Config.LOGD) Log.d(TAG, "start RISING: " + count +" time: "+ (((1000*count)/2)/SAMPLE_RATE));
                        }
                        break;
                    case RISING:
                        if (energy < threshold || energy < (prevE / 2)){
                            // energy fell back below half of previous, back to start
                            if (Config.LOGD) Log.d(TAG, "back to START: " + count +" time: "+ (((1000*count)/2)/SAMPLE_RATE));
                            peak = 0;
                            state = START;
                        } else if (energy > (prevE / 2) && energy < (prevE * 2)) {
                            // Start of constant energy
                            if (Config.LOGD) Log.d(TAG, "start TOP: " + count +" time: "+ (((1000*count)/2)/SAMPLE_RATE));
                            if (peak < energy) {
                                peak = energy;
                            }
                            state = TOP;
                        }
                        break;
                    case TOP:
                        if (energy < threshold || energy < (peak / 2)) {
                            // e went to less than half of the peak
                            if (Config.LOGD) Log.d(TAG, "end TOP: " + count +" time: "+ (((1000*count)/2)/SAMPLE_RATE));
                            return;
                        }
                        break;
                    }
                prevE = energy;
                count += blockSize;
            }
            if (Config.LOGD) Log.d(TAG, "no beep detected, timed out");
        }

        /**
         * Called by the {@link RecognizerEngine} if the recognizer fails.
         */
        public void onRecognitionFailure(final String msg) {
            if (Config.LOGD) Log.d(TAG, "onRecognitionFailure " + msg);

            // get work off UAPI thread
            mHandler.post(new Runnable() {
                public void run() {
                    // failure, so beep about it
                    playSound(ToneGenerator.TONE_PROP_NACK);

                    mHandler.removeCallbacks(mMicFlasher);
                    ((TextView)findViewById(R.id.state)).setText(R.string.please_try_again);
                    findViewById(R.id.state).setVisibility(View.VISIBLE);
                    findViewById(R.id.microphone_view).setVisibility(View.INVISIBLE);
                    findViewById(R.id.retry_view).setVisibility(View.VISIBLE);

                    if (mVoiceDialerTester != null) {
                        mVoiceDialerTester.onRecognitionFailure(msg);
                        startNextTest();
                        return;
                    }

                    mHandler.postDelayed(new Runnable() {
                        public void run() {
                            finish();
                        }
                    }, FAIL_PAUSE_MSEC);
                }
            });
        }

        /**
         * Called by the {@link RecognizerEngine} on an internal error.
         */
        public void onRecognitionError(final String msg) {
            if (Config.LOGD) Log.d(TAG, "onRecognitionError " + msg);

            // get work off UAPI thread
            mHandler.post(new Runnable() {
                public void run() {
                    // error, so beep about it
                    playSound(ToneGenerator.TONE_PROP_NACK);

                    mHandler.removeCallbacks(mMicFlasher);
                    ((TextView)findViewById(R.id.state)).setText(R.string.please_try_again);
                    ((TextView)findViewById(R.id.substate)).setText(R.string.recognition_error);
                    findViewById(R.id.state).setVisibility(View.VISIBLE);
                    findViewById(R.id.microphone_view).setVisibility(View.INVISIBLE);
                    findViewById(R.id.retry_view).setVisibility(View.VISIBLE);

                    if (mVoiceDialerTester != null) {
                        mVoiceDialerTester.onRecognitionError(msg);
                        startNextTest();
                        return;
                    }

                    mHandler.postDelayed(new Runnable() {
                        public void run() {
                            finish();
                        }
                    }, FAIL_PAUSE_MSEC);
                }
            });
        }

        /**
         * Called by the {@link RecognizerEngine} when is succeeds.  If there is
         * only one item, then the Intent is dispatched immediately.
         * If there are more, then an AlertDialog is displayed and the user is
         * prompted to select.
         * @param intents a list of Intents corresponding to the sentences.
         */
        public void onRecognitionSuccess(final Intent[] intents) {
            if (Config.LOGD) Log.d(TAG, "onRecognitionSuccess " + intents.length);

            // Pull any intents that are not valid to display in a dialog or
            // call "startActivity" with.
            // ACTION_RECOGNIZER_RESULT intents are only used when in Bluetooth
            // mode, to control the behavior of the voicedialer app, rather
            // than to actually place calls or open apps.
            int runnableCount = 0;
            for (int i=0; i < intents.length; i++) {
                if (!RecognizerEngine.ACTION_RECOGNIZER_RESULT.equals(
                        intents[i].getAction())) {
                    runnableCount++;
                }
            }
            Intent runnableIntents[] = new Intent[runnableCount];
            int j = 0;
            for (int i=0; i < intents.length; i++) {
                if (!RecognizerEngine.ACTION_RECOGNIZER_RESULT.equals(
                        intents[i].getAction())) {
                    runnableIntents[j] = intents[i];
                    j++;
                }
            }

            if (runnableIntents.length == 0) {
                // no usable intents
                onRecognitionFailure("No displayable intents");
                return;
            }
            // repackage our intents as a bundle so that we can pass it into
            // showDialog.  This in required so that we can handle it when
            // orientation changes and the activity is destroyed and recreated.
            final Bundle args = new Bundle();
            args.putParcelableArray(INTENTS_KEY, runnableIntents);

            mHandler.post(new Runnable() {

                public void run() {
                    // success, so beep about it
                    playSound(ToneGenerator.TONE_PROP_ACK);

                    mHandler.removeCallbacks(mMicFlasher);

                    showDialog(DIALOG_ID, args);

                    // start the next test
                    if (mVoiceDialerTester != null) {
                        mVoiceDialerTester.onRecognitionSuccess(intents);
                        startNextTest();
                        mHandler.postDelayed(new Runnable() {
                            public void run() {
                                dismissDialog(DIALOG_ID);
                                mAlertDialog = null;
                            }
                        }, 2000);
                    }
                }
            });
        }
    }

    // post a Toast if not real contacts or microphone
    private void startActivityHelp(Intent intent) {
        if (getArg(MICROPHONE_EXTRA) == null &&
                getArg(CONTACTS_EXTRA) == null) {
            startActivity(intent);
        } else {
            notifyText(intent.
                    getStringExtra(RecognizerEngine.SENTENCE_EXTRA) +
                    "\n" + intent.toString());
        }

    }
    @Override
    protected void onDestroy() {
        if (Config.LOGD) Log.d(TAG, "onDestroy");
        super.onDestroy();
    }
}
