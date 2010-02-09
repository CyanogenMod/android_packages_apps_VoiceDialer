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

import android.util.Config;
import android.util.Log;
import android.content.Intent;
import android.speech.srec.Recognizer;

import java.util.ArrayList;
import java.io.IOException;


/**
 * This is a RecognizerEngine that processes yes and no responses.
 * <ul>
 * <li>setupGrammar creates and stores the boolean grammar, which will remain
 * in memory as a member variable.
 * <li>onRecognitionSuccess is called when we get results from the recognizer,
 * it will process the results, which will pass a list of intents to
 * the {@RecognizerClient}
 * </ul>
 * Notes:
 * <ul>
 * <li>Audio many be read from a file.
 * <li>A directory tree of audio files may be stepped through.
 * <li>A contact list may be read from a file.
 * <li>A {@link RecognizerLogger} may generate a set of log files from
 * a recognition session.
 * <li>A static instance of this class is held and reused by the
 * {@link BluetoothVoiceDialerActivity}, which saves setup time.
 * </ul>
 */
public class YesNoRecognizerEngine extends RecognizerEngine {
    /**
     * Constructor.
     */
    public YesNoRecognizerEngine() {

    }

    protected void setupGrammar() throws IOException, InterruptedException {
        if (mSrecGrammar == null) {
            if (Config.LOGD) Log.d(TAG, "start new Grammar");
            mSrecGrammar = mSrec.new Grammar(SREC_DIR + "/grammars/boolean.g2g");
            mSrecGrammar.setupRecognizer();
        }
    }

    /**
     * Called when recognition succeeds.  It receives a list
     * of results, builds a corresponding list of Intents, and
     * passes them to the {@link RecognizerClient}, which selects and
     * performs a corresponding action.
     * @param recognizerClient the client that will be sent the results
     */
    protected void onRecognitionSuccess(RecognizerClient recognizerClient) throws InterruptedException {
        if (Config.LOGD) Log.d(TAG, "onRecognitionSuccess " + mSrec.getResultCount());

        if (mLogger != null) mLogger.logNbestHeader();

        ArrayList<Intent> intents = new ArrayList<Intent>();

        // we only pay attention to the first result.
        if (mSrec.getResultCount() > 0) {
            // parse the semanticMeaning string and build an Intent
            String conf = mSrec.getResult(0, Recognizer.KEY_CONFIDENCE);
            String literal = mSrec.getResult(0, Recognizer.KEY_LITERAL);
            String semantic = mSrec.getResult(0, Recognizer.KEY_MEANING);
            String msg = "conf=" + conf + " lit=" + literal + " sem=" + semantic;
            if (Config.LOGD) Log.d(TAG, msg);
            if (mLogger != null) mLogger.logLine(msg);

            if (("1".equalsIgnoreCase(semantic)) ||
                ("0".equalsIgnoreCase(semantic))) {
                if (Config.LOGD) Log.d(TAG, " got yes or no");
                Intent intent = new Intent(ACTION_RECOGNIZER_RESULT, null);
                intent.putExtra(SENTENCE_EXTRA, literal);
                intent.putExtra(SEMANTIC_EXTRA, semantic);
                addIntent(intents, intent);
            } else {
                // Anything besides yes or no is a failure.
            }
        }

        // log if requested
        if (mLogger != null) mLogger.logIntents(intents);

        // bail out if cancelled
        if (Thread.interrupted()) throw new InterruptedException();

        if (intents.size() == 0) {
            if (Config.LOGD) Log.d(TAG, " no intents");
            recognizerClient.onRecognitionFailure("No Intents generated");
        }
        else {
            if (Config.LOGD) Log.d(TAG, " success");
            recognizerClient.onRecognitionSuccess(
                    intents.toArray(new Intent[intents.size()]));
        }
    }
}
