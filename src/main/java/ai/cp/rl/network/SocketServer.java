package ai.cp.rl.network;

import ai.cp.DragonKiller;
import ai.cp.config.RLConfig;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SocketServer {
    private ServerSocketChannel serverChannel;
    private SocketChannel clientChannel;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(65536);
    private final StringBuilder lineBuffer = new StringBuilder();
    private final Queue<ByteBuffer> sendQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean running;

    public void start() {
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(true);
            serverChannel.bind(new InetSocketAddress(RLConfig.TCP_PORT));
            running = true;
            DragonKiller.LOGGER.info("RL SocketServer listening on port {}", RLConfig.TCP_PORT);
        } catch (IOException e) {
            DragonKiller.LOGGER.error("Failed to start SocketServer", e);
        }
    }

    public boolean acceptClient() {
        if (serverChannel == null) return false;
        try {
            serverChannel.configureBlocking(false);
            clientChannel = serverChannel.accept();
            if (clientChannel != null) {
                clientChannel.configureBlocking(false);
                DragonKiller.LOGGER.info("RL client connected: {}", clientChannel.getRemoteAddress());
                return true;
            }
        } catch (IOException e) {
            DragonKiller.LOGGER.error("Error accepting client", e);
        }
        return false;
    }

    /**
     * Enqueue a message for sending. Actual write happens in drainSendQueue().
     * Never blocks the server tick.
     */
    public void send(String message) {
        if (clientChannel == null || !clientChannel.isConnected()) return;
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        sendQueue.add(ByteBuffer.wrap(bytes));
    }

    /**
     * Called every server tick to drain the send queue.
     * Writes up to one full message per tick (non-blocking).
     * If the TCP buffer is full, remaining messages stay queued for next tick.
     */
    public void drainSendQueue() {
        if (clientChannel == null || !clientChannel.isConnected()) {
            sendQueue.clear();
            return;
        }
        try {
            while (!sendQueue.isEmpty()) {
                ByteBuffer buf = sendQueue.peek();
                int written = clientChannel.write(buf);
                if (written == 0) {
                    // Kernel buffer full — wait for next tick
                    return;
                }
                if (buf.hasRemaining()) {
                    // Partial write — try finishing it next tick
                    return;
                }
                sendQueue.poll(); // Full message sent
            }
        } catch (IOException e) {
            DragonKiller.LOGGER.error("Error draining send queue", e);
            closeClient();
        }
    }

    public int getQueuedSendBytes() {
        int total = 0;
        for (ByteBuffer buf : sendQueue) {
            total += buf.remaining();
        }
        return total;
    }

    public JsonObject tryReceive() {
        if (clientChannel == null || !clientChannel.isConnected()) return null;
        try {
            int bytesRead = clientChannel.read(readBuffer);
            if (bytesRead == -1) {
                closeClient();
                return null;
            }
            if (bytesRead > 0) {
                readBuffer.flip();
                byte[] data = new byte[readBuffer.remaining()];
                readBuffer.get(data);
                readBuffer.clear();
                lineBuffer.append(new String(data, StandardCharsets.UTF_8));
            }

            // Always check lineBuffer — there may be queued messages from a previous over-read
            int newlineIdx = lineBuffer.indexOf("\n");
            if (newlineIdx >= 0) {
                String line = lineBuffer.substring(0, newlineIdx);
                lineBuffer.delete(0, newlineIdx + 1);
                return Protocol.parseMessage(line);
            }
        } catch (IOException e) {
            DragonKiller.LOGGER.error("Error receiving message", e);
            closeClient();
        }
        return null;
    }

    public boolean isConnected() {
        return clientChannel != null && clientChannel.isConnected();
    }

    public void closeClient() {
        if (clientChannel != null) {
            try {
                clientChannel.close();
            } catch (IOException ignored) {
            }
            clientChannel = null;
        }
        sendQueue.clear();
        lineBuffer.setLength(0);
        DragonKiller.LOGGER.info("RL client disconnected");
    }

    public void stop() {
        running = false;
        closeClient();
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {
            }
        }
    }

    public boolean isRunning() {
        return running;
    }
}
