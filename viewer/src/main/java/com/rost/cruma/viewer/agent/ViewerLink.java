package com.rost.cruma.viewer.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class ViewerLink implements AutoCloseable {
    record Call(long id, CompletableFuture<ToolResult> result) {}

    private static final long STATUS_WAIT_MILLIS = 3_000L;
    private static final int HELLO_MILLIS = 3_000;

    private final ViewerEndpoint endpoint;
    private final Socket socket;
    private final BufferedWriter writer;
    private final AtomicLong next = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JSONObject>> waiting = new ConcurrentHashMap<>();
    private volatile boolean open = true;

    private ViewerLink(ViewerEndpoint endpoint, Socket socket, BufferedWriter writer) {
        this.endpoint = endpoint;
        this.socket = socket;
        this.writer = writer;
    }

    static ViewerLink connect(ViewerEndpoint endpoint) throws IOException {
        var socket = new Socket();
        BufferedWriter writer;
        BufferedReader reader;
        try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port()), HELLO_MILLIS);
            socket.setSoTimeout(HELLO_MILLIS);
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var hello = new JSONObject();
            hello.put("token", endpoint.token());
            writer.write(hello.toJSONString());
            writer.newLine();
            writer.flush();
            var answer = reader.readLine();
            if (answer == null || !JSON.parseObject(answer).getBooleanValue("ok")) {
                throw new IOException("The viewer refused this connection");
            }
            socket.setSoTimeout(0);
        } catch (IOException | RuntimeException failed) {
            socket.close();
            throw failed;
        }
        var link = new ViewerLink(endpoint, socket, writer);
        Thread.ofPlatform()
                .name("cruma-agent-viewer-" + endpoint.pid())
                .daemon()
                .start(() -> link.read(reader));
        return link;
    }

    public ViewerEndpoint getEndpoint() {
        return endpoint;
    }

    boolean isOpen() {
        return open;
    }

    Call call(String tool, JSONObject args) {
        var message = new JSONObject();
        message.put("call", tool);
        message.put("args", args);
        var answer = send(message);
        return new Call(message.getLongValue("id"), answer.thenApply(reply -> {
            var result = ToolResult.read(reply.getJSONObject("result"));
            var folder = reply.getString("folder");
            return folder == null ? result : result.withFolder(Path.of(folder));
        }));
    }

    void cancel(long call) {
        var message = new JSONObject();
        message.put("cancel", call);
        send(message);
    }

    public JSONObject getStatus() throws InterruptedException {
        var message = new JSONObject();
        message.put("status", true);
        try {
            return send(message).get(STATUS_WAIT_MILLIS, TimeUnit.MILLISECONDS).getJSONObject("status");
        } catch (ExecutionException | TimeoutException gone) {
            return null;
        }
    }

    void note(String tool, ToolResult result) {
        var note = new JSONObject();
        note.put("tool", tool);
        note.put("summary", result.getSummary());
        note.put("failed", result.hasFailed());
        note.put(
                "folder", result.getFolder() == null ? null : result.getFolder().toString());
        var message = new JSONObject();
        message.put("note", note);
        try {
            synchronized (writer) {
                writer.write(message.toJSONString());
                writer.newLine();
                writer.flush();
            }
        } catch (IOException gone) {
        }
    }

    public void shutdown() {
        var message = new JSONObject();
        message.put("shutdown", true);
        send(message);
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void read(BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                var message = JSON.parseObject(line);
                var waiter = waiting.remove(message.getLong("id"));
                if (waiter != null) {
                    waiter.complete(message);
                }
            }
        } catch (IOException gone) {
        } finally {
            open = false;
            for (var waiter : waiting.values()) {
                waiter.completeExceptionally(new IOException("The viewer closed"));
            }
            waiting.clear();
        }
    }

    private CompletableFuture<JSONObject> send(JSONObject message) {
        var id = next.getAndIncrement();
        message.put("id", id);
        var waiter = new CompletableFuture<JSONObject>();
        waiting.put(id, waiter);
        try {
            synchronized (writer) {
                writer.write(message.toJSONString());
                writer.newLine();
                writer.flush();
            }
        } catch (IOException gone) {
            waiting.remove(id);
            waiter.completeExceptionally(gone);
        }
        return waiter;
    }
}
