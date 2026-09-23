package edu.osu.pcv.marslogger;

import android.app.Activity;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

import timber.log.Timber;

/**
 * this class is mostly similar to IMUManager because gps data must be logged at the same time
 * as the imu data (but in a separate file)
 *
 * TFG: reescrito para que las posiciones sean alineables con la cámara y la IMU.
 * Ver FASE0_BUILD.md §6. Tres cambios respecto de upstream:
 *
 *   1. El timestamp sale de Location.getElapsedRealtimeNanos(), que es el mismo
 *      reloj monótono (CLOCK_BOOTTIME) que usan CaptureResult.SENSOR_TIMESTAMP y
 *      SensorEvent.timestamp. Upstream usaba System.currentTimeMillis(), o sea
 *      reloj de pared en milisegundos, en una columna rotulada [nanosec]: con eso
 *      no se puede saber qué frame corresponde a qué posición.
 *   2. Se agrega la columna speed, que el formato objetivo necesita.
 *   3. Se escribe un solo archivo, location.csv. Upstream abría además gps.csv,
 *      que quedaba siempre vacío porque recordGpsValue() no tenía llamadores.
 */
public class GPSManager {

    public static String GpsHeader = "Timestamp[nanosecond],latitude[degrees]," +
            "longitude[degrees],altitude[meters],speed[meters/second]," +
            "Unix time[nanosecond]\n";

    private static final long MILLI_TO_NANO = 1000000L;

    private final LocationProvider locationProvider;
    private final SensorStreamer mStreamer;

    // Protege al writer: el callback de ubicación corre en locationThread y
    // stopRecording() puede cerrarlo desde el hilo de UI.
    private final Object mWriterLock = new Object();
    private BufferedWriter mLocationWriter = null;
    private volatile boolean mRecordingGpsData = false;

    private int mFixCount = 0;

    /**
     * @param streamer receives every row written to location.csv, prefixed with "GPS,"
     *                 (CONTEXTO_APP_ANDROID.md §4.3). May be null to only record.
     */
    public GPSManager(Activity activity, SensorStreamer streamer) {
        mStreamer = streamer;
        locationProvider = new LocationProvider(activity, location -> {
            if (!mRecordingGpsData) {
                return;
            }
            synchronized (mWriterLock) {
                if (mLocationWriter == null) {
                    return;
                }
                String row = location.getElapsedRealtimeNanos() + ","
                        + location.getLatitude() + ","
                        + location.getLongitude() + ","
                        + location.getAltitude() + ","
                        + location.getSpeed() + ","
                        + location.getTime() * MILLI_TO_NANO;
                try {
                    mLocationWriter.write(row + "\n");
                    ++mFixCount;
                } catch (IOException ioe) {
                    Timber.e(ioe, "IOException writing a location fix");
                }
                // Recording and streaming coexist: a failed disk write must not stop the stream.
                if (mStreamer != null) {
                    mStreamer.send("GPS," + row);
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

    public boolean ismRecordingGpsData() {
        return mRecordingGpsData;
    }

    public void unregister() {
        locationProvider.quitThread();
    }
}
