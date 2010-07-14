/*
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
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Config;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;

import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

/**
 * TODO: get rid of the anonymous classes
 *
 * This class is the user interface of the BluetoothVoiceDialer application.
 * It begins in the INITIALIZING state.
 *
 * INITIALIZING :
 *  This transitions out on events from TTS and the BluetoothHeadset
 *   once TTS initialized and SCO channel set up:
 *     * prompt the user "speak now"
 *     * transition to the SPEAKING_GREETING state
 *
 * SPEAKING_GREETING:
 *  This transitions out only on events from TTS or the fallback runnable
 *   once the greeting utterance completes:
 *     * begin listening for the command using the {@link CommandRecognizerEngine}
 *     * transition to the WAITING_FOR_COMMAND state
 *
 * WAITING_FOR_COMMAND :
 * This transitions out only on events from the recognizer
 *   on RecognitionFailure or RecognitionError:
 *     * begin speaking "try again."
 *     * remain in state SPEAKING_TRY_AGAIN
 *   on RecognitionSuccess:
 *     single result:
 *       * begin speaking the sentence describing the intent
 *       * transition to the SPEAKING_CHOSEN_ACTION
 *     multiple results:
 *       * begin speaking each of the choices in order
 *       * transition to the SPEAKING_CHOICES state
 *
 * SPEAKING_TRY_AGAIN:
 * This transitions out only on events from TTS or the fallback runnable
 *   once the try again utterance completes:
 *     * begin listening for the command using the {@link CommandRecognizerEngine}
 *     * transition to the LISTENING_FOR_COMMAND state
 *
 * SPEAKING_CHOSEN_ACTION:
 *  This transitions out only on events from TTS or the fallback runnable
 *   once the utterance completes:
 *     * dispatch the intent that was chosen
 *     * transition to the EXITING state
 *     * finish the activity
 *
 * SPEAKING_CHOICES:
 *  This transitions out only on events from TTS or the fallback runnable
 *   once the utterance completes:
 *     * begin listening for the user's choice using the
 *         {@link PhoneTypeChoiceRecognizerEngine}
 *     * transition to the WAITING_FOR_CHOICE state.
 *
 * WAITING_FOR_CHOICE:
 *  This transitions out only on events from the recognizer
 *   on RecognitionFailure or RecognitionError:
 *     * begin speaking the "invalid choice" message, along with the list
 *       of choices
 *     * transition to the SPEAKING_CHOICES state
 *   on RecognitionSuccess:
 *     if the result is "try again", prompt the user to say a command, begin
 *       listening for the command, and transition back to the WAITING_FOR_COMMAND
 *       state.
 *     if the result is "exit", then being speaking the "goodbye" message and
 *       transition to the SPEAKING_GOODBYE state.
 *     if the result is a valid choice, begin speaking the action chosen,initiate
 *       the command the user has choose and exit.
 *     if not a valid choice, speak the "invalid choice" message, begin
 *       speaking the choices in order again, transition to the
 *       SPEAKING_CHOICES
 *
 * SPEAKING_GOODBYE:
 *  This transitions out only on events from TTS or the fallback runnable
 *   after a time out, finish the activity.
 *
 */

public class BluetoothVoiceDialerActivity extends Activity {

    private static final String TAG = "VoiceDialerActivity";

    private static final String MICROPHONE_EXTRA = "microphone";
    private static final String CONTACTS_EXTRA = "contacts";

    private static final String SPEAK_NOW_UTTERANCE = "speak_now";
    private static final String TRY_AGAIN_UTTERANCE = "try_again";
    private static final String CHOSEN_ACTION_UTTERANCE = "chose_action";
    private static final String GOODBYE_UTTERANCE = "goodbye";
    private static final String CHOICES_UTTERANCE = "choices";

    private static final int FIRST_UTTERANCE_DELAY = 300;
    private static final int MAX_TTS_DELAY = 6000;

    private static final int SAMPLE_RATE = 8000;

    private static final int INITIALIZING = 0;
    private static final int SPEAKING_GREETING = 1;
    private static final int WAITING_FOR_COMMAND = 2;
    private static final int SPEAKING_TRY_AGAIN = 3;
    private static final int SPEAKING_CHOICES = 4;
    private static final int WAITING_FOR_CHOICE = 5;
    private static final int SPEAKING_CHOSEN_ACTION = 6;
    private static final int SPEAKING_GOODBYE = 7;
    private static final int EXITING = 8;

    private static final CommandRecognizerEngine mCommandEngine =
            new CommandRecognizerEngine();
    private static final PhoneTypeChoiceRecognizerEngine mPhoneTypeChoiceEngine =
            new PhoneTypeChoiceRecognizerEngine();
    private CommandRecognizerClient mCommandClient;
    private ChoiceRecognizerClient mChoiceClient;
    private ToneGenerator mToneGenerator;
    private Handler mHandler;
    private Thread mRecognizerThread = null;
    private AudioManager mAudioManager;
    private BluetoothHeadset mBluetoothHeadset;
    private TextToSpeech mTts;
    private HashMap<String, String> mTtsParams;
    private VoiceDialerBroadcastReceiver mReceiver;
    private int mBluetoothAudioState;
    private boolean mWaitingForTts;
    private boolean mWaitingForScoConnection;
    private Intent[] mAvailableChoices;
    private Intent mChosenAction;
    private int mBluetoothVoiceVolume;
    private int mState;
    private AlertDialog mAlertDialog;
    private Runnable mFallbackRunnable;

    private WakeLock mWakeLock;

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
        if (Config.LOGD) Log.d(TAG, "onStart " + getIntent());
        super.onStart();

        acquireWakeLock(this);

        mState = INITIALIZING;
        mChosenAction = null;
        mAudioManager.requestAudioFocus(
                null, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);

        // set this flag so this activity will stay in front of the keyguard
        int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
        getWindow().addFlags(flags);

        // open main window
        setTheme(android.R.style.Theme_Dialog);
        setTitle(R.string.bluetooth_title);
        setContentView(R.layout.voice_dialing);
        findViewById(R.id.microphone_view).setVisibility(View.INVISIBLE);
        findViewById(R.id.retry_view).setVisibility(View.INVISIBLE);
        findViewById(R.id.microphone_loading_view).setVisibility(View.VISIBLE);
        if (RecognizerLogger.isEnabled(this)) {
            ((TextView) findViewById(R.id.substate)).setText(R.string.logging_enabled);
        }

        // Get handle to BluetoothHeadset object
        IntentFilter audioStateFilter;
        audioStateFilter = new IntentFilter();
        audioStateFilter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
        mReceiver = new VoiceDialerBroadcastReceiver();
        registerReceiver(mReceiver, audioStateFilter);

        mCommandEngine.setContactsFile(newFile(getArg(CONTACTS_EXTRA)));
        mCommandEngine.setMinimizeResults(true);
        mCommandEngine.setAllowOpenEntries(false);
        mCommandClient = new CommandRecognizerClient();
        mChoiceClient = new ChoiceRecognizerClient();

        mBluetoothAudioState = BluetoothHeadset.STATE_ERROR;

        if (BluetoothHeadset.isBluetoothVoiceDialingEnabled(this)) {
            // we can't start recognizing until we get connected to the BluetoothHeadset
            // and have an connected audio state.  We will listen for these
            // states to change.
            mWaitingForScoConnection = true;
            mBluetoothHeadset = new BluetoothHeadset(this,
                    mBluetoothHeadsetServiceListener);
            // initialize the text to speech system
            mWaitingForTts = true;
            mTts = new TextToSpeech(this, new TtsInitListener());
            mTtsParams = new HashMap<String, String>();
            mTtsParams.put(TextToSpeech.Engine.KEY_PARAM_STREAM,
                    String.valueOf(AudioManager.STREAM_BLUETOOTH_SCO));
        } else {
            // bluetooth voice dialing is disabled, just exit
            finish();
        }
    }

    class ErrorRunnable implements Runnable {
        private int mErrorMsg;
        public ErrorRunnable(int errorMsg) {
            mErrorMsg = errorMsg;
        }

        public void run() {
            // put up an error and exit
            mHandler.removeCallbacks(mMicFlasher);
            ((TextView)findViewById(R.id.state)).setText(R.string.failure);
            ((TextView)findViewById(R.id.substate)).setText(mErrorMsg);
            ((TextView)findViewById(R.id.substate)).setText(
                    R.string.headset_connection_lost);
            findViewById(R.id.microphone_view).setVisibility(View.INVISIBLE);
            findViewById(R.id.retry_view).setVisibility(View.VISIBLE);

            playSound(ToneGenerator.TONE_PROP_NACK);
        }
    }

    class FallbackRunnable implements Runnable {
        public void run() {
            Log.e(TAG, "utterance completion not delivered, using fallback");
            // This runnable is intended as a fallback to transition to
            // the next state is for some reason we never get a
            // TTS utterance completion.  It will behave just the same
            // as if we had received utterance completion.
            onSpeechCompletion();
        }
    }

    class GreetingRunnable implements Runnable {
        public void run() {
            mState = SPEAKING_GREETING;
            mTtsParams.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,
                    SPEAK_NOW_UTTERANCE);
            mTts.speak(getString(R.string.speak_now_tts),
                TextToSpeech.QUEUE_FLUSH,
                mTtsParams);
            // Normally, the we will begin listening for the command after the
            // utterance completes.  As a fallback in case the utterance
            // does not complete, post a delayed runnable to fire
            // the intent.
            mFallbackRunnable = new FallbackRunnable();
            mHandler.postDelayed(mFallbackRunnable, MAX_TTS_DELAY);
        }
    }

    class TtsInitListener implements TextToSpeech.OnInitListener {
        public void onInit(int status) {
            // status can be either TextToSpeech.SUCCESS or TextToSpeech.ERROR.
            if (Config.LOGD) Log.d(TAG, "onInit for tts");
            if (status != TextToSpeech.SUCCESS) {
                // Initialization failed.
                Log.e(TAG, "Could not initialize TextToSpeech.");
                mHandler.post(new ErrorRunnable(R.string.recognition_error));
                exitActivity();
                return;
            }

            if (mTts == null) {
                Log.e(TAG, "null tts");
                mHandler.post(new ErrorRunnable(R.string.recognition_error));
                exitActivity();
                return;
            }

            // The TTS engine has been successfully initialized.
            mWaitingForTts = false;

            mTts.setOnUtteranceCompletedListener(new OnUtteranceCompletedListener());
            // TTS over bluetooth is really loud,
            // store the current volume away, and then turn it down.
            // we will restore it in onStop.
            // Limit volume to -18dB. Stream volume range represents approximately 50dB
            // (See AudioSystem.cpp linearToLog()) so the number of steps corresponding
            // to 18dB is 18 / (50 / maxSteps).
            mBluetoothVoiceVolume = mAudioManager.getStreamVolume(
                    AudioManager.STREAM_BLUETOOTH_SCO);
            int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_BLUETOOTH_SCO);
            int volume = maxVolume - ((18 / (50/maxVolume)) + 1);
            if (mBluetoothVoiceVolume > volume) {
                mAudioManager.setStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO, volume, 0);
            }

            if (mWaitingForScoConnection) {
                // the bluetooth connection is not up yet, still waiting.
            } else {
                // we now have SCO connection and TTS, so we can start.
                mHandler.postDelayed(new GreetingRunnable(), FIRST_UTTERANCE_DELAY);
            }
        }
    }

    class OnUtteranceCompletedListener
            implements TextToSpeech.OnUtteranceCompletedListener {
        public void onUtteranceCompleted(String utteranceId) {
            Log.d(TAG, "onUtteranceCompleted " + utteranceId);
            // since the utterance has completed, we no longer need the fallback.
            mHandler.removeCallbacks(mFallbackRunnable);
            mFallbackRunnable = null;
            mHandler.post(new Runnable() {
                public void run() {
                    onSpeechCompletion();
                }
            });
        }
    }

    private void onSpeechCompletion() {
        if (mState == SPEAKING_GREETING || mState == SPEAKING_TRY_AGAIN) {
            listenForCommand();
        } else if (mState == SPEAKING_CHOICES) {
            listenForChoice();
        } else if (mState == SPEAKING_GOODBYE) {
            mState = EXITING;
            finish();
        } else if (mState == SPEAKING_CHOSEN_ACTION) {
            mState = EXITING;
            startActivityHelp(mChosenAction);
            finish();
        }
    }

    private BluetoothHeadset.ServiceListener mBluetoothHeadsetServiceListener =
            new BluetoothHeadset.ServiceListener() {
        public void onServiceConnected() {
            if (mBluetoothHeadset != null) {
                BluetoothDevice device = mBluetoothHeadset.getCurrentHeadset();
                if (mBluetoothHeadset.getState(device) == BluetoothHeadset.STATE_CONNECTED) {
                    mBluetoothHeadset.startVoiceRecognition();
                }
            }

            if (Config.LOGD) Log.d(TAG, "onServiceConnected");
        }
        public void onServiceDisconnected() {}
    };

    private class VoiceDialerBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)) {
                mBluetoothAudioState = intent.getIntExtra(
                        BluetoothHeadset.EXTRA_AUDIO_STATE,
                        BluetoothHeadset.STATE_ERROR);
                if (Config.LOGD) Log.d(TAG, "HEADSET AUDIO_STATE_CHANGED -> " +
                        mBluetoothAudioState);

                if (mBluetoothAudioState == BluetoothHeadset.AUDIO_STATE_CONNECTED &&
                    mWaitingForScoConnection) {
                    // SCO channel has just become available.
                    mWaitingForScoConnection = false;
                    if (mWaitingForTts) {
                        // still waiting for the TTS to be set up.
                    } else {
                        // we now have SCO connection and TTS, so we can start.
                        mHandler.postDelayed(new GreetingRunnable(), FIRST_UTTERANCE_DELAY);
                    }
                } else {
                    if (!mWaitingForScoConnection) {
                        // apparently our connection to the headset has dropped.
                        // we won't be able to continue voicedialing.
                        if (Config.LOGD) Log.d(TAG, "lost sco connection");

                        mHandler.post(new ErrorRunnable(
                                R.string.headset_connection_lost));

                        exitActivity();
                    }
                }
            }
        }
    }

    private class CommandRecognizerClient implements RecognizerClient {
        /**
         * Called by the {@link RecognizerEngine} when the microphone is started.
         */
        public void onMicrophoneStart(InputStream mic) {
            if (Config.LOGD) Log.d(TAG, "onMicrophoneStart");

            mHandler.post(new Runnable() {
                public void run() {
                    findViewById(R.id.retry_view).setVisibility(View.INVISIBLE);
                    findViewById(R.id.microphone_loading_view).setVisibility(
                            View.INVISIBLE);
                    ((TextView)findViewById(R.id.state)).setText(R.string.listening);
                    mHandler.post(mMicFlasher);
                }
            });
        }

        /**
         * Called by the {@link RecognizerEngine} if the recognizer fails.
         */
        public void onRecognitionFailure(final String msg) {
            if (Config.LOGD) Log.d(TAG, "onRecognitionFailure " + msg);
            // we had zero results.  Just try again.
            askToTryAgain();
        }

        /**
         * Called by the {@link RecognizerEngine} on an internal error.
         */
        public void onRecognitionError(final String msg) {
            if (Config.LOGD) Log.d(TAG, "onRecognitionError " + msg);
            mHandler.post(new ErrorRunnable(R.string.recognition_error));
            exitActivity();
        }

        private void askToTryAgain() {
            // get work off UAPI thread
            mHandler.post(new Runnable() {
                public void run() {
                    if (mAlertDialog != null) {
                        mAlertDialog.dismiss();
                    }

                    mState = SPEAKING_TRY_AGAIN;
                    mTtsParams.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,
                            TRY_AGAIN_UTTERANCE);
                    mTts.speak(getString(R.string.no_results_tts),
                        TextToSpeech.QUEUE_FLUSH,
                        mTtsParams);

                    mHandler.removeCallbacks(mMicFlasher);
                    ((TextView)findViewById(R.id.state)).setText(R.string.please_try_again);
                    findViewById(R.id.state).setVisibility(View.VISIBLE);
                    findViewById(R.id.microphone_view).setVisibility(View.INVISIBLE);
                    findViewById(R.id.retry_view).setVisibility(View.VISIBLE);

                    // don't listen for command yet, wait for the utterance to complete.
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

            // store the intents in a member variable so that we can access it
            // later when the user choses which action to perform.
            mAvailableChoices = intents;

            mHandler.post(new Runnable() {
                public void run() {
                    mHandler.removeCallbacks(mMicFlasher);

                    String[] sentences = new String[intents.length];
                    for (int i = 0; i < intents.length; i++) {
                        sentences[i] = intents[i].getStringExtra(
                                RecognizerEngine.SENTENCE_EXTRA);
                    }

                    if (intents.length == 0) {
                        onRecognitionFailure("zero intents");
                        return;
                    }

                    if (intents.length > 0) {
                        // see if we the response was "exit" or "cancel".
                        String value = intents[0].getStringExtra(
                            RecognizerEngine.SEMANTIC_EXTRA);
                        if (Config.LOGD) Log.d(TAG, "value " + value);
                        if ("X".equals(value)) {
                            exitActivity();
                            return;
                        }
                    }

                    if ((intents.length == 1) ||
                            (!Intent.ACTION_CALL_PRIVILEGED.equals(
                                    intents[0].getAction()))) {
                        // Either there is only one match, or multiple
                        // matches for some type of intent other than "call".
                        // If there's only one match, we may as well just
                        // dispatch it.  If it's not a "call" intent, then
                        // we don't have a good way to let the user choose
                        // which match without touching the screen.  In this
                        // case, we simply take the highest confidence match.

                        // Speak the sentence for the action we are about
                        // to dispatch so that the user knows what is happening.
                        String sentenceSpoken = spaceOutDigits(
                                mAvailableChoices[0].getStringExtra(
                                    RecognizerEngine.SENTENCE_EXTRA));

                        mState = SPEAKING_CHOSEN_ACTION;
                        mTtsParams.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,
                                CHOSEN_ACTION_UTTERANCE);
                        mTts.speak(sentenceSpoken,
                            TextToSpeech.QUEUE_FLUSH,
                            mTtsParams);
                        mChosenAction = intents[0];

                        // Normally, the intent will be dispatched after the
                        // utterance completes.  As a fallback in case the utterance
                        // does not complete, post a delayed runnable to fire
                        // the intent.
                        mFallbackRunnable = new FallbackRunnable();
                        mHandler.postDelayed(mFallbackRunnable, MAX_TTS_DELAY);

                        return;
                    } else {
                        // We have multiple call intents.  There should only
                        // be results for a single name, but multiple phone types.
                        // speak the choices to the user, and then listen for
                        // the choice.
                        // We will not start listening until the utterance
                        // of the choice list completes.
                        speakChoices();

                        // Normally, listening will begin after the
                        // utterance completes.  As a fallback in case the utterance
                        // does not complete, post a delayed runnable to begin
                        // listening.
                        mFallbackRunnable = new FallbackRunnable();
                        mHandler.postDelayed(mFallbackRunnable, MAX_TTS_DELAY);

                        DialogInterface.OnCancelListener cancelListener =
                            new DialogInterface.OnCancelListener() {

                            public void onCancel(DialogInterface dialog) {
                                if (Config.LOGD) {
                                    Log.d(TAG, "cancelListener.onCancel");
                                }
                                dialog.dismiss();
                                finish();
                            }
                       };

                        DialogInterface.OnClickListener clickListener =
                            new DialogInterface.OnClickListener() {

                            public void onClick(DialogInterface dialog, int which) {
                                if (Config.LOGD) {
                                    Log.d(TAG, "clickListener.onClick " + which);
                                }
                                startActivityHelp(intents[which]);
                                dialog.dismiss();
                                finish();
                            }
                        };

                        DialogInterface.OnClickListener negativeListener =
                            new DialogInterface.OnClickListener() {

                            public void onClick(DialogInterface dialog, int which) {
                                if (Config.LOGD) {
                                    Log.d(TAG, "negativeListener.onClick " +
                                        which);
                                }
                                dialog.dismiss();
                                finish();
                            }
                        };

                        mAlertDialog =
                                new AlertDialog.Builder(
                                        BluetoothVoiceDialerActivity.this)
                                .setTitle(R.string.title)
                                .setItems(sentences, clickListener)
                                .setOnCancelListener(cancelListener)
                                .setNegativeButton(android.R.string.cancel,
                                        negativeListener)
                                .show();
                    }
                }

            });
        }
    }

    private class ChoiceRecognizerClient implements RecognizerClient {
        public void onRecognitionSuccess(final Intent[] intents) {
            if (Config.LOGD) Log.d(TAG, "ChoiceRecognizerClient onRecognitionSuccess");

            if (mAlertDialog != null) {
                mAlertDialog.dismiss();
            }

            // disregard all but the first intent.
            if (intents.length > 0) {
                String value = intents[0].getStringExtra(
                    RecognizerEngine.SEMANTIC_EXTRA);
                if (Config.LOGD) Log.d(TAG, "value " + value);
                if ("R".equals(value)) {
                    mHandler.post(new GreetingRunnable());
                } else if ("X".equals(value)) {
                    exitActivity();
                } else {
                    // it's a phone type response
                    mChosenAction = null;
                    for (int i = 0; i < mAvailableChoices.length; i++) {
                        if (value.equalsIgnoreCase(
                                mAvailableChoices[i].getStringExtra(
                                        CommandRecognizerEngine.PHONE_TYPE_EXTRA))) {
                            mChosenAction = mAvailableChoices[i];
                        }
                    }

                    if (mChosenAction != null) {
                        mState = SPEAKING_CHOSEN_ACTION;
                        mTtsParams.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,
                                CHOSEN_ACTION_UTTERANCE);
                        mTts.speak(mChosenAction.getStringExtra(
                                RecognizerEngine.SENTENCE_EXTRA),
                            TextToSpeech.QUEUE_FLUSH,
                            mTtsParams);

                        // Normally, the intent will be dispatched after the
                        // utterance completes.  As a fallback in case the utterance
                        // does not complete, post a delayed runnable to fire
                        // the intent.
                        mFallbackRunnable = new FallbackRunnable();
                        mHandler.postDelayed(mFallbackRunnable, MAX_TTS_DELAY);
                    } else {
                        // invalid choice
                        if (Config.LOGD) Log.d(TAG, "invalid choice" + value);

                        mTtsParams.remove(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID);
                        mTts.speak(getString(R.string.invalid_choice_tts),
                            TextToSpeech.QUEUE_FLUSH,
                            mTtsParams);

                        // repeat the list of choices.  We will not start
                        // listening until this utterance completes.
                        speakChoices();

                        // Normally, listening will begin after the
                        // utterance completes.  As a fallback in case the utterance
                        // does not complete, post a delayed runnable begin
                        // listening.
                        mFallbackRunnable = new FallbackRunnable();
                        mHandler.postDelayed(mFallbackRunnable, MAX_TTS_DELAY);
                    }
                }
            }
        }

        public void onRecognitionFailure(String msg) {
            if (Config.LOGD) Log.d(TAG, "ChoiceRecognizerClient onRecognitionFailure");
            exitActivity();
        }

        public void onRecognitionError(String err) {
            if (Config.LOGD) Log.d(TAG, "ChoiceRecognizerClient onRecognitionError");
            mHandler.post(new ErrorRunnable(R.string.recognition_error));
            exitActivity();
        }

        public void onMicrophoneStart(InputStream mic) {
            if (Config.LOGD) Log.d(TAG, "ChoiceRecognizerClient onMicrophoneStart");
        }
    }

    private void speakChoices() {
        if (Config.LOGD) Log.d(TAG, "speakChoices");
        mState = SPEAKING_CHOICES;

        String sentenceSpoken = spaceOutDigits(
                mAvailableChoices[0].getStringExtra(
                    RecognizerEngine.SENTENCE_EXTRA));

        // When we have multiple choices, they will be of the form
        // "call jack jones at home", "call jack jones on mobile".
        // Speak the entire first sentence, then the last word from each
        // of the remaining sentences.  This will come out to something
        // like "call jack jones at home mobile or work".
        StringBuilder builder = new StringBuilder();
        builder.append(sentenceSpoken);

        int count = mAvailableChoices.length;
        for (int i=1; i < count; i++) {
            if (i == count-1) {
                builder.append(" or ");
            } else {
                builder.append(" ");
            }
            String tmpSentence = mAvailableChoices[i].getStringExtra(
                    RecognizerEngine.SENTENCE_EXTRA);
            String[] words = tmpSentence.trim().split(" ");
            builder.append(words[words.length-1]);
        }
        mTtsParams.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,
                CHOICES_UTTERANCE);
        mTts.speak(builder.toString(),
            TextToSpeech.QUEUE_ADD,
            mTtsParams);
    }


    private static String spaceOutDigits(String sentenceDisplay) {
        // if we have a sentence of the form "dial 123 456 7890",
        // we need to insert a space between each digit, otherwise
        // the TTS engine will say "dial one hundred twenty three...."
        // When there already is a space, we also insert a comma,
        // so that it pauses between sections.  For the displayable
        // sentence "dial 123 456 7890" it will speak
        // "dial 1 2 3, 4 5 6, 7 8 9 0"
        char buffer[] = sentenceDisplay.toCharArray();
        StringBuilder builder = new StringBuilder();
        boolean buildingNumber = false;
        int l = sentenceDisplay.length();
        for (int index = 0; index < l; index++) {
            char c = buffer[index];
            if (Character.isDigit(c)) {
                if (buildingNumber) {
                    builder.append(" ");
                }
                buildingNumber = true;
                builder.append(c);
            } else if (c == ' ') {
                if (buildingNumber) {
                    builder.append(",");
                } else {
                    builder.append(" ");
                }
            } else {
                buildingNumber = false;
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private void startActivityHelp(Intent intent) {
        startActivity(intent);
    }

    private void listenForCommand() {
        if (Config.LOGD) Log.d(TAG, ""
                + "Command(): MICROPHONE_EXTRA: "+getArg(MICROPHONE_EXTRA)+
                ", CONTACTS_EXTRA: "+getArg(CONTACTS_EXTRA));

        mState = WAITING_FOR_COMMAND;
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

    private void listenForChoice() {
        if (Config.LOGD) Log.d(TAG, "listenForChoice(): MICROPHONE_EXTRA: " +
                getArg(MICROPHONE_EXTRA));

        mState = WAITING_FOR_CHOICE;
        mRecognizerThread = new Thread() {
            public void run() {
                mPhoneTypeChoiceEngine.recognize(mChoiceClient,
                        BluetoothVoiceDialerActivity.this,
                        newFile(getArg(MICROPHONE_EXTRA)), SAMPLE_RATE);
            }
        };
        mRecognizerThread.start();
    }

    private void exitActivity() {
        synchronized(this) {
            if (mState != EXITING) {
                if (Config.LOGD) Log.d(TAG, "exitActivity");
                mState = SPEAKING_GOODBYE;
                mTtsParams.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,
                        GOODBYE_UTTERANCE);
                mTts.speak(getString(R.string.goodbye_tts),
                    TextToSpeech.QUEUE_FLUSH,
                    mTtsParams);

                // Normally, the activity will finish() after the
                // utterance completes.  As a fallback in case the utterance
                // does not complete, post a delayed runnable finish the
                // activity.
                mFallbackRunnable = new FallbackRunnable();
                mHandler.postDelayed(mFallbackRunnable, MAX_TTS_DELAY);
            }
        }
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

    protected void onStop() {
        if (Config.LOGD) Log.d(TAG, "onStop");

        synchronized(this) {
            mState = EXITING;
        }

        if (mAlertDialog != null) {
            mAlertDialog.dismiss();
        }

        // set the volume back to the level it was before we started.
        mAudioManager.setStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO,
                                      mBluetoothVoiceVolume, 0);
        mAudioManager.abandonAudioFocus(null);

        // shut down bluetooth, if it exists
        if (mBluetoothHeadset != null) {
            mBluetoothHeadset.stopVoiceRecognition();
            mBluetoothHeadset.close();
            mBluetoothHeadset = null;
        }

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

        if (mTts != null) {
            mTts.stop();
            mTts.shutdown();
            mTts = null;
        }
        unregisterReceiver(mReceiver);

        super.onStop();

        releaseWakeLock();

        // It makes no sense to have this activity maintain state when in
        // background.  When it stops, it should just be destroyed.
        finish();
    }

    private void acquireWakeLock(Context context) {
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BluetoothVoiceDialer");
            mWakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }
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
        if (Config.LOGD) Log.d(TAG, "onDestroy");
        super.onDestroy();
    }
}
