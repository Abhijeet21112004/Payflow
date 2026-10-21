package com.payflow.webhook;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/** A tiny local HTTP server that records every webhook it receives and answers with a chosen status code. */
final class FakeMerchantServer implements AutoCloseable {

    record Received(Map<String, String> headers, String body) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    /** @param failFirst how many requests to answer with 500 before answering 200 (use a big number to always fail) */
    FakeMerchantServer(int failFirst) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/webhooks", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(Map.of(
                    "event-id", exchange.getRequestHeaders().getFirst("X-PayFlow-Event-Id"),
                    "timestamp", exchange.getRequestHeaders().getFirst("X-PayFlow-Timestamp"),
                    "signature", exchange.getRequestHeaders().getFirst("X-PayFlow-Signature")), body));
            int status = calls.incrementAndGet() <= failFirst ? 500 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/webhooks";
    }

    List<Received> received() {
        return received;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
