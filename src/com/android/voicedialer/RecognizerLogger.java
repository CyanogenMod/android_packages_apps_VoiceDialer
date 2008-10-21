package com.android.voicedialer;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.pim.DateFormat;
import android.speech.recognition.AudioStream;
import android.speech.recognition.MediaFileWriter;
import android.speech.recognition.MediaFileWriterListener;
import android.speech.recognition.Microphone;
import android.speech.recognition.NBestRecognitionResult;
import android.speech.recognition.impl.MediaFileWriterImpl;
import android.util.Config;
import android.util.Log;

import com.android.voicedialer.VoiceContact;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This class logs the inputs and results of a recognition session to
 * the files listed below, which reside in /sdcard/voicedialer.
 * The files have the date encoded in the  name so that they will sort in
 * time order.  The newest RecognizerLogger.MAX_FILES are kept,
 * and the rest deleted to limit space used in the file system.
 * <ul>
 * <li> datename.wav - what the microphone heard.
 * <li> datename.log - contact list, results, errors, etc.
 * </ul>
 */
public class RecognizerLogger {

    private static final String TAG = "RecognizerLogger";
    
    private static final String LOGDIR = "logdir";
    private static final String ENABLED = "enabled";
    
    private static final int MAX_FILES = 20;
    
    private final String mDatedPath;

    private AudioStream mAudioStream;
    
    /**
     * Determine if logging is enabled.  If the
     * @param context needed to reference the logging directory.
     * @return true if logging is enabled, determined by the 'enabled' file.
     */
    public static boolean isEnabled(Context context) {
        File dir = context.getDir(LOGDIR, 0);
        File enabled = new File(dir, ENABLED);
        return enabled.exists();
    }
    
    /**
     * Enable logging.
     * @param context needed to reference the logging directory.
     */
    public static void enable(Context context) {
        try {
            File dir = context.getDir(LOGDIR, 0);
            File enabled = new File(dir, ENABLED);
            enabled.createNewFile();
        }
        catch (IOException e) {
            Log.d(TAG, "enableLogging " + e);
        }
    }
    
    /**
     * Disable logging.
     * @param context needed to reference the logging directory.
     */
    public static void disable(Context context) {
        try {
            File dir = context.getDir(LOGDIR, 0);
            File enabled = new File(dir, ENABLED);
            enabled.delete();
        }
        catch (SecurityException e) {
            Log.d(TAG, "enableLogging " + e);
        }
    }

    /**
     * Constructor
     * @param dataDir directory to contain the log files.
     */
    public RecognizerLogger(Context context) {
        File dir = context.getDir(LOGDIR, 0);
        mDatedPath = dir.toString() + File.separator + "log_" +
                DateFormat.format("yyyy_MM_dd_kk_mm_ss",
                        System.currentTimeMillis());
    }

    /**
     * Called when the {@link Microphone} is started.
     * @param mic the {@link Microphone} to record.
     */
    public void onMicrophoneStart(Microphone mic) {
        mAudioStream = mic.createAudio();
    }

    /**
     * Helper to write the list of results to a file.
     * @param msg message to write to the log file.
     * @param contacts a List of {@link VoiceContact} or null.
     * @param nbest an {@link NBestRecognitionResult} or null.
     * @param intents an an array of {@link Intent} or null.
     */
    public void log(String msg, List<VoiceContact> contacts,
            NBestRecognitionResult nbest, ArrayList<Intent> intents) {
        if (Config.LOGD) {
            Log.d(TAG, "log " + mDatedPath);
        }
        
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(mDatedPath + ".log"), 8192);
            
            // header message
            writer.write(msg);
            writer.newLine();

            writer.write(Build.FINGERPRINT);
            writer.newLine();

            // list results
            if (nbest != null) {
                writer.write("NBestRecognitionResult *****************");
                writer.newLine();
                for (int i = 0; i < nbest.getSize(); i++) {
                    writer.write(RecognizerEngine.toString(nbest.getEntry(i)));
                    writer.newLine();
                }
            }
            
            // list Intents
            if (intents != null) {
                writer.write("Intents *********************");
                writer.newLine();
                for (Intent intent : intents) {
                    writer.write(intent.toString());
                    writer.write(" ");
                    writer.write(RecognizerEngine.SENTENCE_EXTRA + "=");
                    writer.write(intent.getStringExtra(RecognizerEngine.SENTENCE_EXTRA));
                    writer.newLine();
                }
            }
            
            // list of contacts
            if (contacts != null) {
                writer.write("Contacts *****************");
                writer.newLine();
                for (VoiceContact vc : contacts) {
                    writer.write(vc.toString());
                    writer.newLine();
                }
            }
            
            writer.flush();
            
        } catch (IOException ioe) {
            if (Config.LOGD) {
                Log.d(TAG, "log ", ioe);
            }
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ioe) {
                } 
            }
        }
        
        // save utterance
        if (mAudioStream != null) {
            MediaFileWriter mfw =
                    MediaFileWriter.create(mMediaFileWriterListener);
            mfw.save(mAudioStream, mDatedPath + ".wav");
            //bug work-around until dispose is exposed in MediaFileWriter
            ((MediaFileWriterImpl) mfw).dispose();
        }
        
        // delete oldest files in log directory
        rotateAll();
    }
    
    private MediaFileWriterListener mMediaFileWriterListener =
            new MediaFileWriterListener() {

        private static final String TAG = RecognizerLogger.TAG +
                ".MediaFileWriterListener";

        public void onError(Exception e) {
            if (Config.LOGD) {
                Log.d(TAG, "MediaFileWriterListener.onError", e);
            }
        }

        public void onStopped() {
            if (Config.LOGD) {
                Log.d(TAG, "MediaFileWriterListener.onStopped");
            }
        }
    };

    /**
     * Helper to delete oldest files, using the filename to determine.
     */
    private void rotateAll() {
        if (Config.LOGD) {
            Log.d(TAG, "rotateAll");
        }
        rotate(".wav");
        rotate(".log");
        if (Config.LOGD) {
            Log.d(TAG, "rotateAll done");
        }
    }

    /**
     * Helper to rotateAll, which deleted the oldest files with a give suffix.
     * @param suffix delete oldest files with this suffix.
     */
    private void rotate(final String suffix) {
        FileFilter ff = new FileFilter() {
            public boolean accept(File f) {
                String name = f.getName();
                return name.startsWith("log_") && name.endsWith(suffix);
            }
        };
        File[] files = (new File(mDatedPath)).getParentFile().listFiles(ff);
        Arrays.sort(files);

        for (int i = 0; i < files.length - MAX_FILES; i++) {
            files[i].delete();            
        }
    }

}
