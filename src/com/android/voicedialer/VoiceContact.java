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
import android.database.Cursor;
import android.provider.Contacts;
import android.provider.CallLog;
import android.util.Config;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


/**
 * This class represents a person who may be called via the VoiceDialer app.
 * The person has a name and a list of phones (home, mobile, work, other).
 */
public class VoiceContact {
    private static final String TAG = "VoiceContact";
    
    /**
     * Corresponding row doesn't exist.
     */
    public static final long ID_UNDEFINED = -1;

    public final String mName;
    public final long mPersonId;
    public final long mPrimaryId;
    public final long mHomeId;
    public final long mMobileId;
    public final long mWorkId;
    public final long mOtherId;

    /**
     * Constructor.
     * 
     * @param name person's name.
     * @param personId ID in person table.
     * @param primaryId primary ID in phone table.
     * @param homeId home ID in phone table.
     * @param mobileId mobile ID in phone table.
     * @param workId work ID in phone table.
     * @param otherId other ID in phone table.
     */
    private VoiceContact(String name, long personId, long primaryId,
            long homeId, long mobileId, long workId,long otherId) {
        mName = name;
        mPersonId = personId;
        mPrimaryId = primaryId;
        mHomeId = homeId;
        mMobileId = mobileId;
        mWorkId = workId;
        mOtherId = otherId;
    }
    
    @Override
    public int hashCode() {
        final int LARGE_PRIME = 1610612741;
        int hash = 0;
        hash = LARGE_PRIME * (hash + (int)mPersonId);
        hash = LARGE_PRIME * (hash + (int)mPrimaryId);
        hash = LARGE_PRIME * (hash + (int)mHomeId);
        hash = LARGE_PRIME * (hash + (int)mMobileId);
        hash = LARGE_PRIME * (hash + (int)mWorkId);
        hash = LARGE_PRIME * (hash + (int)mOtherId);
        return mName.hashCode() + hash;
    }
    
    @Override
    public String toString() {
        return "mName=" + mName
                + " mPersonId=" + mPersonId
                + " mPrimaryId=" + mPrimaryId
                + " mHomeId=" + mHomeId
                + " mMobileId=" + mMobileId
                + " mWorkId=" + mWorkId
                + " mOtherId=" + mOtherId;
    }
    
    /**
     * @param activity The VoiceDialerActivity instance.
     * @return List of {@link VoiceContact} from
     * the contact list content provider.
     */
    public static List<VoiceContact> getVoiceContacts(Activity activity) {
        if (Config.LOGD) Log.d(TAG, "VoiceContact.getVoiceContacts");
        
        List<VoiceContact> contacts = new ArrayList<VoiceContact>();

        String[] phonesProjection = new String[] {
            Contacts.Phones._ID,
            Contacts.Phones.TYPE,
            Contacts.Phones.ISPRIMARY,
            // TODO: handle type != 0,1,2, and use LABEL
            Contacts.Phones.LABEL,
            Contacts.Phones.NAME,
            //Contacts.Phones.NUMBER,
            Contacts.Phones.PERSON_ID,
        };
        
        // table is sorted by name
        Cursor cursor = activity.getContentResolver().query(
                Contacts.Phones.CONTENT_URI, phonesProjection,
                "", null, Contacts.Phones.DEFAULT_SORT_ORDER);

        final int phoneIdColumn = cursor.getColumnIndexOrThrow(Contacts.Phones._ID);
        final int typeColumn = cursor.getColumnIndexOrThrow(Contacts.Phones.TYPE);
        final int isPrimaryColumn = cursor.getColumnIndexOrThrow(Contacts.Phones.ISPRIMARY);
        int labelColumn = cursor.getColumnIndexOrThrow(Contacts.Phones.LABEL);
        final int nameColumn = cursor.getColumnIndexOrThrow(Contacts.Phones.NAME);
        //final int numberColumn = cursor.getColumnIndexOrThrow(Contacts.Phones.NUMBER);
        final int personIdColumn = cursor.getColumnIndexOrThrow(Contacts.Phones.PERSON_ID);
        
        // pieces of next VoiceContact
        String name = null;
        long personId = ID_UNDEFINED;
        long primaryId = ID_UNDEFINED;
        long homeId = ID_UNDEFINED;
        long mobileId = ID_UNDEFINED;
        long workId = ID_UNDEFINED;
        long otherId = ID_UNDEFINED;

        // loop over phone table
        cursor.moveToFirst();
        while (cursor.moveToNext()) {
            long phoneIdAtCursor = cursor.getLong(phoneIdColumn);
            int typeAtCursor = cursor.getInt(typeColumn);
            long isPrimaryAtCursor = cursor.getLong(isPrimaryColumn);
            String labelAtCursor = cursor.getString(labelColumn);
            String nameAtCursor = cursor.getString(nameColumn);
            //String numberAtCursor = cursor.getString(numberColumn);
            long personIdAtCursor = cursor.getLong(personIdColumn);

            /*
            if (Config.LOGD) {
                Log.d(TAG, "phoneId=" + phoneIdAtCursor
                        + " type=" + typeAtCursor
                        + " isPrimary=" + isPrimaryAtCursor
                        + " label=" + labelAtCursor
                        + " name=" + nameAtCursor
                        //+ " number=" + numberAtCursor
                        + " personId=" + personIdAtCursor
                        );
            }
            */
            
            // encountered a new name, so generate current VoiceContact
            if (name != null && !name.equals(nameAtCursor)) {
                contacts.add(new VoiceContact(name, personId, primaryId,
                        homeId, mobileId, workId, otherId));
                name = null;
            }
            
            // start accumulating pieces for a new VoiceContact
            if (name == null) {
                name = nameAtCursor;
                personId = personIdAtCursor;
                primaryId = ID_UNDEFINED;
                homeId = ID_UNDEFINED;
                mobileId = ID_UNDEFINED;
                workId = ID_UNDEFINED;
                otherId = ID_UNDEFINED;
            }
            
            // if labeled, then patch to HOME/MOBILE/WORK/OTHER
            if (typeAtCursor == Contacts.Phones.TYPE_CUSTOM &&
                    labelAtCursor != null) {
                String label = labelAtCursor.toLowerCase();
                if (label.contains("home") || label.contains("house")) {
                    typeAtCursor = Contacts.Phones.TYPE_HOME;
                }
                else if (label.contains("mobile") || label.contains("cell")) {
                    typeAtCursor = Contacts.Phones.TYPE_MOBILE;
                }
                else if (label.contains("work") || label.contains("office")) {
                    typeAtCursor = Contacts.Phones.TYPE_WORK;
                }
                else if (label.contains("other")) {
                    typeAtCursor = Contacts.Phones.TYPE_OTHER;
                }
            }
            
            // save the home, mobile, or work phone id
            switch (typeAtCursor) {
                case Contacts.Phones.TYPE_HOME:
                    homeId = phoneIdAtCursor;
                    if (isPrimaryAtCursor != 0) {
                        primaryId = phoneIdAtCursor;
                    }
                    break;
                case Contacts.Phones.TYPE_MOBILE:
                    mobileId = phoneIdAtCursor;
                    if (isPrimaryAtCursor != 0) {
                        primaryId = phoneIdAtCursor;
                    }
                    break;
                case Contacts.Phones.TYPE_WORK:
                    workId = phoneIdAtCursor;
                    if (isPrimaryAtCursor != 0) {
                        primaryId = phoneIdAtCursor;
                    }
                    break;
                case Contacts.Phones.TYPE_OTHER:
                    otherId = phoneIdAtCursor;
                    if (isPrimaryAtCursor != 0) {
                        primaryId = phoneIdAtCursor;
                    }
                    break;
            }
        }
        
        // generate the last VoiceContact
        if (name != null) {
            contacts.add(new VoiceContact(name, personId, primaryId,
                            homeId, mobileId, workId, otherId));
        }
        
        // clean up cursor
        cursor.close();

        if (Config.LOGD) Log.d(TAG, "VoiceContact.getVoiceContacts " + contacts.size());
        
        return contacts;
    }
    
    /**
     * @param contactsFile File containing a list of names,
     * one per line.
     * @return a List of {@link VoiceContact} in a File.
     */
    public static List<VoiceContact> getVoiceContactsFromFile(File contactsFile) {
        if (Config.LOGD) Log.d(TAG, "getVoiceContactsFromFile " + contactsFile);

        List<VoiceContact> contacts = new ArrayList<VoiceContact>();

        // read from a file
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(contactsFile), 8192);
            String name;
            for (int id = 1; (name = br.readLine()) != null; id++) {
                contacts.add(new VoiceContact(name, id, ID_UNDEFINED,
                        ID_UNDEFINED, ID_UNDEFINED, ID_UNDEFINED, ID_UNDEFINED));
            }
        }
        catch (IOException e) {
            if (Config.LOGD) Log.d(TAG, "getVoiceContactsFromFile failed " + e);
        }
        finally {
            try {
                br.close();
            } catch (IOException e) {
                if (Config.LOGD) Log.d(TAG, "getVoiceContactsFromFile failed during close " + e);
            }
        }

        if (Config.LOGD) Log.d(TAG, "getVoiceContactsFromFile " + contacts.size());

        return contacts;
    }
    
    /**
     * @param activity The VoiceDialerActivity instance.
     * @return String of last number dialed.
     */
    public static String redialNumber(Activity activity) {
        Cursor cursor = activity.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                new String[] { CallLog.Calls.NUMBER },
                CallLog.Calls.TYPE + "=" + CallLog.Calls.OUTGOING_TYPE,
                null,
                CallLog.Calls.DEFAULT_SORT_ORDER + " LIMIT 1");
        String number = null;
        if (cursor.getCount() >= 1) {
            cursor.moveToNext();
            int column = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER);
            number = cursor.getString(column);
        }
        cursor.close();
        
        if (Config.LOGD) Log.d(TAG, "redialNumber " + number);
        
        return number;
    }

}
