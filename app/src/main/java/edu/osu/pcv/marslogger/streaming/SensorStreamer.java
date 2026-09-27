package edu.osu.pcv.marslogger.streaming;

import android.os.SystemClock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import timber.log.Timber;

/**
 * Streams sensor measurements to a PC over UDP, one CSV row per datagram, and takes
 * recording commands from it.
 *
 * The PC subscribes by sending "SUBSCRIBE" to this port and repeating it every few seconds;
 * the phone replies "SUBSCRIBED" and its state, and streams to the latest subscriber.
 * "START" and "STOP" start and stop recording; the phone answers each with its state,
 * "STATE,RECORDING,folder" or "STATE,IDLE", and also sends it when recording is started or
 * stopped on the phone. send() never blocks: datagrams are queued for a sender thread and
 * dropped if the queue is full.
 */
public class SensorStreamer {

    public static final int DEFAULT_PORT = 5001;
    public static final String START = "START";
    public static final String STOP = "STOP";

    /** Receives START and STOP on the streamer's thread. Must end with setRecordingState(). */
    public interface CommandListener {
        void onCommand(String command);
    }

    private static final String SUBSCRIBE = "SUBSCRIBE";
    private static final String SUBSCRIBED = "SUBSCRIBED";
    private static final int QUEUE_CAPACITY = 256;
    // A subscriber that has not sent SUBSCRIBE for this long is reported as silent.
    private static final long SUBSCRIBER_TIMEOUT_MS = 5000;

    private final int mPort;
    private final BlockingQueue<DatagramPacket> mQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong mSent = new AtomicLong();
    private final AtomicLong mDropped = new AtomicLong();

    private volatile DatagramSocket mSocket;
    private volatile InetSocketAddress mSubscriber;
    private volatile InetSocketAddress mCommander;
    private volatile long mLastSubscribeMs;
    private volatile String mState = "STATE,IDLE";
    private volatile CommandListener mCommandListener;
    private volatile boolean mRunning;
    private Thread mListenThread;
    private Thread mSendThread;

    public SensorStreamer(int port) {
        mPort = port;
    }

    public void start() {
        try {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(mPort));
            mSocket = socket;
        } catch (SocketException e) {
            Timber.e(e, "Could not open UDP port %d; sensor streaming disabled", mPort);
            return;
        }
        mRunning = true;
        mListenThread = new Thread(this::listenLoop, "SensorStreamerListen");
        mSendThread = new Thread(this::sendLoop, "SensorStreamerSend");
        mListenThread.start();
        mSendThread.start();
        Timber.i("Sensor streamer listening on UDP port %d", mPort);
    }

    /** Queues one line for the current subscriber. Never blocks. */
    public void send(String line) {
        InetSocketAddress to = mSubscriber;
        if (!mRunning || to == null) {
            return;     // no subscriber yet
        }
        enqueue(line, to);
    }

    public void setCommandListener(CommandListener listener) {
        mCommandListener = listener;
    }

    /**
     * Sets the state reported to the PC and sends it to the subscriber and to whoever sent
     * the last command.
     *
     * @param folder name of the recording's folder; may be empty if unknown
     */
    public void setRecordingState(boolean recording, String folder) {
        String state = recording ? "STATE,RECORDING," + folder : "STATE,IDLE";
        mState = state;
        if (!mRunning) {
            return;
        }
        InetSocketAddress subscriber = mSubscriber;
        InetSocketAddress commander = mCommander;
        if (subscriber != null) {
            enqueue(state, subscriber);
        }
        if (commander != null && !commander.equals(subscriber)) {
            enqueue(state, commander);
        }
    }

    public void stop() {
        mRunning = false;
        DatagramSocket socket = mSocket;
        if (socket != null) {
            socket.close();                 // unblocks receive()
        }
        if (mSendThread != null) {
            mSendThread.interrupt();        // unblocks take()
        }
        mQueue.clear();
        Timber.i("Sensor streamer stopped: %d sent, %d dropped", mSent.get(), mDropped.get());
    }

    /** False if the UDP port could not be opened, or after stop(). */
    public boolean isRunning() {
        return mRunning;
    }

    /** The PC that last sent SUBSCRIBE, or null if none has yet. */
    public InetSocketAddress getSubscriber() {
        return mSubscriber;
    }

    /** True if the subscriber has greeted recently enough to be considered listening. */
    public boolean isSubscriberAlive() {
        return mSubscriber != null && getMillisSinceLastSubscribe() < SUBSCRIBER_TIMEOUT_MS;
    }

    public long getMillisSinceLastSubscribe() {
        return SystemClock.elapsedRealtime() - mLastSubscribeMs;
    }

    public long getSentCount() {
        return mSent.get();
    }

    public long getDroppedCount() {
        return mDropped.get();
    }

    /**
     * IPv4 addresses a PC on the same network can reach: WiFi, hotspot or USB tethering.
     * Cellular interfaces are skipped.
     */
    public static List<String> localIpv4Addresses() {
        List<String> addresses = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = nif.getName();
                if (!nif.isUp() || nif.isLoopback()
                        || name.startsWith("rmnet") || name.startsWith("ccmni")) {
                    continue;
                }
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            Timber.w(e, "Could not list network interfaces");
        }
        return addresses;
    }

    private void enqueue(String text, InetSocketAddress to) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (!mQueue.offer(new DatagramPacket(bytes, bytes.length, to))) {
            mDropped.incrementAndGet();
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[64];
        while (mRunning) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                mSocket.receive(packet);
            } catch (IOException e) {
                if (mRunning) {
                    Timber.w(e, "Error receiving on UDP port %d", mPort);
                }
                continue;
            }
            String message = new String(packet.getData(), 0, packet.getLength(),
                    StandardCharsets.UTF_8).trim();
            InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
            if (SUBSCRIBE.equals(message)) {
                if (!from.equals(mSubscriber)) {
                    Timber.i("New sensor subscriber: %s", from);
                }
                mSubscriber = from;
                mLastSubscribeMs = SystemClock.elapsedRealtime();
                reply(SUBSCRIBED, from);
                reply(mState, from);
            } else if (START.equals(message) || STOP.equals(message)) {
                Timber.i("%s from %s", message, from);
                mCommander = from;
                CommandListener listener = mCommandListener;
                if (listener != null) {
                    listener.onCommand(message);
                } else {
                    reply(mState, from);
                }
            }
        }
    }

    private void reply(String text, InetSocketAddress to) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            mSocket.send(new DatagramPacket(bytes, bytes.length, to));
        } catch (IOException e) {
            Timber.w(e, "Could not reply to %s", to);
        }
    }

    private void sendLoop() {
        while (mRunning) {
            DatagramPacket packet;
            try {
                packet = mQueue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                mSocket.send(packet);
                mSent.incrementAndGet();
            } catch (IOException e) {
                mDropped.incrementAndGet();
                Timber.w(e, "Could not send datagram to %s", packet.getSocketAddress());
            }
        }
    }
}
