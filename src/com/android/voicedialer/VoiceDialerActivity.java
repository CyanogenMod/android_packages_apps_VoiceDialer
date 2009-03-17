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
import android.bluetooth.BluetoothHeadset;
import android.content.Intent;
import android.content.DialogInterface;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.telephony.PhoneNumberUtils;
import android.util.Config;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import com.android.voicedialer.RecognizerEngine;
//import com.android.voicedialer.VoiceDialerTester;
import java.io.File;


/**
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
    private static final String CODEC_EXTRA = "codec";
    private static final String TONE_EXTRA = "tone";
    
    private static final int FAIL_PAUSE_MSEC = 5000;

    private final static RecognizerEngine mEngine = new RecognizerEngine();
    private VoiceDialerTester mVoiceDialerTester;
    private Handler mHandler;
    private Thread mRecognizerThread = null;
    private AudioManager mAudioManager;
    private int mSavedVolume;
    private ToneGenerator mToneGenerator;
    private BluetoothHeadset mBluetoothHeadset;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        if (Config.LOGD) Log.d(TAG, "onCreate");
        
        mHandler = new Handler();
        
        // get AudioManager, save current music volume, set music volume to zero
        mAudioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        mSavedVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        
        // set up ToneGenerator
        // currently disabled because it crashes audio input
        mToneGenerator = !"0".equals(getArg(TONE_EXTRA)) ?
                new ToneGenerator(AudioManager.STREAM_RING, ToneGenerator.MAX_VOLUME) :
                null;

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
        
        // throw up tooltip
        if (false && !Intent.ACTION_VOICE_COMMAND.equals(getIntent().getAction())) {
            View v = getLayoutInflater().inflate(R.layout.tool_tip, null);
            Toast toast = new Toast(this);
            toast.setView(v);
            toast.setDuration(Toast.LENGTH_LONG);
            toast.setGravity(Gravity.BOTTOM, 0, 0);
            toast.show();
        }

        // start the tester, if present
        mVoiceDialerTester = null;
        File micDir = newFile(getArg(MICROPHONE_EXTRA));
        if (micDir != null && micDir.isDirectory()) {
            mVoiceDialerTester = new VoiceDialerTester(micDir);
            startNextTest();
            return;
        }

        // Get handle to BluetoothHeadset object if required
        if (Intent.ACTION_VOICE_COMMAND.equals(getIntent().getAction()) &&
            // start work in the BluetoothHeadsetClient onServiceConnected() callback
            getIntent().getIntExtra(Intent.EXTRA_AUDIO_ROUTE, -1) ==
            AudioManager.ROUTE_BLUETOOTH_SCO) {
            mBluetoothHeadset = new BluetoothHeadset(this, mBluetoothHeadsetServiceListener);
        } else {
            startWork();
        }
    }

    private BluetoothHeadset.ServiceListener mBluetoothHeadsetServiceListener =
            new BluetoothHeadset.ServiceListener() {
        public void onServiceConnected() {
            if (mBluetoothHeadset != null) {
                mBluetoothHeadset.startVoiceRecognition();
                startWork();
            }
        }
        public void onServiceDisconnected() {}
    };

    private void startWork() {
        // prompt the user with a beep
        final int msec = playSound(ToneGenerator.TONE_PROP_PROMPT);
        
        // start the engine after the beep
        mRecognizerThread = new Thread() {
            public void run() {
                if (Config.LOGD) Log.d(TAG, "onCreate.Runnable.run");
                try {
                    Thread.sleep(msec);
                } catch (InterruptedException e) {
                    return;
                }
                if (mToneGenerator != null) mToneGenerator.stopTone();
                mEngine.recognize(VoiceDialerActivity.this,
                        newFile(getArg(MICROPHONE_EXTRA)),
                        newFile(getArg(CONTACTS_EXTRA)),
                        getArg(CODEC_EXTRA));
            }
        };
        mRecognizerThread.start();
    }
    
    /**
     * Returns a Bundle with the result for a test run
     * @return Bundle or null if the test is in progress
     */
    public Bundle getRecognitionResult() {
        return null;
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
                String codec = getArg(CODEC_EXTRA);
                notifyText("Testing\n" + microphone + "\n" + contacts);
                mEngine.recognize(VoiceDialerActivity.this,
                        microphone, contacts, codec);
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
        if (mAudioManager.shouldVibrate(AudioManager.VIBRATE_TYPE_RINGER)) {
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
    protected void onPause() {
        super.onPause();

        if (Config.LOGD) Log.d(TAG, "onPause");

        // shut down bluetooth, if it exists
        if (mBluetoothHeadset != null) {
            mBluetoothHeadset.stopVoiceRecognition();
            mBluetoothHeadset.close();
            mBluetoothHeadset = null;
        }
        
        // restore volume, if changed
        if (mSavedVolume > 0) {
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mSavedVolume, 0);
            mSavedVolume = 0;
        }
        
        // no more tester
        mVoiceDialerTester = null;
        
        // shut down recognizer and wait for the thread to complete
        if (mRecognizerThread !=  null) {
            mRecognizerThread.interrupt();
            try {
                mRecognizerThread.join();
            } catch (InterruptedException e) {
                if (Config.LOGD) Log.d(TAG, "onPause mRecognizerThread.join exception " + e);
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

    /**
     * Called by the {@link RecognizerEngine} when the microphone is started.
     */
    public void onMicrophoneStart() {
        if (Config.LOGD) Log.d(TAG, "onMicrophoneStart");
        
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
        
        mHandler.post(new Runnable() {
            
            public void run() {
                // success, so beep about it
                playSound(ToneGenerator.TONE_PROP_ACK);
                
                mHandler.removeCallbacks(mMicFlasher);
                
                // only one item, so just launch
                /*
                if (intents.length == 1 && mVoiceDialerTester == null) {
                    // start the Intent
                    startActivityHelp(intents[0]);
                    finish();
                    return;
                }
                */

                DialogInterface.OnClickListener clickListener =
                    new DialogInterface.OnClickListener() {
                    
                    public void onClick(DialogInterface dialog, int which) {
                        if (Config.LOGD) Log.d(TAG, "clickListener.onClick " + which);
                        startActivityHelp(intents[which]);
                        dialog.dismiss();
                        finish();
                    }
                    
                };
                
                DialogInterface.OnCancelListener cancelListener =
                    new DialogInterface.OnCancelListener() {
                    
                    public void onCancel(DialogInterface dialog) {
                        if (Config.LOGD) Log.d(TAG, "cancelListener.onCancel");
                        dialog.dismiss();
                        finish();
                    }
                    
                };

                DialogInterface.OnClickListener positiveListener =
                    new DialogInterface.OnClickListener() {
                    
                    public void onClick(DialogInterface dialog, int which) {
                        if (Config.LOGD) Log.d(TAG, "positiveListener.onClick " + which);
                        if (intents.length == 1 && which == -1) which = 0;
                        startActivityHelp(intents[which]);
                        dialog.dismiss();
                        finish();
                    }
                    
                };

                DialogInterface.OnClickListener negativeListener =
                    new DialogInterface.OnClickListener() {
                    
                    public void onClick(DialogInterface dialog, int which) {
                        if (Config.LOGD) Log.d(TAG, "negativeListener.onClick " + which);
                        dialog.dismiss();
                        finish();
                    }
                    
                };
                
                String[] sentences = new String[intents.length];
                for (int i = 0; i < intents.length; i++) {
                    sentences[i] = intents[i].getStringExtra(
                            RecognizerEngine.SENTENCE_EXTRA);
                }

                final AlertDialog alertDialog = intents.length > 1 ?
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
    
    @Override
    protected void onDestroy() {
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
