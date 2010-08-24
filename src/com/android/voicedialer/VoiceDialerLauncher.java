package com.android.voicedialer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;

public class VoiceDialerLauncher extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AudioManager audmgr = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        Intent activity = new Intent(this, audmgr.isBluetoothScoOn() ?
                BluetoothVoiceDialerActivity.class : VoiceDialerActivity.class);
        activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(activity);
        finish();
    }
}
