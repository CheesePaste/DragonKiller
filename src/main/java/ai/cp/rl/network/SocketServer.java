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

public class SocketServer {
    private ServerSocketChannel serverChannel;
    private SocketChannel clientChannel;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(65536);
    private final StringBuilder lineBuffer = new StringBuilder();
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

    public void send(String message) {
        if (clientChannel == null || !clientChannel.isConnected()) return;
        try {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                clientChannel.write(buf);
            }
        } catch (IOException e) {
            DragonKiller.LOGGER.error("Error sending message", e);
            closeClient();
        }
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

                int newlineIdx = lineBuffer.indexOf("\n");
                if (newlineIdx >= 0) {
                    String line = lineBuffer.substring(0, newlineIdx);
                    lineBuffer.delete(0, newlineIdx + 1);
                    return Protocol.parseMessage(line);
                }
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
