package edu.osu.pcv.marslogger;

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
 * TFG (Fase 2): sends sensor measurements to the PC over UDP, one line per datagram,
 * in the same format as the CSV rows (CONTEXTO_APP_ANDROID.md §4.3).
 *
 * The PC speaks first: it sends "SUBSCRIBE" to this port. The phone remembers where it
 * came from, answers "SUBSCRIBED", and from then on sends every measurement there. This
 * keeps the phone as the server (§3), so the PC's IP never has to be typed on the phone.
 *
 * send() never blocks: it enqueues, and if the queue is full the line is dropped and
 * counted. A separate thread does the actual sending, because Android forbids network
 * I/O on the main thread.
 */
public class SensorStreamer {

    public static final int DEFAULT_PORT = 5001;

    private static final String SUBSCRIBE = "SUBSCRIBE";
    private static final byte[] SUBSCRIBED = "SUBSCRIBED".getBytes(StandardCharsets.UTF_8);
    private static final int QUEUE_CAPACITY = 256;
    // The PC re-sends SUBSCRIBE every 2 s. Without one for this long, it is shown as silent.
    private static final long SUBSCRIBER_TIMEOUT_MS = 5000;

    private final int mPort;
    private final BlockingQueue<String> mQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong mSent = new AtomicLong();
    private final AtomicLong mDropped = new AtomicLong();

    private volatile DatagramSocket mSocket;
    private volatile InetSocketAddress mSubscriber;
    private volatile long mLastSubscribeMs;
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
        if (!mRunning || mSubscriber == null) {
            return;     // nobody is listening: nothing to send, and nothing lost
        }
        if (!mQueue.offer(line)) {
            mDropped.incrementAndGet();
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
     * IPv4 addresses the PC can use to reach this phone: WiFi, the phone's own hotspot, or
     * USB tethering. Cellular interfaces are skipped because the PC cannot reach them.
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
            if (!SUBSCRIBE.equals(message)) {
                continue;
            }
            InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
            if (!from.equals(mSubscriber)) {
                Timber.i("New sensor subscriber: %s", from);
            }
            mSubscriber = from;
            mLastSubscribeMs = SystemClock.elapsedRealtime();
            try {
                mSocket.send(new DatagramPacket(SUBSCRIBED, SUBSCRIBED.length, from));
            } catch (IOException e) {
                Timber.w(e, "Could not acknowledge subscriber %s", from);
            }
        }
    }

    private void sendLoop() {
        while (mRunning) {
            String line;
            try {
                line = mQueue.take();
            } catch (InterruptedException e) {
                return;
            }
            InetSocketAddress to = mSubscriber;
            if (to == null) {
                continue;
            }
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            try {
                mSocket.send(new DatagramPacket(bytes, bytes.length, to));
                mSent.incrementAndGet();
            } catch (IOException e) {
                mDropped.incrementAndGet();
                Timber.w(e, "Could not send sensor datagram to %s", to);
            }
        }
    }
}
