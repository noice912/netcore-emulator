package core.network;

import core.engine.EngineConfig;
import core.engine.EngineMetrics;
import core.support.CapturingTransport;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {

    private static SessionManager manager(EngineConfig config) {
        AtomicReference<EngineConfig> ref = new AtomicReference<>(config);
        return new SessionManager(ref::get, new EngineMetrics());
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    @Test
    void openIndexesByIdAndEndpoint() {
        SessionManager m = manager(EngineConfig.defaults());
        NetworkSession s = m.open(Protocol.TCP, loopback(5000), new CapturingTransport()).orElseThrow();

        assertEquals(1, m.activeCount());
        assertSame(s, m.find(s.id()).orElseThrow());
        assertSame(s, m.find(Protocol.TCP, loopback(5000)).orElseThrow());
        assertTrue(s.isLoopback());
    }

    @Test
    void duplicateEndpointIsRejected() {
        SessionManager m = manager(EngineConfig.defaults());
        assertTrue(m.open(Protocol.TCP, loopback(5001), new CapturingTransport()).isPresent());
        assertTrue(m.open(Protocol.TCP, loopback(5001), new CapturingTransport()).isEmpty());
        assertEquals(1, m.activeCount());
    }

    @Test
    void sameEndpointOnDifferentProtocolsCoexist() {
        SessionManager m = manager(EngineConfig.defaults());
        assertTrue(m.open(Protocol.TCP, loopback(5002), new CapturingTransport()).isPresent());
        assertTrue(m.open(Protocol.UDP, loopback(5002), new CapturingTransport()).isPresent());
        assertEquals(2, m.activeCount());
        assertEquals(1, m.count(Protocol.TCP));
        assertEquals(1, m.count(Protocol.UDP));
    }

    @Test
    void perAddressLimitIsEnforced() throws Exception {
        SessionManager m = manager(EngineConfig.parse(
                "{\"network\":{\"maxSessionsPerAddress\":2}}", "test"));
        InetAddress host = InetAddress.getLoopbackAddress();
        assertTrue(m.open(Protocol.TCP, new InetSocketAddress(host, 6001), new CapturingTransport()).isPresent());
        assertTrue(m.open(Protocol.TCP, new InetSocketAddress(host, 6002), new CapturingTransport()).isPresent());
        assertTrue(m.open(Protocol.TCP, new InetSocketAddress(host, 6003), new CapturingTransport()).isEmpty());
        assertEquals(2, m.countFor(host));
    }

    @Test
    void globalLimitIsEnforced() throws Exception {
        SessionManager m = manager(EngineConfig.parse(
                "{\"network\":{\"maxSessions\":1,\"maxSessionsPerAddress\":0}}", "test"));
        assertTrue(m.open(Protocol.TCP, loopback(6100), new CapturingTransport()).isPresent());
        assertTrue(m.open(Protocol.TCP, loopback(6101), new CapturingTransport()).isEmpty());
    }

    @Test
    void loopbackOnlyRejectsRemoteAddresses() throws Exception {
        SessionManager m = manager(EngineConfig.parse(
                "{\"network\":{\"loopbackOnly\":true}}", "test"));
        InetSocketAddress remote = new InetSocketAddress("8.8.8.8", 7000);
        assertTrue(m.open(Protocol.TCP, remote, new CapturingTransport()).isEmpty());
        assertTrue(m.open(Protocol.TCP, loopback(7001), new CapturingTransport()).isPresent());
    }

    @Test
    void closeRemovesFromEveryIndexAndNotifiesListener() {
        SessionManager m = manager(EngineConfig.defaults());
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        m.addListener(new SessionListener() {
            @Override
            public void onOpen(NetworkSession session) {
                opens.incrementAndGet();
            }

            @Override
            public void onClose(NetworkSession session, String reason) {
                closes.incrementAndGet();
            }
        });

        NetworkSession s = m.open(Protocol.TCP, loopback(8000), new CapturingTransport()).orElseThrow();
        InetAddress host = s.remoteAddress().getAddress();
        assertEquals(1, m.countFor(host));
        s.close("done");

        assertEquals(1, opens.get());
        assertEquals(1, closes.get());
        assertEquals(0, m.activeCount());
        assertTrue(m.find(s.id()).isEmpty());
        assertTrue(m.find(Protocol.TCP, loopback(8000)).isEmpty());
        assertEquals(0, m.countFor(host));
        // A freed slot can be reused.
        assertTrue(m.open(Protocol.TCP, loopback(8000), new CapturingTransport()).isPresent());
    }

    @Test
    void closeIsIdempotent() {
        SessionManager m = manager(EngineConfig.defaults());
        NetworkSession s = m.open(Protocol.TCP, loopback(8100), new CapturingTransport()).orElseThrow();
        s.close("first");
        s.close("second");
        assertEquals(0, m.activeCount());
        assertFalse(s.isOpen());
    }

    @Test
    void closeIdleOnlyClosesStaleSessions() throws InterruptedException {
        SessionManager m = manager(EngineConfig.defaults());
        NetworkSession s = m.open(Protocol.UDP, loopback(8200), new CapturingTransport()).orElseThrow();
        Thread.sleep(30);
        assertEquals(0, m.closeIdle(Protocol.UDP, 10_000, "idle")); // not idle enough
        assertEquals(1, m.closeIdle(Protocol.UDP, 10, "idle"));     // now past threshold
        assertFalse(s.isOpen());
    }
}
