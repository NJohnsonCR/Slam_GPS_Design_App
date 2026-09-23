package edu.osu.pcv.marslogger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
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

    private final int mPort;
    private final BlockingQueue<String> mQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong mSent = new AtomicLong();
    private final AtomicLong mDropped = new AtomicLong();

    private volatile DatagramSocket mSocket;
    private volatile InetSocketAddress mSubscriber;
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
