package edu.osu.pcv.marslogger;

import android.app.Activity;
import android.os.SystemClock;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;

import edu.osu.pcv.marslogger.streaming.SensorStreamer;
import timber.log.Timber;

/**
 * this class is mostly similar to IMUManager because gps data must be logged at the same time
 * as the imu data (but in a separate file)
 *
 * Fixes are timestamped with Location.getElapsedRealtimeNanos(), the CLOCK_BOOTTIME base that
 * camera frames and IMU samples also use, so all three can be aligned.
 */
public class GPSManager {

    public static String GpsHeader = "Timestamp[nanosecond],latitude[degrees]," +
            "longitude[degrees],altitude[meters],speed[meters/second]," +
            "Unix time[nanosecond]\n";

    private static final long MILLI_TO_NANO = 1000000L;
    // The fix rate shown on screen is averaged over this window.
    private static final long RATE_WINDOW_NS = 10000000000L;

    private final LocationProvider locationProvider;
    private final SensorStreamer mStreamer;

    // Fixes are written from the location thread; stopRecording() runs on the UI thread.
    private final Object mWriterLock = new Object();
    private BufferedWriter mLocationWriter = null;
    private volatile boolean mRecordingGpsData = false;

    private int mFixCount = 0;

    // Timestamps of the fixes within the rate window, newest last.
    private final ArrayDeque<Long> mRecentFixes = new ArrayDeque<>();

    /**
     * @param streamer receives every fix as a location.csv row prefixed with "GPS,", also
     *                 when not recording. May be null.
     */
    public GPSManager(Activity activity, SensorStreamer streamer) {
        mStreamer = streamer;
        locationProvider = new LocationProvider(activity, location -> {
            String row = location.getElapsedRealtimeNanos() + ","
                    + location.getLatitude() + ","
                    + location.getLongitude() + ","
                    + location.getAltitude() + ","
                    + location.getSpeed() + ","
                    + location.getTime() * MILLI_TO_NANO;
            recordFixTime(location.getElapsedRealtimeNanos());
            // Streamed whether or not the phone is recording.
            if (mStreamer != null) {
                mStreamer.send("GPS," + row);
            }
            if (!mRecordingGpsData) {
                return;
            }
            synchronized (mWriterLock) {
                if (mLocationWriter == null) {
                    return;
                }
                try {
                    mLocationWriter.write(row + "\n");
                    ++mFixCount;
                } catch (IOException ioe) {
                    Timber.e(ioe, "IOException writing a location fix");
                }
            }
        });
        locationProvider.createLocationListener();
    }

    public void startRecording(String locationResultFile) {
        synchronized (mWriterLock) {
            try {
                mLocationWriter = new BufferedWriter(
                        new FileWriter(locationResultFile, false));
                if (locationProvider == null) {
                    mLocationWriter.write("Unable to initialize locationProvider!\n");
                } else {
                    mLocationWriter.write(GpsHeader);
                }
                mFixCount = 0;
                mRecordingGpsData = true;
            } catch (IOException err) {
                Timber.e(err, "IOException in opening gps data writer at %s",
                        locationResultFile);
            }
        }
    }

    public void stopRecording() {
        mRecordingGpsData = false;
        synchronized (mWriterLock) {
            if (mLocationWriter == null) {
                return;
            }
            try {
                mLocationWriter.flush();
                mLocationWriter.close();
            } catch (IOException err) {
                Timber.e(err, "IOException in closing gps data writer");
            }
            Timber.d("Recorded %d location fixes", mFixCount);
            mLocationWriter = null;
        }
    }

    /** Fixes per second over the last 10 s, or 0 with fewer than two fixes. */
    public float getFixRateHz() {
        synchronized (mRecentFixes) {
            long spanNs = mRecentFixes.size() < 2 ? 0
                    : mRecentFixes.peekLast() - mRecentFixes.peekFirst();
            return spanNs > 0 ? (mRecentFixes.size() - 1) * 1e9f / spanNs : 0f;
        }
    }

    /** Age of the latest fix, or -1 if there has been none. */
    public long getMillisSinceLastFix() {
        synchronized (mRecentFixes) {
            if (mRecentFixes.isEmpty()) {
                return -1;
            }
            return (SystemClock.elapsedRealtimeNanos() - mRecentFixes.peekLast()) / MILLI_TO_NANO;
        }
    }

    private void recordFixTime(long timeNs) {
        synchronized (mRecentFixes) {
            mRecentFixes.addLast(timeNs);
            while (timeNs - mRecentFixes.peekFirst() > RATE_WINDOW_NS) {
                mRecentFixes.removeFirst();
            }
        }
    }

    public boolean ismRecordingGpsData() {
        return mRecordingGpsData;
    }

    public void unregister() {
        locationProvider.quitThread();
    }
}
