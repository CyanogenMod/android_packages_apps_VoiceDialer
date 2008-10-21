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

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.provider.Contacts;
import android.speech.recognition.AudioSource;
import android.speech.recognition.AudioStream;
import android.speech.recognition.Codec;
import android.speech.recognition.EmbeddedRecognizer;
import android.speech.recognition.MediaFileReader;
import android.speech.recognition.MediaFileReaderListener;
import android.speech.recognition.Microphone;
import android.speech.recognition.MicrophoneListener;
import android.speech.recognition.NBestRecognitionResult;
import android.speech.recognition.RecognitionResult;
import android.speech.recognition.RecognizerListener;
import android.speech.recognition.SrecGrammar;
import android.speech.recognition.SrecGrammarListener;
import android.speech.recognition.WordItem;
import android.util.Config;
import android.util.Log;
import com.android.voicedialer.RecognizerLogger;
import com.android.voicedialer.VoiceContact;
import com.android.voicedialer.VoiceDialerActivity;
import java.io.File;
import java.io.FileFilter;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;


/**
 * This class knows how to recognize speech.  It's life cycle is as follows:
 * <ul>
 * <li>Create with a reference to the {@link VoiceDialerActivity}.
 * <li>Signal the user to start speaking with the Vibrator or beep.
 * <li>Create and starts a {@link Microphone}.
 * <li>Create and configures an {@link EmbeddedRecognizer}.
 * <li>Fetch a List of {@link VoiceContact} and determine if there is a
 * corresponding g2g file which is up-to-date.
 * <li>If the g2g file is out-of-date, update and save it.
 * <li>Start the {@link EmbeddedRecognizer} running using data already being
 * collected by the {@link Microphone} and with the updated g2g file.
 * <li>Wait for the {@link EmbeddedRecognizer} to detect end-of-speech.
 * <li>Pass a list of {@link Intent} corresponding to the recognition results
 * to the {@link VoiceDialerActivity}, which notifies the user.
 * <li>Shut down and clean up.
 * </ul>
 * Notes:
 * <ul>
 * <li>Audio many be read from a file.
 * <li>A directory tree of audio files may be stepped through.
 * <li>A contact list may be read from a file.
 * <li>A {@link RecognizerLogger} may generate a set of log files from
 * a recognition session.
 * <li>A static instance of this class is held and reused by the
 * {@link VoiceDialerActivity}, which saves setup time.
 * </ul>
 */
public class RecognizerEngine {

    private static final String TAG = "RecognizerEngine";
    
    public static final String SENTENCE_EXTRA = "sentence";
    
    private static final String SREC_DIR = "/system/usr/srec/config/en.us";
    
    private static final int RESULT_LIMIT = 5;

    private volatile VoiceDialerActivity mVoiceDialerActivity;
    private volatile RecognizerLogger mLogger;
    
    private volatile AudioSource mAudioSource;
    private volatile AudioStream mAudioStream;
    private volatile EmbeddedRecognizer mRecognizer;
    private volatile File mContactsFile;
    private volatile List<VoiceContact> mContacts;
    private volatile SrecGrammar mSrecGrammar;
    private volatile Codec mCodec;
    
    private volatile File mGrammarFile;
    private volatile Iterator<Map.Entry<String, String>> mCleanContacts;

    private volatile boolean mRecognitionInProgress = false;

    /**
     * Constructor.
     */
    public RecognizerEngine() {
        // do this here to cause libUAPI_jni.so to be loaded, ~200 msec
        android.speech.recognition.impl.System sys =
                android.speech.recognition.impl.System.getInstance();
    }

    /**
     * Start the recognition process.
     * <ul>
     * <li>Signal the user with the {@link Vibrator}.
     * <li>Create and start the {@link Microphone}.
     * </ul>
     * 
     * @param voiceDialerActivity VoiceDialerActivity instance.
     * @param logDir write log files to this directory.
     * @param micFile audio input from this file, or directory tree.
     * @param contactsFile file containing a list of contacts to use.
     * @param codec one of PCM/16bit/11KHz(default) or PCM/16bit/8KHz.
     */
    public void start(VoiceDialerActivity voiceDialerActivity,
            File micFile, File contactsFile, String codec) {
        if (Config.LOGD) {
            Log.d(TAG, "start");
        }

        // an illegal state, but attempt to recover
        if (mRecognitionInProgress) {
            mRecognitionInProgress = false;
            voiceDialerActivity.onRecognitionError(TAG + " start error - already started");
            return;
        }
        mRecognitionInProgress = true;
        
        // set up
        mVoiceDialerActivity = voiceDialerActivity;
        
        mContactsFile = contactsFile;
        
        // logger
        mLogger = null;
        if (RecognizerLogger.isEnabled(voiceDialerActivity)) {
            mLogger = new RecognizerLogger(voiceDialerActivity);
        }
        
        // set up codec
        if (codec == null) {
            codec = Codec.PCM_16BIT_11K.toString();
        }
        final Codec codecs[] = {
            // Codec.PCM_16BIT_22K, currently unimplemented
            Codec.PCM_16BIT_11K, // PCM/16bit/11KHz
            Codec.PCM_16BIT_8K,  // PCM/16bit/8KHz
            // Codec.ULAW_8BIT_8K, currently unimplemented
        };
        for (Codec c : codecs) {
            if (c.toString().equalsIgnoreCase(codec)) {
                if (mCodec != c) {
                    mCodec = c;
                    mRecognizer = null;
                    mSrecGrammar = null;
                    break;
                }
            }
        }
        if (mCodec == null) {
            if (Config.LOGD) {
                Log.d(TAG, "start - error: illegal codec " + codec);
            }
            return;
        }
        
        // check for audio input file
        if (micFile != null) {
            if (Config.LOGD) {
                Log.d(TAG, "startHelp reading audio from " + micFile);
            }
            mAudioSource = MediaFileReader.create(micFile.toString(),
                        mMediaFileReaderListener);
        }
        
        // just set up a Microphone
        else {
            Microphone mic = Microphone.getInstance();
            mAudioSource =  mic;
            mic.setCodec(mCodec);
            mic.setListener(mMicrophoneListener);
        }
        
        mAudioStream = mAudioSource.createAudio();
        
        // start the microphone
        if (mLogger != null && mAudioSource instanceof Microphone) {
            mLogger.onMicrophoneStart((Microphone)mAudioSource);
        }
        if (Config.LOGD) {
            Log.d(TAG, "startHelp mAudioSource.start");
        }
        mAudioSource.start();
    }

    /**
     * Handle {@link MicrophoneListener} events.
     */
    private final MicrophoneListener mMicrophoneListener =
            new MicrophoneListener() {

        private static final String TAG =
                RecognizerEngine.TAG + ".MicrophoneListener";

        /**
         * Called when the {@link Microphone} has been started.
         * It does the following:
         * <ul>
         * <li>Signals the {@link VoiceDialerActivity} to change the UI.
         * <li>Calls {@link createRecognizerAndStart}.
         * </ul>
         */
        public void onStarted() {
            if (Config.LOGD) {
                Log.d(TAG, "onStarted");
            }
            
            mVoiceDialerActivity.onMicrophoneStart();
            createRecognizerAndStart();
        }

        /**
         * Called when the {@link Microphone} has an error.
         * @param e An Exception associated with the error.
         */
        public void onError(Exception e) {
            if (Config.LOGD) {
                Log.d(TAG, "onError() ", e);
            }
            mVoiceDialerActivity.onRecognitionError(e.toString());
        }

        /**
         * Called when the {@link Microphone} is stops.
         */
        public void onStopped() {
            if (Config.LOGD) {
                Log.d(TAG, "onStopped()");
            }
        }

    };

    /**
     * Handle {@link MediaFileReaderListener} events.
     */
    private final MediaFileReaderListener mMediaFileReaderListener =
            new MediaFileReaderListener() {

        private static final String TAG =
                RecognizerEngine.TAG + ".MediaFileReaderListener";

        /**
         * Called when the {@link MediaFileReaderListener} has been started.
         * It does the following:
         * <ul>
         * <li>Signals the {@link VoiceDialerActivity} to change the UI.
         * <li>Calls {@link createRecognizerAndStart}.
         * </ul>
         */
        public void onStarted() {
            if (Config.LOGD) {
                Log.d(TAG, "onStarted");
            }
            
            mVoiceDialerActivity.onMicrophoneStart();
            createRecognizerAndStart();
        }

        /**
         * Called when the {@link MediaFileReader} has an error.
         * @param e An Exception associated with the error.
         */
        public void onError(Exception e) {
            if (Config.LOGD) {
                Log.d(TAG, "onError() ", e);
            }
            mVoiceDialerActivity.onRecognitionError(e.toString());
        }

        /**
         * Called when the {*link MediaFileReader} stops.
         */
        public void onStopped() {
            if (Config.LOGD) {
                Log.d(TAG, "onStopped()");
            }
            // this doesn't work, but will it fix CANT_OPEN_FILE bug?
            //((MediaFileReaderImpl)mAudioSource).dispose();
        }

    };

    /**
     * Does the following:
     * <ul>
     * <li>Create an {@link EmbeddedRecognizer}.
     * <li>Configure it.
     * <li>Get a List of {@link VoiceContact}.
     * <li>Check if the saved g2g file is out of date wrt the
     * List of {@link VoiceContact}.
     * <li>If up to date, then start recognition.
     * <li>Else begin compiling a g2g file.
     * </ul>
     */
    private void createRecognizerAndStart() {
        
        if (Config.LOGD) {
            Log.d(TAG, "createRecognizerAndStart");
        }
        
        // bail if cancelled
        if (!mRecognitionInProgress) return;
        
        EmbeddedRecognizer recognizer = EmbeddedRecognizer.getInstance();
        
        // if a new one, configure it
        if (mRecognizer != recognizer) {
            try {
                mRecognizer = recognizer;
                mSrecGrammar = null;
                if (Config.LOGD) {
                    Log.d(TAG, "createRecognizerAndStart configure");
                }
                String baseline =
                        mCodec == Codec.PCM_16BIT_11K ? "/baseline11k.par" :
                        mCodec == Codec.PCM_16BIT_8K ? "/baseline8k.par" :
                        null;
                recognizer.configure(SREC_DIR + baseline);
                if (Config.LOGD) {
                    Log.d(TAG, "createRecognizerAndStart configure done");
                }
            } catch (Exception e) {
                if (Config.LOGD) {
                    Log.d(TAG, "createRecognizerAndStart failed", e);
                }
                mRecognizer = null;
                stopMicrophone();
                // TODO: better error reporting
                return;
            }
        }
        mRecognizer.setListener(mRecognizerListener);
        mRecognizer.resetAcousticState();
        
        // bail if cancelled
        if (!mRecognitionInProgress) return;
        
        // get contact list
        List<VoiceContact> contacts = mContactsFile != null ?
                VoiceContact.getVoiceContactsFromFile(mContactsFile) :
                VoiceContact.getVoiceContacts(mVoiceDialerActivity);
        
        // check if the cached contact list is out of date
        int hashCode = contacts.hashCode();
        if (mContacts == null || hashCode != mContacts.hashCode()) {
            mContacts = contacts;
            
            // if it exists, clean up old grammar
            if (mSrecGrammar != null) {
                mSrecGrammar.unload();
                mSrecGrammar = null;
            }
        }

        // bail if cancelled
        if (!mRecognitionInProgress) return;

        // if we have an up-to-date SrecGrammar, start recognition
        if (mSrecGrammar != null) {
            if (Config.LOGD) {
                Log.d(TAG, "createRecognizerAndStart EmbeddedRecognizer.recognize");
            }
            // start recognition immediately with existing grammar
            mRecognizer.recognize(mAudioStream, mSrecGrammar);
            return;
        }
        
        // check if a current g2g file exists
        File g2g = mVoiceDialerActivity.getFileStreamPath("voicedialer." +
                Integer.toHexString(hashCode) + ".g2g");
        if (g2g.exists()) {
            mGrammarFile = null;
            // load and g2g file and start
            mSrecGrammar = (SrecGrammar)mRecognizer.createGrammar(
                    g2g.getAbsolutePath(), mSrecGrammarListener);
            mSrecGrammar.load();
            // should start recognizing on onLoad
            return;
        }
        mGrammarFile = g2g;
        
        // make sure the directory which will contain the g2g file exists
        g2g.getParentFile().mkdirs();
        
        // delete any existing cached g2g files (should be just one)
        deleteAllG2GFiles(mVoiceDialerActivity);

        // produce a list without duplicates
        HashMap<String, String> map = new HashMap<String, String>();
        StringBuffer sb = new StringBuffer();
        for (VoiceContact contact : contacts) {
            sb.setLength(0);
            sb.append(contact.mPersonId).append(' ');
            sb.append(contact.mPrimaryId).append(' ');
            sb.append(contact.mHomeId).append(' ');
            sb.append(contact.mMobileId).append(' ');
            sb.append(contact.mWorkId).append(' ');
            sb.append(contact.mOtherId);
            map.put(scrubName(contact.mName), sb.toString());
        }
        mCleanContacts = map.entrySet().iterator();

        // load uncompiled grammar
        mSrecGrammar = (SrecGrammar)mRecognizer.createGrammar(
                SREC_DIR + "/grammars/VoiceDialer.g2g", mSrecGrammarListener);
        mSrecGrammar.load();
    }
    
    /**
     * Handle events from the {@link SrecGrammar}
     */
    private final SrecGrammarListener mSrecGrammarListener =
            new SrecGrammarListener() {

        private static final String TAG =
                RecognizerEngine.TAG + ".SrecGrammarListener";

        /**
         * Called when a g2g file has been loaded.
         * <ul>
         * <li>If the grammar has been stuffed with names, then start recognizing.
         * <li>Else start stuffing names.
         * </ul>
         */
        public void onLoaded() {
            if (Config.LOGD) {
                Log.d(TAG, "onLoaded");
            }

            // bail if cancelled
            if (!mRecognitionInProgress) {
                mSrecGrammar = null;
                return;
            }
            
            // a compiled grammar has been loaded, so start
            if (mGrammarFile == null) {
                mRecognizer.recognize(mAudioStream, mSrecGrammar);
                return;
            }

            // reset all slots in uncompiled grammar
            mSrecGrammar.resetAllSlots();
        }

        /**
         * Called when a {@link SrecGrammar} has been reset,
         * prior to stuffing a new set of names.
         */
        public void onResetAllSlots() {
            if (Config.LOGD) {
                Log.d(TAG, "onResetAllSlots");
            }
            
            addItemsHelp();
        }

        /**
         * Called when a list of names has been added to a {@link SrecGrammar}.
         */
        public void onAddItemList() {
            if (Config.LOGD) {
                Log.d(TAG, "onAddItemList");
            }
            
            addItemsHelp();
        }
        
        /**
         * Add items to a {@link SrecGrammar}.  If all items have been added,
         * start compiling the {@link SrecGrammar}.
         */
        private void addItemsHelp() {
            // bail if cancelled
            if (!mRecognitionInProgress) {
                mSrecGrammar = null;
                return;
            }
            
            // insert names into uncompiled grammar
            Vector<SrecGrammar.Item> items = new Vector<SrecGrammar.Item>();
            while (mCleanContacts != null && mCleanContacts.hasNext() &&
                    items.size() < 50) {
                Map.Entry<String, String> contact = mCleanContacts.next();
                items.add(new SrecGrammar.Item(
                        WordItem.valueOf(contact.getKey(), (String)null),
                        1, "V='" + contact.getValue() + "'"));
            }
            if (items.size() > 0) {
                mSrecGrammar.addItemList("@Names", items);
            }
            else {
                mCleanContacts = null;
                // add names added, so compile the grammar
                mSrecGrammar.compileAllSlots();
            }
            
        }

        /**
         * Called when the {@link SrecGrammar} fails to add an item.
         * @param index Index of the failed item in the list.
         * @param e Exception associated with the error.
         */
        public void onAddItemListFailure(int index, Exception e) {
            if (Config.LOGD) {
                Log.d(TAG, "onAddItemListFailure " + index, e);
            }
        }

        /**
         * Called when the {@link SrecGrammar} has been compiled.  It will be
         * saved to a file.
         */
        public void onCompileAllSlots() {
            if (Config.LOGD) {
                Log.d(TAG, "onCompileAllSlots " + mGrammarFile.getAbsolutePath());
            }
            
            // bail if cancelled
            if (!mRecognitionInProgress) {
                mSrecGrammar = null;
                return;
            }

            mSrecGrammar.save(mGrammarFile.getAbsolutePath());
        }

        /**
         * Called when the {@link SrecGrammar} has been compiled.
         * The recognizer will be started next.
         * @param path pathname of the saved g2g file.
         */
        public void onSaved(String path) {
            if (Config.LOGD) {
                Log.d(TAG, "onSaved " + path);
            }

            // bail if cancelled
            if (!mRecognitionInProgress) {
                mSrecGrammar = null;
                return;
            }

            // a compiled grammar has been saved, so start
            mRecognizer.recognize(mAudioStream, mSrecGrammar);
        }

        /**
         * Called when the {@link SrecGrammar} is unloaded.
         */
        public void onUnloaded() {
            if (Config.LOGD) {
                Log.d(TAG, "onUnloaded");
            }
        }

        /**
         * Called when and error occurs in the {@link SrecGrammar}.
         * @param e an exception associated with the error.
         */
        public void onError(Exception e) {
            Log.e(TAG, "onError ", e);
            mVoiceDialerActivity.onRecognitionError(e.toString());
        }
    };

    // map letters in Latin1 Supplement to basic ascii
    // from http://en.wikipedia.org/wiki/Latin-1_Supplement_unicode_block
    // not all letters map well, including Eth and Thorn
    // TODO: this should really be all handled in the pronunciation engine
    private final static char[] mLatin1Letters =
            "AAAAAAACEEEEIIIIDNOOOOO OUUUUYDsaaaaaaaceeeeiiiidnooooo ouuuuydy".
            toCharArray();
    private final static int mLatin1Base = 0x00c0;
    
    /**
     * Reformat a raw name from the contact list into a form a
     * {@link SrecEmbeddedGrammar} can digest.
     * @param name the raw name.
     * @return the reformatted name.
     */
    private static String scrubName(String name) {
        // replace '&' with ' and '
        name = name.replace("&", " and ");
        
        // replace '@' with ' at '
        name = name.replace("@", " at ");
        
        // remove '(...)'
        while (true) {
            int i = name.indexOf('(');
            if (i == -1) break;
            int j = name.indexOf(')', i);
            if (j == -1) break;
            name = name.substring(0, i) + " " + name.substring(j + 1);
        }
        
        // map letters of Latin1 Supplement to basic ascii
        char[] nm = null;
        for (int i = name.length() - 1; i >= 0; i--) {
            char ch = name.charAt(i);
            if (mLatin1Base <= ch && ch < mLatin1Base + mLatin1Letters.length) {
                if (nm == null) {
                    nm = name.toCharArray();
                }
                nm[i] = mLatin1Letters[ch - mLatin1Base];
            }
        }
        if (nm != null) {
            name = new String(nm);
        }
        
        // if '.' followed by alnum, replace with ' dot '
        while (true) {
            int i = name.indexOf('.');
            if (i == -1 ||
                    i + 1 >= name.length() ||
                    !Character.isLetterOrDigit(name.charAt(i + 1))) break;
            name = name.substring(0, i) + " dot " + name.substring(i + 1);
        }
        
        // trim
        name = name.trim();
        
        return name;
    }

    /**
     * Cancel the recognition session.
     */
    public void cancelRecognition() {
        mRecognitionInProgress = false;
        if (mRecognizer != null) {
            mRecognizer.stop();
        }
        stopMicrophone();
    }

    /**
     * Stop the microphone, if running.
     */
    private void stopMicrophone() {
        if (mAudioSource != null) {
            mAudioSource.stop();
        }
    }
    
    /**
     * Delete all g2g files in the directory indicated by {@link File},
     * which is typically /data/data/com.android.voicedialer/files.
     * There should only be one g2g file at any one time, with a hashcode
     * embedded in it's name, but if stale ones are present, this will delete
     * them all.
     * @param context fetch directory for the stuffed and compiled g2g file.
     */
    public static void deleteAllG2GFiles(Context context) {
        FileFilter ff = new FileFilter() {
            public boolean accept(File f) {
                String name = f.getName();
                return name.endsWith(".g2g");
            }
        };
        File[] files = context.getFilesDir().listFiles(ff);
        if (files != null) {
            for (File file : files) {
                if (Config.LOGD) {
                    Log.d(TAG, "deleteSavedG2GFiles " + file);
                }
                file.delete();            
            }
        }
    }

    /**
     * Handle {@link EmbeddedRecognizer} events.
     */
    private final RecognizerListener mRecognizerListener =
            new RecognizerListener() {
        
        private static final String TAG =
            RecognizerEngine.TAG + ".RecognizerListener";

        public void onAcousticStateReset() {
            if (Config.LOGD) {
                Log.d(TAG, "onAcousticStateReset()");
            }
        }

        /**
         * Called when recognition succeeds.  It receives a list
         * of results, builds a corresponding list of Intents, and
         * passes them to the {@link VoiceDialerActivity}, which selects and
         * performs a corresponding action.
         * @param nbest a list of recognition results.
         */
        public void onRecognitionSuccess(RecognitionResult recognitionResult) {
            if (Config.LOGD) {
                Log.d(TAG, "onRecognitionSuccess");
            }
            
            // bail out if cancelled
            if (!mRecognitionInProgress) return;
            
            NBestRecognitionResult nbest =
                    (NBestRecognitionResult)recognitionResult;
            
            ArrayList<Intent> intents = new ArrayList<Intent>();

            // loop over results
            for (int result = 0; result < nbest.getSize() &&
                    intents.size() < RESULT_LIMIT; result++) {
                NBestRecognitionResult.Entry entry = nbest.getEntry(result);
                if (Config.LOGD) {
                    Log.d(TAG, RecognizerEngine.toString(entry));
                }
                
                // parse the semanticMeaning string and build an Intent
                String commands[] = entry.getSemanticMeaning().trim().split(" ");
                String literal = entry.getLiteralMeaning();
                
                // DIAL 650 867 5309
                // DIAL 867 5309
                // DIAL 911
                if ("DIAL".equals(commands[0])) {
                    Uri uri = Uri.fromParts("tel", commands[1], null);
                    // TODO: can we fix this in VoiceDialer.grxml with prons?
                    String num = commands[1];
                    if (num.length() == 10) {
                        num = num.substring(0, 3) + " " +
                                num.substring(3, 6) + " " + num.substring(6);
                    }
                    else if (num.length() == 7) {
                        num = num.substring(0,3) + " " + num.substring(3);
                    }
                    num = literal.split(" ")[0].trim() + " " + num;
                    addCallIntent(intents, uri, num, 0);
                }
                
                // CALL JACK JONES
                else if ("CALL".equals(commands[0]) && commands.length >= 7) {
                    // parse the ids
                    long personId = Long.parseLong(commands[1]); // people table
                    long phoneId  = Long.parseLong(commands[2]); // phones table
                    long homeId   = Long.parseLong(commands[3]); // phones table
                    long mobileId = Long.parseLong(commands[4]); // phones table
                    long workId   = Long.parseLong(commands[5]); // phones table
                    long otherId  = Long.parseLong(commands[6]); // phones table
                    Resources res = mVoiceDialerActivity.getResources();
                        
                    int count = 0;
                    
                    //
                    // generate the best entry corresponding to what was said
                    //
                        
                    // 'CALL JACK JONES AT HOME|MOBILE|WORK|OTHER'
                    if (commands.length == 8) {
                        long spokenPhoneId =
                                "H".equalsIgnoreCase(commands[7]) ? homeId :
                                "M".equalsIgnoreCase(commands[7]) ? mobileId :
                                "W".equalsIgnoreCase(commands[7]) ? workId :
                                "O".equalsIgnoreCase(commands[7]) ? otherId :
                                VoiceContact.ID_UNDEFINED;
                        if (spokenPhoneId != VoiceContact.ID_UNDEFINED) {
                            addCallIntent(intents, ContentUris.withAppendedId(
                                    Contacts.Phones.CONTENT_URI, spokenPhoneId),
                                    literal, 0);
                            count++;
                        }
                    }
                    
                    // 'CALL JACK JONES', with valid default phoneId
                    else if (commands.length == 7) {
                        CharSequence phoneIdMsg =
                            phoneId == VoiceContact.ID_UNDEFINED ? null :
                            phoneId == homeId ? res.getText(R.string.at_home) :
                            phoneId == mobileId ? res.getText(R.string.on_mobile) :
                            phoneId == workId ? res.getText(R.string.at_work) :
                            phoneId == otherId ? res.getText(R.string.at_other) :
                            null;
                        if (phoneIdMsg != null) {
                            addCallIntent(intents, ContentUris.withAppendedId(
                                    Contacts.Phones.CONTENT_URI, phoneId),
                                    literal + phoneIdMsg, 0);
                            count++;
                        }
                    }
                    
                    //
                    // generate all other entries
                    //
                    
                    // trim last two words, ie 'at home', etc
                    String lit = literal;
                    if (commands.length == 8) {
                        String[] words = literal.trim().split(" ");
                        StringBuffer sb = new StringBuffer();
                        for (int i = 0; i < words.length - 2; i++) {
                            if (i != 0) {
                                sb.append(' ');
                            }
                            sb.append(words[i]);
                        }
                        lit = sb.toString();
                    }
                    
                    //  add 'CALL JACK JONES at home' using phoneId
                    if (homeId != VoiceContact.ID_UNDEFINED) {
                        addCallIntent(intents, ContentUris.withAppendedId(
                                Contacts.Phones.CONTENT_URI, homeId),
                                lit + res.getText(R.string.at_home), 0);
                        count++;
                    }
                    
                    //  add 'CALL JACK JONES on mobile' using mobileId
                    if (mobileId != VoiceContact.ID_UNDEFINED) {
                        addCallIntent(intents, ContentUris.withAppendedId(
                                Contacts.Phones.CONTENT_URI, mobileId),
                                lit + res.getText(R.string.on_mobile), 0);
                        count++;
                    }
                    
                    //  add 'CALL JACK JONES at work' using workId
                    if (workId != VoiceContact.ID_UNDEFINED) {
                        addCallIntent(intents, ContentUris.withAppendedId(
                                Contacts.Phones.CONTENT_URI, workId),
                                lit + res.getText(R.string.at_work), 0);
                        count++;
                    }
                    
                    //  add 'CALL JACK JONES at other' using otherId
                    if (otherId != VoiceContact.ID_UNDEFINED) {
                        addCallIntent(intents, ContentUris.withAppendedId(
                                Contacts.Phones.CONTENT_URI, otherId),
                                lit + res.getText(R.string.at_other), 0);
                        count++;
                    }
                    
                    //
                    // if no other entries were generated, use the personId
                    //
                    
                    // add 'CALL JACK JONES', with valid personId
                    if (count == 0 && personId != VoiceContact.ID_UNDEFINED) {
                        addCallIntent(intents, ContentUris.withAppendedId(
                                Contacts.People.CONTENT_URI, personId), literal, 0);
                    }
                }
                
                // "CALL VoiceMail"
                else if ("voicemail".equals(commands[0]) && commands.length == 1) {
                    addCallIntent(intents, Uri.fromParts("voicemail", "x", null),
                            literal, Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                }
                
                // "REDIAL"
                else if ("redial".equals(commands[0]) && commands.length == 1) {
                    String number = VoiceContact.redialNumber(mVoiceDialerActivity);
                    if (number != null) {
                        addCallIntent(intents, Uri.fromParts("tel", number, null), literal,
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                    }
                }
                
                // "Intent ..."
                else if ("Intent".equalsIgnoreCase(commands[0])) {
                    for (int i = 1; i < commands.length; i++) {
                        try {
                            Intent intent = Intent.getIntent(commands[i]);
                            if (intent.getExtra(SENTENCE_EXTRA) == null) {
                                intent.putExtra(SENTENCE_EXTRA, literal);
                            }
                            addIntent(intents, intent);
                        } catch (URISyntaxException e) {
                            if (Config.LOGD) {
                                Log.d(TAG, "onRecognitionSuccess: " +
                                        "poorly formed URI in grammar\n" + e);
                            }
                        }
                    }
                }
                
                // can't parse result
                else {
                    if (Config.LOGD) {
                        Log.d(TAG, "onRecognitionSuccess: parsing error");
                    }
                }
                
            }
            
            // log if requested
            if (mLogger != null) {
                mLogger.log("onRecognitionSuccess", mContacts, nbest, intents);
            }

            // bail out if cancelled
            if (!mRecognitionInProgress) return;
            mRecognitionInProgress = false;
            
            if (intents.size() == 0) {
                // TODO: strip HOME|MOBILE|WORK and try default here?
                mVoiceDialerActivity.onRecognitionFailure("No Intents generated");
            }
            else {
                mVoiceDialerActivity.onRecognitionSuccess(
                        intents.toArray(new Intent[intents.size()]));
            }
        }
        
        // only add if different
        private void addCallIntent(ArrayList<Intent> intents, Uri uri, String literal,
                int flags) {
            Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED, uri).
                    setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | flags).
                    putExtra(SENTENCE_EXTRA, literal);
            addIntent(intents, intent);
        }
        
        private void addIntent(ArrayList<Intent> intents, Intent intent) {
            for (Intent in : intents) {
                if (in.getAction() != null &&
                        in.getAction().equals(intent.getAction()) &&
                        in.getData() != null &&
                        in.getData().equals(intent.getData())) {
                    return;
                }
            }
            intents.add(intent);
        }

        /**
         * Called when recognition fails.  It cleans up and notifies the
         * {@link VoiceDialerActivity} to notify the user.
         * @param reason
         */
        public void onRecognitionFailure(FailureReason reason) {
            if (Config.LOGD) {
                Log.d(TAG, "onRecognitionFailure " + reason);
            }
            
            // bail out if cancelled
            if (!mRecognitionInProgress) return;
            
            if (mLogger != null) {
                mLogger.log("onRecognitionFailure\n" + reason, mContacts, null,
                        null);
            }

            // bail out if cancelled
            if (!mRecognitionInProgress) return;
            mRecognitionInProgress = false;
            
            mVoiceDialerActivity.onRecognitionFailure(reason.toString());
            // TODO: a more informative error message
        }

        /**
         * Called when there is an internal error.  It cleans up and notifies
         * the {@link VoiceDialerActivity} to notify the user.
         * @param e
         */
        public void onError(Exception e) {
            if (Config.LOGD) {
                Log.d(TAG, "onError");
            }
            
            // bail out if cancelled
            if (!mRecognitionInProgress) return;
            
            if (mLogger != null) {
                mLogger.log("onError\n" + e, mContacts, null, null);
            }

            // bail out if cancelled
            if (!mRecognitionInProgress) return;
            mRecognitionInProgress = false;
            
            mVoiceDialerActivity.onRecognitionError(e.toString());
            // TODO: a more informative error message
        }

        public void onStarted() {
            if (Config.LOGD) {
                Log.d(TAG, "onStarted()");
            }
        }

        /**
         * Called when the {@link EmbeddedRecognizer} stops.  It cleans up.
         */
        public void onStopped() {
            if (Config.LOGD) {
                Log.d(TAG, "onStopped");
            }
            stopMicrophone();
        }

        public void onBeginningOfSpeech() {
            if (Config.LOGD) {
                Log.d(TAG, "onBeginningOfSpeech()");
            }
        }

        /**
         * Called when the {@link EmbeddedRecognizer} detects end-of-speech.
         */
        public void onEndOfSpeech() {
            if (Config.LOGD) {
                Log.d(TAG, "onEndOfSpeech");
            }
        }

        public void onStartOfSpeechTimeout() {
            if (Config.LOGD) {
                Log.d(TAG, "onStartOfSpeechTimeout()");
            }
        }

        public void onParametersSetError(Hashtable invalidParams, Exception e) {
            if (Config.LOGD) {
                Log.e(TAG, "onParametersGetError() " + invalidParams, e);
            }
        }

        public void onParametersGetError(Vector invalidParams, Exception e) {
            if (Config.LOGD) {
                Log.e(TAG, "onParametersGetError() " + invalidParams, e);
            }
        }

        public void onParametersSet(Hashtable parameters) {
            if (Config.LOGD) {
                Log.d(TAG, "onParametersSet() " + parameters);
            }
        }

        public void onParametersGet(Hashtable parameters) {
            if (Config.LOGD) {
                Log.d(TAG, "onParametersGet() " + parameters);
            }
        }
    };
    
    /**
     * Convert to string
     */
    public static String toString(NBestRecognitionResult.Entry entry) {
        return "conf=" + entry.getConfidenceScore() +
                " lit=" + entry.getLiteralMeaning() +
                " sem=" + entry.getSemanticMeaning();
    }

}
