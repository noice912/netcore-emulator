package core.admin;

import core.engine.ServerEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AdminHttpServerTest {

    private ServerEngine engine;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void startEngine(@TempDir Path dir) throws Exception {
        String captures = dir.resolve("captures").toString().replace('\\', '/');
        String json = """
                {
                  "network": { "bindAddress": "127.0.0.1", "dispatch": { "port": 0 }, "gateway": { "port": 0 } },
                  "admin": { "enabled": true, "bindAddress": "127.0.0.1", "port": 0 },
                  "persistence": { "enabled": false },
                  "schemas": { "enabled": false },
                  "capture": { "directory": "%s" },
                  "engine": { "consoleEnabled": false, "configWatchEnabled": false, "pluginsEnabled": false }
                }
                """.formatted(captures);
        Path cfg = dir.resolve("config.json");
        Files.writeString(cfg, json, StandardCharsets.UTF_8);
        engine = ServerEngine.create(cfg);
        engine.start();
        assertTrue(engine.adminPort() > 0, "admin API should be bound");
    }

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + engine.adminPort() + path);
    }

    @Test
    void statusIsJsonWithEngineIdentity() throws Exception {
        HttpResponse<String> r = get("/status");
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(r.body().contains("\"name\": \"netcore-emulator\""), r.body());
        assertTrue(r.body().contains("\"uptimeMillis\""), r.body());
        assertTrue(r.body().contains("\"tick\""), r.body());
    }

    @Test
    void metricsHaveJsonAndPrometheusForms() throws Exception {
        assertEquals(200, get("/metrics").statusCode());
        HttpResponse<String> prom = get("/metrics/prometheus");
        assertEquals(200, prom.statusCode());
        assertTrue(prom.body().contains("netcore_up 1\n"), prom.body());
        assertTrue(prom.body().contains("netcore_packets_in_total "), prom.body());
    }

    @Test
    void readOnlyEndpointsRespond() throws Exception {
        for (String path : new String[]{"/", "/config", "/schemas", "/plugins", "/tick", "/memory", "/sessions"}) {
            assertEquals(200, get(path).statusCode(), path);
        }
        assertTrue(get("/config").body().contains("\"dispatch\""));
    }

    @Test
    void sessionsEndpointListsALiveConnection() throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), engine.dispatchPort())) {
            long deadline = System.currentTimeMillis() + 5_000;
            while (engine.sessions().activeCount() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            HttpResponse<String> r = get("/sessions");
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("\"protocol\": \"TCP\""), r.body());
            assertTrue(socket.isConnected());
        }
    }

    @Test
    void reloadAndSchemaReloadRunViaPost() throws Exception {
        HttpResponse<String> reload = post("/reload");
        assertEquals(200, reload.statusCode());
        assertTrue(reload.body().contains("UNCHANGED"), reload.body());
        assertEquals(200, post("/schemas/reload").statusCode());
    }

    @Test
    void captureCanBeToggled() throws Exception {
        HttpResponse<String> start = post("/capture/start");
        assertEquals(200, start.statusCode());
        assertTrue(start.body().contains("\"recording\": true"), start.body());
        assertTrue(engine.recorder().isRecording());
        HttpResponse<String> stop = post("/capture/stop");
        assertEquals(200, stop.statusCode());
        assertTrue(!engine.recorder().isRecording());
    }

    @Test
    void errorsAreReportedWithProperStatusCodes() throws Exception {
        assertEquals(404, get("/nope").statusCode());
        assertEquals(404, post("/nope").statusCode());
        assertEquals(404, post("/sessions/999999/kick").statusCode());
        assertEquals(409, post("/save").statusCode(), "persistence is disabled in this test");
        assertEquals(400, post("/broadcast").statusCode());
        HttpResponse<String> delete = http.send(HttpRequest.newBuilder(uri("/status")).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, delete.statusCode());
        assertEquals("GET, POST", delete.headers().firstValue("Allow").orElse(""));
    }

    @Test
    void broadcastReportsRecipientCount() throws Exception {
        HttpResponse<String> r = post("/broadcast?text=hello%20all");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"sent\": 0"), r.body());
    }
}
