package com.rost.cruma.viewer.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.AgentActivity.Entry;
import com.rost.cruma.viewer.agent.AgentFrames.Call;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class AgentBridge implements AutoCloseable {
    private static final int TOKEN_BYTES = 32;
    private static final int MOST_HELLO_BYTES = 256;
    private static final int HELLO_MILLIS = 3000;
    private static final int MOST_STRANGERS = 8;
    private static final Logger log = LogManager.getLogger(AgentBridge.class);

    private final AgentRuntime runtime;
    private final Path folder;
    private final String client;
    private final List<String> tiles;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final AtomicInteger strangers = new AtomicInteger();
    private ServerSocket server;
    private ViewerEndpoint endpoint;

    AgentBridge(AgentRuntime runtime, Path folder, String client, List<String> tiles) {
        this.runtime = runtime;
        this.folder = folder;
        this.client = client;
        this.tiles = tiles;
    }

    void open() throws IOException {
        var token = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(token);
        server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        endpoint = new ViewerEndpoint(
                ProcessHandle.current().pid(),
                server.getLocalPort(),
                HexFormat.of().formatHex(token),
                client,
                tiles,
                runtime.isHidden(),
                runtime.isOwned(),
                runtime.isHeadless(),
                ViewerEndpoint.ownVersion());
        try {
            endpoint.write(folder);
        } catch (IOException failed) {
            server.close();
            throw failed;
        }
        Thread.ofPlatform().name("cruma-agent-socket").daemon().start(this::accept);
        log.info("This viewer takes agents on port {}", endpoint.port());
    }

    @Override
    public void close() {
        try {
            server.close();
            for (var socket : connections) {
                socket.close();
            }
            Files.deleteIfExists(endpoint.file(folder));
        } catch (IOException failed) {
            log.warn("Could not close the socket cleanly", failed);
        }
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                var socket = server.accept();
                if (strangers.incrementAndGet() > MOST_STRANGERS) {
                    strangers.decrementAndGet();
                    socket.close();
                    continue;
                }
                connections.add(socket);
                if (server.isClosed()) {
                    socket.close();
                    return;
                }
                Thread.ofPlatform().name("cruma-agent-link").daemon().start(() -> serve(socket));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        var counted = false;
        var stranger = true;
        try (socket;
                var writer =
                        new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(HELLO_MILLIS);
            if (!isAdmitted(hello(socket.getInputStream()))) {
                return;
            }
            socket.setSoTimeout(0);
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            strangers.decrementAndGet();
            stranger = false;
            var answers = new Outbox(writer);
            answers.add(JSON.parseObject("{\"ok\":true}"));
            runtime.joined();
            counted = true;
            var calls = new ConcurrentHashMap<Long, Call>();
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    handle(JSON.parseObject(line), answers, calls);
                }
            } finally {
                answers.close();
            }
        } catch (IOException | RuntimeException gone) {
            log.debug("A connection ended: {}", gone.toString());
        } finally {
            if (stranger) {
                strangers.decrementAndGet();
            }
            connections.remove(socket);
            if (counted) {
                runtime.left();
            }
        }
    }

    private boolean isAdmitted(@Nullable String line) {
        if (line == null) {
            return false;
        }
        var token = JSON.parseObject(line).getString("token");
        return token != null
                && MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8), endpoint.token().getBytes(StandardCharsets.UTF_8));
    }

    private void handle(JSONObject message, Outbox answers, Map<Long, Call> calls) {
        runtime.contact();
        var id = message.getLongValue("id");
        if (message.containsKey("call")) {
            runtime.readAccess();
            var call = runtime.getFrames().submit(message.getString("call"), message.getJSONObject("args"));
            calls.put(id, call);
            call.getResult().thenAccept(result -> {
                calls.remove(id);
                runtime.contact();
                var answer = new JSONObject();
                answer.put("id", id);
                answer.put("result", result.write());
                if (result.getFolder() != null) {
                    answer.put("folder", result.getFolder().toString());
                }
                answers.add(answer);
            });
        } else if (message.containsKey("cancel")) {
            var call = calls.get(message.getLong("cancel"));
            if (call != null) {
                call.cancel();
            }
        } else if (message.containsKey("status")) {
            var answer = new JSONObject();
            answer.put("id", id);
            answer.put("status", runtime.getStatus());
            answers.add(answer);
        } else if (message.containsKey("note")) {
            var note = message.getJSONObject("note");
            var folder = note.getString("folder");
            runtime.getActivity()
                    .add(new Entry(
                            LocalTime.now(),
                            note.getString("tool"),
                            note.getString("summary"),
                            note.getBooleanValue("failed"),
                            folder == null ? null : Path.of(folder)));
        } else if (message.containsKey("shutdown")) {
            runtime.askShutdown();
        }
    }

    private static @Nullable String hello(InputStream input) throws IOException {
        var line = new ByteArrayOutputStream();
        while (line.size() < MOST_HELLO_BYTES) {
            var next = input.read();
            if (next < 0) {
                return null;
            }
            if (next == '\n') {
                return line.toString(StandardCharsets.UTF_8);
            }
            line.write(next);
        }
        return null;
    }

    private static final class Outbox {
        private final BlockingQueue<JSONObject> waiting = new LinkedBlockingQueue<>();
        private final Thread thread;
        private volatile boolean closed;

        Outbox(BufferedWriter writer) {
            thread = Thread.ofPlatform().name("cruma-agent-answers").daemon().start(() -> write(writer));
        }

        void add(JSONObject message) {
            if (!closed) {
                waiting.add(message);
            }
        }

        void close() {
            closed = true;
            thread.interrupt();
            waiting.clear();
        }

        private void write(BufferedWriter writer) {
            try {
                while (!closed) {
                    var message = waiting.take();
                    writer.write(message.toJSONString());
                    writer.newLine();
                    writer.flush();
                }
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            } catch (IOException gone) {
                log.debug("An answer found its connection closed");
            } finally {
                closed = true;
                waiting.clear();
            }
        }
    }
}
