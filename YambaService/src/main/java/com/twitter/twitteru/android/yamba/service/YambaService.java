package com.twitter.twitteru.android.yamba.service;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.util.Log;

import com.marakana.android.yamba.clientlib.YambaClient;
import com.marakana.android.yamba.clientlib.YambaClient.Status;
import com.marakana.android.yamba.clientlib.YambaClientException;

import java.util.ArrayList;
import java.util.List;


public class YambaService extends IntentService {
    private static final String TAG = "SVC";

    private static final int POLLER = 666;

    public static void startPoller(Context ctxt) {
        Intent i = new Intent(ctxt, YambaService.class);
        i.putExtra(YambaContract.Service.PARAM_OP, YambaContract.Service.OP_START_POLLING);
        ctxt.startService(i);
    }

    private volatile int pollSize;
    private volatile long pollInterval;

    public YambaService() { super(TAG); }

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) { Log.d(TAG, "created"); }

        Resources rez = getResources();
        pollSize = rez.getInteger(R.integer.poll_size);
        pollInterval = rez.getInteger(R.integer.poll_interval) * 60 * 1000;

        startPoller();
   }

    @Override
    protected void onHandleIntent(Intent i) {
        int op = i.getIntExtra(YambaContract.Service.PARAM_OP, 0);
        if (BuildConfig.DEBUG) { Log.d(TAG, "exec: " + op); }
        switch (op) {
            case YambaContract.Service.OP_POST:
                doPost(i.getStringExtra(YambaContract.Service.PARAM_STATUS));
                break;

            case YambaContract.Service.OP_POLL:
                doPoll();
                break;

            case YambaContract.Service.OP_START_POLLING:
                startPoller();
                break;

            case YambaContract.Service.OP_STOP_POLLING:
                stopPoller();
                break;

            default:
                Log.e(TAG, "Unexpected op: " + op);
        }
    }

    private void doPost(String status) {
        boolean succeeded = false;
        try {
            getClient().postStatus(status);
            if (BuildConfig.DEBUG) { Log.d(TAG, "post succeeded"); }
            succeeded = true;
        }
        catch (YambaClientException e) {
            Log.e(TAG, "Post failed");
        }
        notifyPost(succeeded);
    }

    private void notifyPost(boolean succeeded) {
        Intent i = new Intent(YambaContract.Service.ACTION_POST_COMPLETE);
        i.putExtra(YambaContract.Service.PARAM_POST_SUCCEEDED, succeeded);
        if (BuildConfig.DEBUG) { Log.d(TAG, "post: " + succeeded); }
        sendBroadcast(i, YambaContract.Service.PERMISSION_RECEIVE_POST_COMPLETE);
    }

    private void startPoller() {
        if (0 >= pollInterval) { return; }
        ((AlarmManager) getSystemService(Context.ALARM_SERVICE))
                .setInexactRepeating(
                        AlarmManager.RTC,
                        System.currentTimeMillis() + 100,
                        pollInterval,
                        createPollingIntent());
    }

    private void stopPoller() {
        ((AlarmManager) getSystemService(Context.ALARM_SERVICE))
                .cancel(createPollingIntent());
    }

    private PendingIntent createPollingIntent() {
        Intent i = new Intent(this, YambaService.class);
        i.putExtra(YambaContract.Service.PARAM_OP, YambaContract.Service.OP_POLL);
        return PendingIntent.getService(
                this,
                POLLER,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void doPoll() {
        if (BuildConfig.DEBUG) { Log.d(TAG, "poll"); }

        int n = 0;
        try { n = parseTimeline(getClient().getTimeline(pollSize)); }
        catch (YambaClientException e) {
            Log.e(TAG, "Poll failed");
        }

        if (0 < n) { notifyTimelineUpdate(n); }
    }

    private int parseTimeline(List<Status> timeline) {
        long latest = getLatestStatusTime();
        if (BuildConfig.DEBUG) { Log.d(TAG, "latest: " + latest); }

        List<ContentValues> vals = new ArrayList<ContentValues>();

        for (Status status: timeline) {
            long t = status.getCreatedAt().getTime();
            if (t <= latest) { continue; }

            ContentValues cv = new ContentValues();
            cv.put(YambaContract.Timeline.Columns.ID, Long.valueOf(status.getId()));
            cv.put(YambaContract.Timeline.Columns.TIMESTAMP, Long.valueOf(t));
            cv.put(YambaContract.Timeline.Columns.USER, status.getUser());
            cv.put(YambaContract.Timeline.Columns.STATUS, status.getMessage());
            vals.add(cv);
        }

        int n = vals.size();
        if (0 >= n) { return 0; }
        n = getContentResolver().bulkInsert(
                YambaContract.Timeline.URI,
                vals.toArray(new ContentValues[n]));

        if (BuildConfig.DEBUG) { Log.d(TAG, "inserted: " + n); }
        return n;
    }

    private long getLatestStatusTime() {
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    YambaContract.Timeline.URI,
                    new String[] { YambaContract.Timeline.Columns.MAX_TIMESTAMP },
                    null,
                    null,
                    null);
            return ((null == c) || (!c.moveToNext()))
                    ? Long.MIN_VALUE
                    : c.getLong(0);
        }
        finally {
            if (null != c) { c.close(); }
        }
    }

    private void notifyTimelineUpdate(int count) {
        Intent i = new Intent(YambaContract.Service.ACTION_TIMELINE_UPDATED);
        i.putExtra(YambaContract.Service.PARAM_COUNT, count);
        if (BuildConfig.DEBUG) { Log.d(TAG, "timeline: " + count); }
        sendBroadcast(i, YambaContract.Service.PERMISSION_RECEIVE_TIMELINE_UPDATE);
    }

    private YambaClient getClient() throws YambaClientException {
        return ((YambaApplication) getApplication()).getYambaClient();
    }
}
