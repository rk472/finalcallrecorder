package com.swadeshiapps.callrecorder.services;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.provider.DocumentsContract;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v7.view.ContextThemeWrapper;
import android.telephony.PhoneNumberUtils;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.github.axet.androidlibrary.widgets.OptimizationPreferenceCompat;
import com.github.axet.androidlibrary.widgets.ProximityShader;
import com.github.axet.androidlibrary.widgets.RemoteNotificationCompat;
import com.github.axet.androidlibrary.widgets.RemoteViewsCompat;
import com.github.axet.androidlibrary.widgets.ThemeUtils;
import com.github.axet.audiolibrary.app.RawSamples;
import com.github.axet.audiolibrary.app.Sound;
import com.github.axet.audiolibrary.encoders.EncoderInfo;
import com.github.axet.audiolibrary.encoders.Factory;
import com.github.axet.audiolibrary.encoders.FileEncoder;
import com.github.axet.audiolibrary.encoders.OnFlyEncoding;
import com.github.axet.audiolibrary.filters.AmplifierFilter;
import com.github.axet.audiolibrary.filters.SkipSilenceFilter;
import com.github.axet.audiolibrary.filters.VoiceFilter;
import com.swadeshiapps.callrecorder.R;
import com.swadeshiapps.callrecorder.activities.MainActivity;
import com.swadeshiapps.callrecorder.activities.RecentCallActivity;
import com.swadeshiapps.callrecorder.activities.SettingsActivity;
import com.swadeshiapps.callrecorder.app.MainApplication;
import com.swadeshiapps.callrecorder.app.Storage;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;


public class RecordingService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {
    public static final String TAG = RecordingService.class.getSimpleName();

    public static final int NOTIFICATION_RECORDING_ICON = 1;
    public static final int NOTIFICATION_PERSISTENT_ICON = 2;
    public static final int RETRY_DELAY = 60 * 1000; // 1 min

    public static String SHOW_ACTIVITY = RecordingService.class.getCanonicalName() + ".SHOW_ACTIVITY";
    public static String PAUSE_BUTTON = RecordingService.class.getCanonicalName() + ".PAUSE_BUTTON";
    public static String STOP_BUTTON = RecordingService.class.getCanonicalName() + ".STOP_BUTTON";

    static {
        OptimizationPreferenceCompat.ICON = true;
    }

    Sound sound;
    AtomicBoolean interrupt = new AtomicBoolean();
    Thread thread;
    Notification notification;
    Notification icon;
    Storage storage;
    RecordingReceiver receiver;
    PhoneStateReceiver state;
    // output target file 2016-01-01 01.01.01.wav
    Uri targetUri;
    PhoneStateChangeListener pscl;
    Handler handle = new Handler();
    // variable from settings. how may samples per second.
    int sampleRate;
    // how many samples passed for current recording
    long samplesTime;
    FileEncoder encoder;
    Runnable encoding; // current encoding
    HashMap<File, CallInfo> mapTarget = new HashMap<>();
    OptimizationPreferenceCompat.ServiceReceiver optimization;
    String phone = "";
    String contact = "";
    String contactId = "";
    String call;
    long now;
    int source = -1; // audiotrecorder source
    Runnable encodingNext = new Runnable() {
        @Override
        public void run() {
            encodingNext();
        }
    };

    public static class MediaRecorderThread extends Thread {
        public MediaRecorderThread() {
            super("RecordingThread");
        }

        @Override
        public void run() {
            super.run();
        }
    }

    public static void startService(Context context) {
        context.startService(new Intent(context, RecordingService.class));
    }

    public static boolean isEnabled(Context context) {
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        boolean b = shared.getBoolean(MainApplication.PREFERENCE_CALL, false);
        if (!Storage.permitted(context, MainActivity.MUST))
            b = false;
        return b;
    }

    public static void startIfEnabled(Context context) {
        if (isEnabled(context))
            startService(context);
    }

    public static void stopService(Context context) {
        context.stopService(new Intent(context, RecordingService.class));
    }

    public static void pauseButton(Context context) {
        Intent intent = new Intent(PAUSE_BUTTON);
        context.sendBroadcast(intent);
    }

    public static void stopButton(Context context) {
        Intent intent = new Intent(STOP_BUTTON);
        context.sendBroadcast(intent);
    }

    public interface Success {
        void run(Uri u);
    }

    public static class CallInfo {
        public Uri targetUri;
        public String phone;
        public String contact;
        public String contactId;
        public String call;
        public long now;

        public CallInfo() {
        }

        public CallInfo(Uri t, String p, String c, String cid, String call, long now) {
            this.targetUri = t;
            this.phone = p;
            this.contact = c;
            this.contactId = cid;
            this.call = call;
            this.now = now;
        }
    }

    class RecordingReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String a = intent.getAction();
                if (a.equals(PAUSE_BUTTON)) {
                    pauseButton();
                }
                if (a.equals(STOP_BUTTON)) {
                    finish();
                }
                if (a.equals(OptimizationPreferenceCompat.ICON_UPDATE)) {
                    updateIcon();
                }
            } catch (RuntimeException e) {
                Error(e);
            }
        }
    }

    class PhoneStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (a.equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
                setPhone(intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER), call);
            }
            if (a.equals(Intent.ACTION_NEW_OUTGOING_CALL)) {
                setPhone(intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER), MainApplication.CALL_OUT);
            }
        }
    }

    class PhoneStateChangeListener extends PhoneStateListener {
        public boolean wasRinging;
        public boolean startedByCall;
        TelephonyManager tm;

        public PhoneStateChangeListener() {
            tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        }

        @Override
        public void onCallStateChanged(final int s, final String incomingNumber) {
            try {
                switch (s) {
                    case TelephonyManager.CALL_STATE_RINGING:
                        setPhone(incomingNumber, MainApplication.CALL_IN);
                        wasRinging = true;
                        break;
                    case TelephonyManager.CALL_STATE_OFFHOOK:
                        setPhone(incomingNumber, call);
                        if (thread == null) { // handling restart while current call
                            begin(wasRinging);
                            startedByCall = true;
                        }
                        break;
                    case TelephonyManager.CALL_STATE_IDLE:
                        if (startedByCall) {
                            if (tm.getCallState() != TelephonyManager.CALL_STATE_OFFHOOK) { // current state maybe differed from queued (s) one
                                finish();
                            } else {
                                return; // fast clicking. new call already stared. keep recording. do not reset startedByCall
                            }
                        } else {
                            if (storage.recordingPending()) { // handling restart after call finished
                                finish();
                            } else if (storage.recordingNextPending()) { // only call encodeNext if we have next encoding
                                encodingNext();
                            }
                        }
                        wasRinging = false;
                        startedByCall = false;
                        phone = "";
                        contactId = "";
                        contact = "";
                        call = "";
                        break;
                }
            } catch (RuntimeException e) {
                Error(e);
            }
        }
    }

    public RecordingService() {
    }

    public void setPhone(String s, String c) {
        if (s == null || s.isEmpty())
            return;

        phone = PhoneNumberUtils.formatNumber(s);

        contact = "";
        contactId = "";
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(s));
        if (Storage.permitted(this, SettingsActivity.CONTACTS)) {
            try {
                ContentResolver contentResolver = getContentResolver();
                Cursor contactLookup = contentResolver.query(uri, null, null, null, null);
                if (contactLookup != null) {
                    try {
                        if (contactLookup.moveToNext()) {
                            contact = contactLookup.getString(contactLookup.getColumnIndex(ContactsContract.Data.DISPLAY_NAME));
                            contactId = contactLookup.getString(contactLookup.getColumnIndex(BaseColumns._ID));
                        }
                    } finally {
                        contactLookup.close();
                    }
                }
            } catch (RuntimeException e) {
                Error(e);
            }
        }

        call = c;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        optimization = new OptimizationPreferenceCompat.ServiceReceiver(this, RecordingService.this.getClass(), MainApplication.PREFERENCE_OPTIMIZATION) {
            @Override
            public void check() { // disable ping
            }

            @Override
            public void register() {
                super.register();
                OptimizationPreferenceCompat.setKillCheck(RecordingService.this, next, MainApplication.PREFERENCE_NEXT);
            }

            @Override
            public void unregister() {
                super.unregister();
                OptimizationPreferenceCompat.setKillCheck(RecordingService.this, 0, MainApplication.PREFERENCE_NEXT);
            }
        };

        receiver = new RecordingReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(PAUSE_BUTTON);
        filter.addAction(STOP_BUTTON);
        filter.addAction(OptimizationPreferenceCompat.ICON_UPDATE);
        registerReceiver(receiver, filter);

        storage = new Storage(this);
        sound = new Sound(this);

        deleteOld();

        pscl = new PhoneStateChangeListener();
        TelephonyManager tm = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        tm.listen(pscl, PhoneStateListener.LISTEN_CALL_STATE);

        filter = new IntentFilter();
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction(Intent.ACTION_NEW_OUTGOING_CALL);
        state = new PhoneStateReceiver();
        registerReceiver(state, filter);

        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);

        sampleRate = Sound.getSampleRate(this);

        shared.registerOnSharedPreferenceChangeListener(this);

        try {
            encodingNext();
        } catch (RuntimeException e) {
            Error(e);
        }

        updateIcon();
    }

    void deleteOld() {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        String d = shared.getString(MainApplication.PREFERENCE_DELETE, getString(R.string.delete_off));
        if (d.equals(getString(R.string.delete_off)))
            return;

        List<Uri> list = new ArrayList<>();

        String[] ee = Storage.getEncodingValues(this);
        Uri path = storage.getStoragePath();
        String s = path.getScheme();
        if (Build.VERSION.SDK_INT >= 21 && s.startsWith(ContentResolver.SCHEME_CONTENT)) {
            ContentResolver resolver = getContentResolver();
            Uri childId = DocumentsContract.buildChildDocumentsUriUsingTree(path, DocumentsContract.getTreeDocumentId(path));
            Cursor c = resolver.query(childId, null, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String id = c.getString(c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                    Uri dd = DocumentsContract.buildDocumentUriUsingTree(path, id);
                    list.add(dd);
                }
                c.close();
            }
        } else if (s.startsWith(ContentResolver.SCHEME_FILE)) {
            File dir = Storage.getFile(path);
            File[] ff = dir.listFiles();
            if (ff == null)
                return;
            for (File f : ff) {
                list.add(Uri.fromFile(f));
            }
        } else {
            throw new Storage.UnknownUri();
        }

        for (Uri f : list) {
            String n = Storage.getDocumentName(f).toLowerCase();
            for (String e : ee) {
                e = e.toLowerCase();
                if (n.endsWith(e)) {
                    Calendar c = Calendar.getInstance();
                    c.setTimeInMillis(storage.getLastModified(f));
                    Calendar cur = c;

                    if (d.equals(getString(R.string.delete_1day))) {
                        cur = Calendar.getInstance();
                        c.add(Calendar.DAY_OF_YEAR, 1);
                    }
                    if (d.equals(getString(R.string.delete_1week))) {
                        cur = Calendar.getInstance();
                        c.add(Calendar.WEEK_OF_YEAR, 1);
                    }
                    if (d.equals(getString(R.string.delete_1month))) {
                        cur = Calendar.getInstance();
                        c.add(Calendar.MONTH, 1);
                    }
                    if (d.equals(getString(R.string.delete_3month))) {
                        cur = Calendar.getInstance();
                        c.add(Calendar.MONTH, 3);
                    }
                    if (d.equals(getString(R.string.delete_6month))) {
                        cur = Calendar.getInstance();
                        c.add(Calendar.MONTH, 6);
                    }

                    if (c.before(cur)) {
                        if (!MainApplication.getStar(this, f)) // do not delete favorite recorings
                            storage.delete(f);
                    }
                }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");

        if (optimization.onStartCommand(intent, flags, startId)) {
            // nothing to restart
        }

        if (intent != null) {
            String a = intent.getAction();
            if (a == null) {
                ; // nothing
            } else if (a.equals(PAUSE_BUTTON)) {
                Intent i = new Intent(PAUSE_BUTTON);
                sendBroadcast(i);
            } else if (a.equals(SHOW_ACTIVITY)) {
                ProximityShader.closeSystemDialogs(this);
                MainActivity.startActivity(this);
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public class Binder extends android.os.Binder {
        public RecordingService getService() {
            return RecordingService.this;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestory");

        showNotificationAlarm(false);

        handle.removeCallbacks(encodingNext);

        stopRecording();

        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        shared.unregisterOnSharedPreferenceChangeListener(this);

        if (optimization != null) {
            optimization.close();
            optimization = null;
        }

        if (receiver != null) {
            unregisterReceiver(receiver);
            receiver = null;
        }

        if (state != null) {
            unregisterReceiver(state);
            state = null;
        }

        if (pscl != null) {
            TelephonyManager tm = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
            tm.listen(pscl, PhoneStateListener.LISTEN_NONE);
            pscl = null;
        }
    }

    public String getSourceText() {
        switch (source) {
            case MediaRecorder.AudioSource.VOICE_UPLINK:
                return "(VOICE_UPLINK)";
            case MediaRecorder.AudioSource.VOICE_DOWNLINK:
                return "(VOICE_DOWNLINK)";
            case MediaRecorder.AudioSource.VOICE_CALL:
                return getString(R.string.source_line);
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION:
                return "(VoIP)";
            case MediaRecorder.AudioSource.MIC:
                return getString(R.string.source_mic);
            case MediaRecorder.AudioSource.DEFAULT:
                return getString(R.string.source_default);
            case MediaRecorder.AudioSource.UNPROCESSED:
                return "(RAW)";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION:
                return "(VOICE_RECOGNITION)";
            case MediaRecorder.AudioSource.CAMCORDER:
                return "(Camcoder)";
            default:
                return "";
        }
    }

    @SuppressLint("RestrictedApi")
    public Notification buildNotification(Notification when) {
        boolean recording = thread != null;

        PendingIntent main = PendingIntent.getService(this, 0,
                new Intent(this, RecordingService.class).setAction(SHOW_ACTIVITY),
                PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent pe = PendingIntent.getService(this, 0,
                new Intent(this, RecordingService.class).setAction(PAUSE_BUTTON),
                PendingIntent.FLAG_UPDATE_CURRENT);

        RemoteNotificationCompat.Builder builder = new RemoteNotificationCompat.Builder(this, MainApplication.getTheme(this, R.layout.notifictaion_recording_light, R.layout.notifictaion_recording_dark));

        String title;
        String text;

        title = encoding != null ? getString(R.string.encoding_title) : (getString(R.string.recording_title) + " " + getSourceText());
        text = ".../" + Storage.getDocumentName(targetUri);
        builder.view.setViewVisibility(R.id.notification_pause, View.VISIBLE);
        builder.view.setImageViewResource(R.id.notification_pause, recording ? R.drawable.ic_stop_black_24dp : R.drawable.ic_play_arrow_black_24dp);

        title = title.trim();

        builder.view.setOnClickPendingIntent(R.id.notification_pause, pe);
        builder.view.setViewVisibility(R.id.notification_record, View.GONE);

        if (encoding != null)
            builder.view.setViewVisibility(R.id.notification_pause, View.GONE);

        builder.setTheme(MainApplication.getTheme(this, R.style.RecThemeLight, R.style.RecThemeDark))
                .setImageViewTint(R.id.icon_circle, R.attr.colorButtonNormal)
                .setMainIntent(main)
                .setIcon(R.drawable.ic_mic_24dp)
                .setTitle(title)
                .setText(text)
                .setChannel(((MainApplication) getApplication()).channelStatus)
                .setWhen(when)
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_mic);

        return builder.build();
    }

    @SuppressLint("RestrictedApi")
    public Notification buildPersistent(Notification when) {
        PendingIntent main = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);

        RemoteNotificationCompat.Builder builder = new RemoteNotificationCompat.Builder(this, MainApplication.getTheme(getBaseContext(), R.layout.notifictaion_recording_light, R.layout.notifictaion_recording_dark));

        String title;
        String text;

        title = getString(R.string.app_name);
        text = getString(R.string.recording_enabled);
        builder.view.setViewVisibility(R.id.notification_pause, View.GONE);

        title = title.trim();

        builder.view.setViewVisibility(R.id.notification_record, View.GONE);

        builder.setTheme(MainApplication.getTheme(this, R.style.RecThemeLight, R.style.RecThemeDark))
                .setMainIntent(main)
                .setImageViewTint(R.id.icon_circle, R.attr.colorButtonNormal)
                .setTitle(title)
                .setText(text)
                .setIcon(R.drawable.ic_call_black_24dp)
                .setChannel(((MainApplication) getApplication()).channelIcon)
                .setWhen(when)
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_call);

        return builder.build();
    }

    public void showNotificationAlarm(boolean show) {
        boolean recording = thread != null;
        MainActivity.showProgress(RecordingService.this, show, phone, samplesTime / sampleRate, recording);
        updateIcon();
    }

    public void updateIcon() {
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);

        OptimizationPreferenceCompat.State state = OptimizationPreferenceCompat.getState(this, MainApplication.PREFERENCE_OPTIMIZATION);

        if (!isEnabled(this) && thread == null && encoding == null) {
            stopForeground(true);
            nm.cancel(NOTIFICATION_PERSISTENT_ICON);
            nm.cancel(NOTIFICATION_RECORDING_ICON);
            icon = null;
            notification = null;
            return;
        }

        if (Build.VERSION.SDK_INT >= 26 && state.icon) {
            Notification n = buildPersistent(icon);
            if (icon == null)
                startForeground(NOTIFICATION_PERSISTENT_ICON, n);
            else
                nm.notify(NOTIFICATION_PERSISTENT_ICON, n);
            icon = n;

            if (thread == null && encoding == null) {
                nm.cancel(NOTIFICATION_RECORDING_ICON);
                notification = null;
            } else {
                n = buildNotification(notification);
                nm.notify(NOTIFICATION_RECORDING_ICON, n);
                notification = n;
            }
        } else {
            if (thread == null && encoding == null) {
                if (state.icon) {
                    Notification n = buildPersistent(notification);
                    if (notification == null)
                        startForeground(NOTIFICATION_RECORDING_ICON, n);
                    else
                        nm.notify(NOTIFICATION_RECORDING_ICON, n);
                    notification = n;
                } else {
                    stopForeground(true);
                    nm.cancel(NOTIFICATION_RECORDING_ICON);
                    notification = null;
                }
            } else {
                Notification n = buildNotification(notification);
                nm.notify(NOTIFICATION_RECORDING_ICON, n);
                notification = n;
            }
        }
    }

    public void showDone(Uri targetUri) {
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        if (!shared.getBoolean(MainApplication.PREFERENCE_DONE_NOTIFICATION, false))
            return;
        RecentCallActivity.startActivity(this, targetUri, true);
    }

    void startRecording() {
        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);

        int[] ss = new int[]{
                MediaRecorder.AudioSource.VOICE_CALL,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // mic source VOIP
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.MIC, // mic
                MediaRecorder.AudioSource.DEFAULT, // mic
                MediaRecorder.AudioSource.UNPROCESSED,
        };
        int i = Integer.valueOf(shared.getString(MainApplication.PREFERENCE_SOURCE, "-1"));
        if (i == -1)
            i = 0;
        else
            i = Sound.indexOf(ss, i);

        String ext = shared.getString(MainApplication.PREFERENCE_ENCODING, "");
        if (Storage.isMediaRecorder(ext)) {
            startMediaRecorder(ext, ss, i);
        } else {
            startAudioRecorder(ss, i);
        }

        showNotificationAlarm(true);
    }

    void startAudioRecorder(int[] ss, int i) {
        final CallInfo info = new CallInfo(targetUri, phone, contact, contactId, call, now);

        final OnFlyEncoding fly = new OnFlyEncoding(storage, info.targetUri, getInfo());

        final AudioRecord recorder = Sound.createAudioRecorder(this, sampleRate, ss, i);
        source = recorder.getAudioSource();

        final Thread old = thread;
        final AtomicBoolean oldb = interrupt;

        interrupt = new AtomicBoolean(false);

        thread = new Thread("RecordingThread") {
            @Override
            public void run() {
                if (old != null) {
                    oldb.set(true);
                    old.interrupt();
                    try {
                        old.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

                Runnable done = new Runnable() {
                    @Override
                    public void run() {
                        deleteOld();
                        stopRecording();
                        showNotificationAlarm(false);
                    }
                };

                Runnable save = new Runnable() {
                    @Override
                    public void run() {
                        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(RecordingService.this);
                        SharedPreferences.Editor edit = shared.edit();
                        edit.putString(MainApplication.PREFERENCE_LAST, Storage.getDocumentName(fly.targetUri));
                        edit.commit();

                        MainApplication.setContact(RecordingService.this, info.targetUri, info.contactId);
                        MainApplication.setCall(RecordingService.this, info.targetUri, info.call);
                        MainActivity.last(RecordingService.this);
                        showDone(info.targetUri);
                    }
                };

                try {
                    long start = System.currentTimeMillis();
                    recorder.startRecording();

                    int samplesTimeCount = 0;
                    // how many samples we need to update 'samples'. time clock. every 1000ms.
                    int samplesTimeUpdate = 1000 * sampleRate / 1000;

                    short[] buffer = new short[100 * sampleRate / 1000 * Sound.getChannels(RecordingService.this)];

                    boolean stableRefresh = false;

                    while (!interrupt.get()) {
                        final int readSize = recorder.read(buffer, 0, buffer.length);
                        if (readSize < 0) {
                            break;
                        }
                        long end = System.currentTimeMillis();

                        long diff = (end - start) * sampleRate / 1000;

                        start = end;

                        int samples = readSize / Sound.getChannels(RecordingService.this);

                        if (stableRefresh || diff >= samples) {
                            stableRefresh = true;

                            fly.encode(buffer, 0, readSize);

                            samplesTime += samples;
                            samplesTimeCount += samples;
                            if (samplesTimeCount > samplesTimeUpdate) {
                                samplesTimeCount -= samplesTimeUpdate;
                                MainActivity.showProgress(RecordingService.this, true, phone, samplesTime / sampleRate, true);
                            }
                        }
                    }
                } catch (final RuntimeException e) {
                    storage.delete(fly.targetUri);
                    Post(e);
                    return; // no save
                } finally {
                    handle.post(done);

                    if (recorder != null)
                        recorder.release();

                    if (fly != null) {
                        try {
                            fly.close();
                        } catch (RuntimeException e) {
                            storage.delete(fly.targetUri);
                            Post(e);
                            return; // no save
                        }
                    }
                }
                handle.post(save);
            }
        };
        thread.start();
    }

    void startMediaRecorder(String ext, int[] ss, int i) {
        try {
            final CallInfo info = new CallInfo(targetUri, phone, contact, contactId, call, now);
            FileDescriptor fd;
            String s = info.targetUri.getScheme();
            if (Build.VERSION.SDK_INT >= 21 && s.equals(ContentResolver.SCHEME_CONTENT)) {
                ContentResolver resolver = getContentResolver();
                Uri root = Storage.getDocumentTreeUri(info.targetUri);
                resolver.takePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                String path = Storage.getDocumentChildPath(info.targetUri);
                Uri out = storage.createFile(root, path);
                if (out == null)
                    throw new RuntimeException("Unable to create file, permissions?");
                ParcelFileDescriptor pfd = resolver.openFileDescriptor(out, "rw");
                fd = pfd.getFileDescriptor();
            } else {
                File f = new File(info.targetUri.getPath());
                FileOutputStream os = new FileOutputStream(f);
                fd = os.getFD();
            }

            final MediaRecorder recorder = new MediaRecorder();
            recorder.setAudioChannels(Sound.getChannels(this));
            recorder.setAudioSource(ss[i]);
            recorder.setAudioEncodingBitRate(Factory.getBitrate(sampleRate));

            source = ss[i];

            switch (ext) {
                case Storage.EXT_3GP:
                    recorder.setAudioSamplingRate(8192);
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                    break;
                case Storage.EXT_3GP16:
                    recorder.setAudioSamplingRate(16384);
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_WB);
                    break;
                case Storage.EXT_AAC:
                    recorder.setAudioSamplingRate(sampleRate);
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    break;
                case Storage.EXT_AACHE:
                    recorder.setAudioSamplingRate(sampleRate);
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC);
                    break;
                case Storage.EXT_AACELD:
                    recorder.setAudioSamplingRate(sampleRate);
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC_ELD);
                    break;
                case Storage.EXT_WEBM:
                    recorder.setAudioSamplingRate(sampleRate);
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.WEBM);
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.VORBIS);
                    break;
                default:
                    recorder.setAudioSamplingRate(sampleRate);
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.DEFAULT);
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);
            }
            recorder.setOutputFile(fd);
            recorder.setOnErrorListener(new MediaRecorder.OnErrorListener() {
                @Override
                public void onError(MediaRecorder mr, int what, int extra) {
                    Log.d(TAG, "MediaRecorder error" + what + " " + extra);
                    stopRecording();
                }
            });
            recorder.prepare();
            final Thread old = thread;
            final AtomicBoolean oldb = interrupt;

            interrupt = new AtomicBoolean(false);
            thread = new MediaRecorderThread() {
                @Override
                public void run() {
                    if (old != null) {
                        oldb.set(true);
                        old.interrupt();
                        try {
                            old.join();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }

                    Runnable done = new Runnable() {
                        @Override
                        public void run() {
                            deleteOld();
                            stopRecording();
                            showNotificationAlarm(false);
                        }
                    };

                    Runnable save = new Runnable() {
                        @Override
                        public void run() {
                            final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(RecordingService.this);
                            SharedPreferences.Editor edit = shared.edit();
                            edit.putString(MainApplication.PREFERENCE_LAST, Storage.getDocumentName(info.targetUri));
                            edit.commit();

                            MainApplication.setContact(RecordingService.this, info.targetUri, info.contactId);
                            MainApplication.setCall(RecordingService.this, info.targetUri, info.call);
                            MainActivity.last(RecordingService.this);
                            showDone(info.targetUri);
                        }
                    };

                    boolean start = false;
                    try {
                        Thread.sleep(2000); // sleep after prepare, some devices requires to record opponent side
                        recorder.start();
                        start = true;
                        while (!interrupt.get()) {
                            Thread.sleep(1000);
                            samplesTime += 1000 * sampleRate / 1000; // per 1 second
                            MainActivity.showProgress(RecordingService.this, true, phone, samplesTime / sampleRate, null);
                        }
                    } catch (RuntimeException e) {
                        storage.delete(info.targetUri);
                        Post(e);
                        return; // no save
                    } catch (InterruptedException e) {
                        ;
                    } finally {
                        handle.post(done);
                        if (start) {
                            try {
                                recorder.stop();
                            } catch (RuntimeException e) { // https://stackoverflow.com/questions/16221866
                                storage.delete(info.targetUri);
                                Post(e);
                            }
                        }
                        recorder.release();
                    }

                    handle.post(save);
                }
            };
            thread.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    EncoderInfo getInfo() {
        final int channels = Sound.getChannels(this);
        final int bps = Sound.DEFAULT_AUDIOFORMAT == AudioFormat.ENCODING_PCM_16BIT ? 16 : 8;
        return new EncoderInfo(channels, sampleRate, bps);
    }

    void encoding(final File in, final Uri uri, final Runnable done, final Success success) {
        final OnFlyEncoding fly = new OnFlyEncoding(storage, uri, getInfo());

        final SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(RecordingService.this);

        encoder = new FileEncoder(this, in, fly);

        if (shared.getBoolean(MainApplication.PREFERENCE_VOICE, false))
            encoder.filters.add(new VoiceFilter(getInfo()));
        float amp = shared.getFloat(MainApplication.PREFERENCE_VOLUME, 1);
        if (amp != 1)
            encoder.filters.add(new AmplifierFilter(amp));
        if (shared.getBoolean(MainApplication.PREFERENCE_SKIP, false))
            encoder.filters.add(new SkipSilenceFilter(getInfo()));

        final Runnable save = new Runnable() {
            @Override
            public void run() {
                Storage.delete(in); // delete raw recording

                MainActivity.showProgress(RecordingService.this, false, phone, samplesTime / sampleRate, false);

                SharedPreferences.Editor edit = shared.edit();
                edit.putString(MainApplication.PREFERENCE_LAST, Storage.getDocumentName(fly.targetUri));
                edit.commit();

                success.run(fly.targetUri);
                done.run();
                encodingNext();
            }
        };

        encoder.run(new Runnable() {
            @Override
            public void run() {  // progress
                MainActivity.setProgress(RecordingService.this, encoder.getProgress());
            }
        }, new Runnable() {
            @Override
            public void run() {  // success only call, done
                save.run();
            }
        }, new Runnable() {
            @Override
            public void run() { // error
                storage.delete(fly.targetUri);
                MainActivity.showProgress(RecordingService.this, false, phone, samplesTime / sampleRate, false);
                Error(encoder.getException());
                done.run();
                handle.removeCallbacks(encodingNext);
                handle.postDelayed(encodingNext, RETRY_DELAY);
            }
        });
    }

    void Post(Throwable e) {
        Log.e(TAG, Log.getStackTraceString(e));
        while (e.getCause() != null)
            e = e.getCause();
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty())
            msg = e.getClass().getSimpleName();
        Post("AudioRecord error: " + msg);
    }

    void Post(final String msg) {
        handle.post(new Runnable() {
            @Override
            public void run() {
                Error(msg);
            }
        });
    }

    void Error(Throwable e) {
        Log.d(TAG, "Error", e);
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            Throwable t;
            if (encoder == null) {
                t = e;
            } else {
                t = encoder.getException();
                if (t == null)
                    t = e;
            }
            while (t.getCause() != null)
                t = t.getCause();
            msg = t.getClass().getSimpleName();
        }
        Error(msg);
    }

    void Error(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    void pauseButton() {
        if (thread != null) {
            stopRecording();
        } else {
            startRecording();
        }
        MainActivity.showProgress(this, true, phone, samplesTime / sampleRate, thread != null);
    }

    void stopRecording() {
        if (thread != null) {
            interrupt.set(true);
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    void begin(boolean wasRinging) {
        now = System.currentTimeMillis();
        targetUri = storage.getNewFile(now, phone, contact, call);
        if (encoder != null) {
            encoder.pause();
        }
        if (storage.recordingPending()) {
            RawSamples rs = new RawSamples(storage.getTempRecording());
            samplesTime = rs.getSamples();
        } else {
            samplesTime = 0;
        }
        startRecording();
    }

    void finish() {
        stopRecording();
        File tmp = storage.getTempRecording();
        if (tmp.exists() && tmp.length() > 0) {
            File parent = tmp.getParentFile();
            File in = Storage.getNextFile(parent, Storage.TMP_REC, null);
            Storage.move(tmp, in);
            mapTarget.put(in, new CallInfo(targetUri, phone, contact, contactId, call, now));
            if (encoder == null) { // double finish()? skip
                encodingNext();
            } else {
                encoder.resume();
            }
        } else { // if encoding failed, we will get no output file, hide notifications
            deleteOld();
            showNotificationAlarm(false);
        }
    }

    void encodingNext() {
        handle.removeCallbacks(encodingNext); // clean next
        if (encoder != null) // can be called twice, exit if alreay encoding
            return;
        if (thread != null) // currently recorindg
            return;
        final File inFile = storage.getTempNextRecording();
        if (inFile == null)
            return;
        if (!inFile.exists())
            return;
        if (inFile.length() == 0) {
            mapTarget.remove(inFile);
            Storage.delete(inFile);
            return;
        }
        CallInfo c = mapTarget.get(inFile);
        if (c == null) { // service restarted, additional info not saved
            c = new CallInfo();
            c.phone = "";
            c.contact = "";
            c.contactId = "";
            c.call = "";
            c.now = inFile.lastModified();
            c.targetUri = storage.getNewFile(c.now, c.phone, c.contact, c.call);
        }
        targetUri = c.targetUri; // update notification encoding name
        final String contactId = c.contactId;
        final String call = c.call;
        final Uri targetUri = RecordingService.this.targetUri;
        encoding = new Runnable() { //  allways called when done
            @Override
            public void run() {
                deleteOld();
                showNotificationAlarm(false);
                encoding = null;
                encoder = null;
            }
        };
        showNotificationAlarm(true); // update status (encoding)
        Log.d(TAG, "Encoded " + inFile.getName() + " to " + storage.getDisplayName(targetUri));
        encoding(inFile, targetUri, encoding, new Success() {
            @Override
            public void run(Uri t) { // called on success
                mapTarget.remove(inFile);
                MainApplication.setContact(RecordingService.this, t, contactId);
                MainApplication.setCall(RecordingService.this, t, call);
                MainActivity.last(RecordingService.this);
                showDone(t);
            }
        });
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals(MainApplication.PREFERENCE_DELETE)) {
            deleteOld();
        }
        if (key.equals(MainApplication.PREFERENCE_STORAGE)) {
            encodingNext();
        }
        if (key.equals(MainApplication.PREFERENCE_THEME)) {
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        optimization.onTaskRemoved(rootIntent);
    }
}
