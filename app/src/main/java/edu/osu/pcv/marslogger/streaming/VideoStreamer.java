package edu.osu.pcv.marslogger.streaming;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import timber.log.Timber;

/**
 * Streams camera frames to a PC over TCP as JPEG.
 *
 * The phone listens and the PC connects. The PC first receives one text line,
 * HELLO,width,height,fx,fy,cx,cy,fps, and then one message per frame: a 20-byte big-endian
 * header (JPEG length as uint32, capture time on the boot clock in ns as uint64, capture time
 * as Unix ns as uint64) followed by the JPEG itself.
 *
 * Frames are never queued. While one frame is being encoded or sent, newer ones are dropped,
 * so a slow network lowers the frame rate instead of adding latency.
 */
public class VideoStreamer implements ImageReader.OnImageAvailableListener {

    public static final int DEFAULT_PORT = 5000;

    private static final int JPEG_QUALITY = 80;
    private static final int HEADER_BYTES = 20;
    private static final byte[] EMPTY_HEADER = new byte[HEADER_BYTES];
    // Small, so that a slow network shows up as dropped frames rather than as data
    // waiting in the socket.
    private static final int SEND_BUFFER_BYTES = 128 * 1024;

    private final int mPort;
    private final AtomicLong mSent = new AtomicLong();
    private final AtomicLong mDropped = new AtomicLong();
    private final AtomicLong mBytesSent = new AtomicLong();
    private volatile long mEncodeNanos;

    // Hand-off of a single frame between the camera thread and the send thread. The camera
    // thread writes the frame fields only while mFramePending is false; the send thread reads
    // them only while it is true.
    private final Object mFrameLock = new Object();
    private boolean mFramePending;
    private byte[] mNv21;
    private byte[] mURow;
    private byte[] mVRow;
    private int mFrameWidth;
    private int mFrameHeight;
    private long mFrameTimeNs;
    private long mFrameUnixNs;

    private volatile String mHello;
    private volatile ServerSocket mServerSocket;
    private volatile Socket mClient;
    private volatile boolean mRunning;
    private Thread mAcceptThread;
    private Thread mSendThread;

    public VideoStreamer(int port) {
        mPort = port;
    }

    public void start() {
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(mPort));
            mServerSocket = server;
        } catch (IOException e) {
            Timber.e(e, "Could not open TCP port %d; video streaming disabled", mPort);
            return;
        }
        mRunning = true;
        mAcceptThread = new Thread(this::acceptLoop, "VideoStreamerAccept");
        mSendThread = new Thread(this::sendLoop, "VideoStreamerSend");
        mAcceptThread.start();
        mSendThread.start();
        Timber.i("Video streamer listening on TCP port %d", mPort);
    }

    public void stop() {
        mRunning = false;
        closeQuietly(mServerSocket);    // unblocks accept()
        closeQuietly(mClient);          // unblocks a write stuck on a dead network
        mClient = null;
        synchronized (mFrameLock) {
            mFrameLock.notifyAll();     // unblocks the send thread's wait()
        }
        Timber.i("Video streamer stopped: %d sent, %d dropped", mSent.get(), mDropped.get());
    }

    /**
     * Camera parameters for the HELLO line, in pixels of the streamed image.
     * Cheap enough to call on every capture result.
     */
    public void setCameraInfo(int width, int height, float fx, float fy, float cx, float cy,
                              int fps) {
        mHello = String.format(Locale.US, "HELLO,%d,%d,%.2f,%.2f,%.2f,%.2f,%d\n",
                width, height, fx, fy, cx, cy, fps);
    }

    /** Called on the camera thread for each YUV_420_888 frame. */
    @Override
    public void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }
        try {
            if (mClient == null) {
                return;
            }
            synchronized (mFrameLock) {
                if (mFramePending) {
                    mDropped.incrementAndGet();
                    return;
                }
            }
            copyToNv21(image);
            long timeNs = image.getTimestamp();
            // The camera stamps frames with the boot clock; shift that instant to Unix time.
            long unixNs = timeNs + System.currentTimeMillis() * 1000000L
                    - SystemClock.elapsedRealtimeNanos();
            synchronized (mFrameLock) {
                mFrameTimeNs = timeNs;
                mFrameUnixNs = unixNs;
                mFramePending = true;
                mFrameLock.notifyAll();
            }
        } finally {
            image.close();
        }
    }

    public boolean isRunning() {
        return mRunning;
    }

    /** The connected PC, or null if none. */
    public InetAddress getClientAddress() {
        Socket client = mClient;
        return client == null ? null : client.getInetAddress();
    }

    public long getSentCount() {
        return mSent.get();
    }

    public long getDroppedCount() {
        return mDropped.get();
    }

    public long getBytesSent() {
        return mBytesSent.get();
    }

    /** Time taken to JPEG-encode the last frame. */
    public long getEncodeMillis() {
        return mEncodeNanos / 1000000L;
    }

    private void acceptLoop() {
        while (mRunning) {
            Socket socket = null;
            try {
                socket = mServerSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setSendBufferSize(SEND_BUFFER_BYTES);
            } catch (IOException e) {
                if (mRunning) {
                    Timber.w(e, "Error accepting on TCP port %d", mPort);
                }
                closeQuietly(socket);
                continue;
            }
            Timber.i("Video client connected: %s", socket.getRemoteSocketAddress());
            // Only one PC at a time; a new connection replaces the old one.
            Socket previous = mClient;
            mClient = socket;
            closeQuietly(previous);
        }
    }

    private void sendLoop() {
        FrameBuffer frame = new FrameBuffer(256 * 1024);
        Socket greeted = null;
        while (true) {
            synchronized (mFrameLock) {
                while (mRunning && !mFramePending) {
                    try {
                        mFrameLock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            if (!mRunning) {
                return;
            }
            Socket client = mClient;
            String hello = mHello;
            try {
                if (client != null && hello != null) {
                    OutputStream out = client.getOutputStream();
                    if (client != greeted) {
                        out.write(hello.getBytes(StandardCharsets.US_ASCII));
                        greeted = client;
                    }
                    encode(frame);
                    out.write(frame.array(), 0, frame.size());
                    mSent.incrementAndGet();
                    mBytesSent.addAndGet(frame.size());
                }
            } catch (IOException e) {
                Timber.i("Video client disconnected: %s", e.getMessage());
                if (mClient == client) {
                    mClient = null;
                }
                closeQuietly(client);
            } finally {
                synchronized (mFrameLock) {
                    mFramePending = false;
                }
            }
        }
    }

    /** Writes the header and the JPEG of the pending frame into the buffer. */
    private void encode(FrameBuffer frame) {
        long start = SystemClock.elapsedRealtimeNanos();
        frame.reset();
        frame.write(EMPTY_HEADER, 0, HEADER_BYTES);     // filled in once the length is known
        new YuvImage(mNv21, ImageFormat.NV21, mFrameWidth, mFrameHeight, null)
                .compressToJpeg(new Rect(0, 0, mFrameWidth, mFrameHeight), JPEG_QUALITY, frame);
        ByteBuffer.wrap(frame.array(), 0, HEADER_BYTES)
                .putInt(frame.size() - HEADER_BYTES)
                .putLong(mFrameTimeNs)
                .putLong(mFrameUnixNs);
        mEncodeNanos = SystemClock.elapsedRealtimeNanos() - start;
    }

    /**
     * Copies a YUV_420_888 image into mNv21: the full Y plane, then V and U interleaved
     * at half resolution. Handles any row and pixel stride.
     */
    private void copyToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (mNv21 == null || width != mFrameWidth || height != mFrameHeight) {
            mNv21 = new byte[width * height * 3 / 2];
            mFrameWidth = width;
            mFrameHeight = height;
        }
        Image.Plane[] planes = image.getPlanes();

        ByteBuffer y = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int pos = 0;
        for (int row = 0; row < height; row++) {
            y.position(row * yRowStride);
            y.get(mNv21, pos, width);
            pos += width;
        }

        // U and V always share row and pixel strides.
        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        int rowStride = planes[1].getRowStride();
        int pixelStride = planes[1].getPixelStride();
        int chromaWidth = width / 2;
        int rowBytes = (chromaWidth - 1) * pixelStride + 1;
        if (mURow == null || mURow.length < rowBytes) {
            mURow = new byte[rowBytes];
            mVRow = new byte[rowBytes];
        }
        for (int row = 0; row < height / 2; row++) {
            u.position(row * rowStride);
            u.get(mURow, 0, rowBytes);
            v.position(row * rowStride);
            v.get(mVRow, 0, rowBytes);
            for (int col = 0; col < chromaWidth; col++) {
                mNv21[pos++] = mVRow[col * pixelStride];
                mNv21[pos++] = mURow[col * pixelStride];
            }
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    /** Exposes the internal buffer so a frame can be sent without copying it. */
    private static class FrameBuffer extends ByteArrayOutputStream {
        FrameBuffer(int size) {
            super(size);
        }

        byte[] array() {
            return buf;
        }
    }
}
