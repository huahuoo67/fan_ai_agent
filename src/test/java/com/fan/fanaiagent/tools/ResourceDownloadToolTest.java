package com.fan.fanaiagent.tools;

import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ResourceDownloadToolTest {

    @Test
    public void testDownloadResource() throws Exception {
        byte[] body = "Fan download fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixture", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        String fileName = "fan-test-" + UUID.randomUUID() + ".txt";
        Path output = Path.of("tmp", "download", fileName);
        try {
            server.start();
            String result = new ResourceDownloadTool().downloadResource(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/fixture", fileName);
            assertTrue(result.startsWith("Resource downloaded successfully"), result);
            assertArrayEquals(body, Files.readAllBytes(output));
        } finally {
            server.stop(0);
            Files.deleteIfExists(output);
        }
    }
}
