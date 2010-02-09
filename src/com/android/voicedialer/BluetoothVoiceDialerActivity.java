/*
 *
 * Copyright (C) 2010 The Android Open Source Project
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
import android.bluetooth.BluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.DialogInterface;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Config;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;
//import com.android.voicedialer.VoiceDialerTester;
import java.io.File;
import java.util.Locale;
import java.util.HashMap;

/**
 * This class is the user interface of the VoiceDialer application.
 * Its life cycle is as follows:
 * TODO: Document the state transitions in this activity
 * TODO: make this work when the lock screen is up
 * TODO: add handling for choosing a person when multiple results come back
 * TODO: fix audio routing to go through the bluetooth headset
 * TODO: wait for the TTS utterance to complete before making the call
 * TODO: improve the user interface that is displayed
 * TODO: handle it if TTS cannot be initialized
 *
 * <ul>
 * <li>The user presses the recognize key, and the BluetoothVoiceDialerActivity starts.
 * <li>A {@link RecognizerEngine} instance is created.
 * <li>The RecognizerEngine signals the user to speak with the Vibrator.
 * <li>The RecognizerEngine captures, processes, and recognizes speech
 * against the names in the contact list.
 * <li>The RecognizerEngine calls onRecognitionSuccess with a list of
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
public class BluetoothVoiceDialerActivity extends Activity {

    private static final String TAG = "VoiceDialerActivity";

    private static final String MICROPHONE_EXTRA = "microphone";
    private static final String CONTACTS_EXTRA = "contacts";
    private static final String TONE_EXTRA = "tone";

    private static final int FAIL_PAUSE_MSEC = 2000;
    private static final int CALL_PAUSE_MSEC = 2000;
    private static final int SAMPLE_RATE = 8000;
    private static final boolean LOGD = true;


    private static final CommandRecognizerEngine mCommandEngine =
            new CommandRecognizerEngine();
    private static final YesNoRecognizerEngine mYesNoEngine =
            new YesNoRecognizerEngine();
    private CommandRecognizerClient mCommandClient;
    private RetryRecognizerClient mRetryClient;
    private VoiceDialerTester mVoiceDialerTester;
    private Handler mHandler;
    private Thread mRecognizerThread = null;
    private AudioManager mAudioManager;
    private ToneGenerator mToneGenerator;
    private BluetoothHeadset mBluetoothHeadset;
    private TextToSpeech mTts;
    private HashMap<String, String> mTtsParams;
    private boolean mTtsAvailable;
    private VoiceDialerBroadcastReceiver mReceiver;
    private int mBluetoothAudioState;
    private boolean mWaitingForScoConnection;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        if (LOGD) Log.d(TAG, "onCreate");
        mHandler = new Handler();
        mAudioManager = (AudioManager)getSystemService(AUDIO_SERVICE);

        // tell music player to shut up so we can hear
        Intent i = new Intent("com.android.music.musicservicecommand");
        i.putExtra("command", "pause");
        sendBroadcast(i);

        // set up ToneGenerator
        // currently disabled because it crashes audio input
        mToneGenerator = !"0".equals(getArg(TONE_EXTRA)) ?
                new ToneGenerator(AudioManager.STREAM_RING, ToneGenerator.MAX_VOLUME) :
                null;

        // open main window
        setTheme(android.R.style.Theme_Dialog);
        setTitle(R.string.bluetooth_title);
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

        // set up ToneGenerator
        mToneGenerator = !"0".equals(getArg(TONE_EXTRA)) ?
                new ToneGenerator(AudioManager.STREAM_VOICE_CALL, ToneGenerator.MAX_VOLUME) :
                null;

        // Get handle to BluetoothHeadset object if required
        IntentFilter audioStateFilter;
        audioStateFilter = new IntentFilter();
        audioStateFilter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
        mReceiver = new VoiceDialerBroadcastReceiver();
        registerReceiver(mReceiver, audioStateFilter);

        mCommandEngine.setContactsFile(newFile(getArg(CONTACTS_EXTRA)));
        mCommandClient = new CommandRecognizerClient();
        mRetryClient = new RetryRecognizerClient();

        mBluetoothAudioState = BluetoothHeadset.STATE_ERROR;
        if (!BluetoothHeadset.DISABLE_BT_VOICE_DIALING &&
                Intent.ACTION_VOICE_COMMAND.equals(getIntent().getAction())) {
            // we can't start recognizing until we get connected to the BluetoothHeadset
            // and have an connected audio state.  We will listen for these
            // states to change.
            mBluetoothHeadset = new BluetoothHeadset(this, mBluetoothHeadsetServiceListener);

            mWaitingForScoConnection = true;

            // initialize the text to speech system
            mTtsAvailable = false;
            mTts = new TextToSpeech(this, new TtsInitListener());
            mTtsParams = new HashMap<String, String>();
            // TODO: direct TTS to the correct audio type.  Right now, if I specify
            // STREAM_VOICE_CALL, no audio plays whatsoever.
//            mTtsParams.put(TextToSpeech.Engine.KEY_PARAM_STREAM,
//                    String.valueOf(AudioManager.STREAM_VOICE_CALL));
        }
    }

    class TtsInitListener implements TextToSpeech.OnInitListener {
        public void onInit(int status) {
            // status can be either TextToSpeech.SUCCESS or TextToSpeech.ERROR.
            if (Config.LOGD) Log.d(TAG, "onInit for tts");
            if (status == TextToSpeech.SUCCESS) {
                // Set preferred language to US english.
                // Note that a language may not be available, and the result will
                // indicate this.
                if (mTts == null) {
                    Log.e(TAG, "null tts");
                } else {
                    int result = mTts.setLanguage(Locale.US);
                    if (result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED) {
                       // Lanuage data is missing or the language is not supported.
                        Log.e(TAG, "Language is not available.");
                    } else {
                        // TODO: check for other possible result codes.
                        // The TTS engine has been successfully initialized.
                        mTtsAvailable = true;

                        if (Config.LOGD) Log.d(TAG, "Tts initialized");
                    }
                }
            } else {
                // Initialization failed.
                Log.e(TAG, "Could not initialize TextToSpeech.");
            }
        }
    }

    private BluetoothHeadset.ServiceListener mBluetoothHeadsetServiceListener =
            new BluetoothHeadset.ServiceListener() {
        public void onServiceConnected() {
            if (mBluetoothHeadset != null &&
                    mBluetoothHeadset.getState() == BluetoothHeadset.STATE_CONNECTED) {
                mBluetoothHeadset.startVoiceRecognition();
            }

            if (LOGD) Log.d(TAG, "onServiceConnected");
        }
        public void onServiceDisconnected() {}
    };

    private class VoiceDialerBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)) {
                mBluetoothAudioState = intent.getIntExtra(BluetoothHeadset.EXTRA_AUDIO_STATE,
                                               BluetoothHeadset.STATE_ERROR);
                if (LOGD) Log.d(TAG, "HEADSET AUDIO_STATE_CHANGED -> " +
                        mBluetoothAudioState);

                if (mBluetoothAudioState == BluetoothHeadset.AUDIO_STATE_CONNECTED &&
                    mWaitingForScoConnection) {
                    //playSound(ToneGenerator.TONE_PROP_BEEP);
                    // SCO channel has just become available.
                    mWaitingForScoConnection = false;
                    mHandler.post(new Runnable() {
                        public void run() {
                            listenForCommand();
                        }
                    });
                }
            }
        }
    }


    private class CommandRecognizerClient implements RecognizerClient {

        /**
         * Called by the {@link RecognizerEngine} when the microphone is started.
         */
        public void onMicrophoneStart() {
            if (LOGD) Log.d(TAG, "onMicrophoneStart");

            if (mVoiceDialerTester != null) return;

            mHandler.post(new Runnable() {
                public void run() {
                    if (mTtsAvailable) {
                        mTts.speak(getString(R.string.speak_now_tts),
                        TextToSpeech.QUEUE_FLUSH,
                        mTtsParams);
                    }

                    findViewById(R.id.microphone_loading_view).setVisibility(View.INVISIBLE);
                    ((TextView)findViewById(R.id.state)).setText(R.string.listening);
                    mHandler.post(mMicFlasher);
                }
            });
        }


        /**
         * Called by the {@link RecognizerEngine} if the recognizer fails.
         */
        public void onRecognitionFailure(final String msg) {
            if (LOGD) Log.d(TAG, "onRecognitionFailure " + msg);

            // get work off UAPI thread
            mHandler.post(new Runnable() {
                public void run() {
                    // failure, so beep about it
                    //playSound(ToneGenerator.TONE_PROP_NACK);

                    mTts.speak(getString(R.string.no_results_tts),
                        TextToSpeech.QUEUE_FLUSH,
                        mTtsParams);

                    mHandler.removeCallbacks(mMicFlasher);
                    ((TextView)findViewById(R.id.state)).setText(R.string.please_try_again);
                    findViewById(R.id.state).setVisibility(View.VISIBLE);
                    findViewById(R.id.microphone_view).setVisibility(View.INVISIBLE);
                    findViewById(R.id.retry_view).setVisibility(View.VISIBLE);

                    listenForRetry();

                    if (mVoiceDialerTester != null) {
                        mVoiceDialerTester.onRecognitionFailure(msg);
                        startNextTest();
                        return;
                    }
                }
            });
        }

        /**
         * Called by the {@link RecognizerEngine} on an internal error.
         */
        public void onRecognitionError(final String msg) {
            if (LOGD) Log.d(TAG, "onRecognitionError " + msg);

            // get work off UAPI thread
            mHandler.post(new Runnable() {
                public void run() {
                    // error, so beep about it
                    //playSound(ToneGenerator.TONE_PROP_NACK);

                    mTts.speak(getString(R.string.error_tts),
                        TextToSpeech.QUEUE_FLUSH,
                        mTtsParams);

                    mHandler.removeCallbacks(mMicFlasher);
                    ((TextView)findViewById(R.id.state)).setText(R.string.please_try_again);
                    ((TextView)findViewById(R.id.substate)).setText(R.string.recognition_error);
                    findViewById(R.id.state).setVisibility(View.VISIBLE);
                    findViewById(R.id.microphone_view).setVisibility(View.INVISIBLE);
                    findViewById(R.id.retry_view).setVisibility(View.VISIBLE);

                    listenForRetry();

                    if (mVoiceDialerTester != null) {
                        mVoiceDialerTester.onRecognitionError(msg);
                        startNextTest();
                        return;
                    }
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
            if (LOGD) Log.d(TAG, "onRecognitionSuccess " + intents.length);

            mHandler.post(new Runnable() {
                public void run() {
                    // success, so beep about it
                    //playSound(ToneGenerator.TONE_PROP_ACK);

                    mHandler.removeCallbacks(mMicFlasher);

                    String[] sentences = new String[intents.length];
                    for (int i = 0; i < intents.length; i++) {
                        sentences[i] = intents[i].getStringExtra(
                                RecognizerEngine.SENTENCE_EXTRA);
                    }

                    if (intents.length == 1 && mVoiceDialerTester == null) {
                        // only one match, just say who we're going to call
                        // and then do it.
                        mTts.speak(sentences[0],
                            TextToSpeech.QUEUE_FLUSH,
                            mTtsParams);
                        // TODO: wait for utterance to finish
                        // example:
                        // mTtsParams.put(TextToSpeech.Engine.KEY_UTTERANCE_ID, "finshed string");
                        startActivityHelp(intents[0]);
                        mHandler.postDelayed(new Runnable() {
                            public void run() {
                                finish();
                            }
                        }, CALL_PAUSE_MSEC);

                        return;
                    } else {
                            // multiple matches, speak each of them.
                            mTts.speak(getString(R.string.choose_person_tts),
                                       TextToSpeech.QUEUE_FLUSH,
                                       mTtsParams);
                            for (int i=0; i < sentences.length; i++) {
                                String sentence = Integer.toString(i+1) + ". " + sentences[i] + ".";
                                mTts.speak(sentence,
                                           TextToSpeech.QUEUE_ADD,
                                           mTtsParams);
                            }
                            // TODO: listen for choice

                            DialogInterface.OnClickListener clickListener =
                                new DialogInterface.OnClickListener() {

                                public void onClick(DialogInterface dialog, int which) {
                                    if (LOGD) Log.d(TAG, "clickListener.onClick " + which);
                                    startActivityHelp(intents[which]);
                                    dialog.dismiss();
                                    finish();
                                }
                            };

                            DialogInterface.OnCancelListener cancelListener =
                                new DialogInterface.OnCancelListener() {

                                public void onCancel(DialogInterface dialog) {
                                    if (LOGD) Log.d(TAG, "cancelListener.onCancel");
                                    dialog.dismiss();
                                    finish();
                                }

                           };

                            DialogInterface.OnClickListener positiveListener =
                                new DialogInterface.OnClickListener() {

                                public void onClick(DialogInterface dialog, int which) {
                                    if (LOGD) Log.d(TAG, "positiveListener.onClick " + which);
                                    if (intents.length == 1 && which == -1) which = 0;
                                    startActivityHelp(intents[which]);
                                    dialog.dismiss();
                                    finish();
                                }

                            };

                            DialogInterface.OnClickListener negativeListener =
                                new DialogInterface.OnClickListener() {

                                public void onClick(DialogInterface dialog, int which) {
                                    if (LOGD) Log.d(TAG, "negativeListener.onClick " + which);
                                    dialog.dismiss();
                                    finish();
                                }

                            };

                            final AlertDialog alertDialog = intents.length > 1 ?
                                    new AlertDialog.Builder(BluetoothVoiceDialerActivity.this)
                                    .setTitle(R.string.title)
                                    .setItems(sentences, clickListener)
                                    .setOnCancelListener(cancelListener)
                                    .setNegativeButton(android.R.string.cancel, negativeListener)
                                    .show()
                                    :
                                    new AlertDialog.Builder(BluetoothVoiceDialerActivity.this)
                                    .setTitle(R.string.title)
                                    .setItems(sentences, clickListener)
                                    .setOnCancelListener(cancelListener)
                                    .setPositiveButton(android.R.string.ok, positiveListener)
                                    .setNegativeButton(android.R.string.cancel, negativeListener)
                                    .show();

                            // start the next test
                            if (mVoiceDialerTester != null) {
                                mVoiceDialerTester.onRecognitionSuccess(intents);
                                startNextTest();
                                mHandler.postDelayed(new Runnable() {
                                    public void run() {
                                        alertDialog.dismiss();
                                    }
                                }, 2000);
                            }
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
            });
        }
    }

    private class RetryRecognizerClient implements RecognizerClient {
        public void onRecognitionSuccess(final Intent[] intents) {
            Log.d(TAG, "RetryRecognizerClient onRecognitionSuccess");
            // disregard all but the first intent.
            if (intents.length > 0) {
                String sentence = intents[0].getStringExtra(
                    RecognizerEngine.SEMANTIC_EXTRA);
                if ("1".equalsIgnoreCase(sentence)) {
                    // user wants to retry.
                    listenForCommand();
                } else {
                    Log.d(TAG, "RetryRecognizerClient onRecognitionFailure");
                    mTts.speak(getString(R.string.goodbye_tts),
                        TextToSpeech.QUEUE_FLUSH,
                        mTtsParams);
                    mHandler.postDelayed(new Runnable() {
                        public void run() {
                            finish();
                        }
                    }, FAIL_PAUSE_MSEC);
                }
            }
        }

        public void onRecognitionFailure(String msg) {
            Log.d(TAG, "RetryRecognizerClient onRecognitionFailure");
            mTts.speak(getString(R.string.goodbye_tts),
                TextToSpeech.QUEUE_FLUSH,
                mTtsParams);
            mHandler.postDelayed(new Runnable() {
                public void run() {
                    finish();
                }
            }, FAIL_PAUSE_MSEC);
        }

        public void onRecognitionError(String err) {
            Log.d(TAG, "RetryRecognizerClient onRecognitionError");
            mTts.speak(getString(R.string.goodbye_tts),
                TextToSpeech.QUEUE_FLUSH,
                mTtsParams);
            mHandler.postDelayed(new Runnable() {
                public void run() {
                    finish();
                }
            }, FAIL_PAUSE_MSEC);
        }

        public void onMicrophoneStart() {
            Log.d(TAG, "YesNoRecognizerClient onMicrophoneStart");
        }
    }

    private void listenForCommand() {
        if (Config.LOGD) Log.d(TAG, "listenForCommand(): MICROPHONE_EXTRA: "+getArg(MICROPHONE_EXTRA)+
                ", CONTACTS_EXTRA: "+getArg(CONTACTS_EXTRA)+
                ", TONE_EXTRA: "+getArg(TONE_EXTRA));


        mRecognizerThread = new Thread() {
            public void run() {
                mCommandEngine.recognize(mCommandClient,
                        BluetoothVoiceDialerActivity.this,
                        newFile(getArg(MICROPHONE_EXTRA)),
                        SAMPLE_RATE);
            }

        };
        mRecognizerThread.start();
    }

    private void listenForRetry() {
        if (Config.LOGD) Log.d(TAG, "listenForRetry(): MICROPHONE_EXTRA: "+getArg(MICROPHONE_EXTRA)+
                ", TONE_EXTRA: "+getArg(TONE_EXTRA));

        mRecognizerThread = new Thread() {
            public void run() {
                mYesNoEngine.recognize(mRetryClient,
                        BluetoothVoiceDialerActivity.this,
                        newFile(getArg(MICROPHONE_EXTRA)), SAMPLE_RATE);
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
                mCommandEngine.recognize(new CommandRecognizerClient(), BluetoothVoiceDialerActivity.this,
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
        if ((mAudioManager != null) && (mAudioManager.shouldVibrate(AudioManager.VIBRATE_TYPE_RINGER))) {
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
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (LOGD) Log.d(TAG, "onPause");

        // shut down bluetooth, if it exists
        if (mBluetoothHeadset != null) {
            mBluetoothHeadset.stopVoiceRecognition();
            mBluetoothHeadset.close();
            mBluetoothHeadset = null;
        }

        // no more tester
        mVoiceDialerTester = null;

        // shut down recognizer and wait for the thread to complete
        if (mRecognizerThread !=  null) {
            mRecognizerThread.interrupt();
            try {
                mRecognizerThread.join();
            } catch (InterruptedException e) {
                if (LOGD) Log.d(TAG, "onPause mRecognizerThread.join exception " + e);
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

        // bye
        finish();
    }

    private void notifyText(final CharSequence msg) {
        Toast.makeText(BluetoothVoiceDialerActivity.this, msg, Toast.LENGTH_SHORT).show();
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

    @Override
    protected void onDestroy() {
        // Don't forget to shutdown!
        if (mTts != null) {
            mTts.stop();
            mTts.shutdown();
            mTts = null;
        }
        unregisterReceiver(mReceiver);

        super.onDestroy();
    }

    private static class VoiceDialerTester {
        public VoiceDialerTester(File f) {
        }

        public boolean stepToNextTest() {
            return false;
        }

        public void report() {
        }

        public File getWavFile() {
            return null;
        }

        public void onRecognitionFailure(String msg) {
        }

        public void onRecognitionError(String err) {
        }

        public void onRecognitionSuccess(Intent[] intents) {
        }
    }

}
