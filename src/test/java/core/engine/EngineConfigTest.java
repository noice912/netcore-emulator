package core.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineConfigTest {

    @Test
    void defaultsAreValid() {
        EngineConfig cfg = EngineConfig.defaults();
        assertEquals("0.0.0.0", cfg.network().bindAddress());
        assertEquals(22101, cfg.network().dispatch().port());
        assertEquals(22102, cfg.network().gateway().port());
        assertTrue(cfg.network().dispatch().enabled());
        assertFalse(cfg.security().requireHandshake());
    }

    @Test
    void partialFileOverridesOnlyGivenKeys() throws Exception {
        EngineConfig cfg = EngineConfig.parse("{\"network\":{\"dispatch\":{\"port\":9000}}}", "test");
        assertEquals(9000, cfg.network().dispatch().port());
        assertEquals(22102, cfg.network().gateway().port()); // untouched default
        assertEquals("0.0.0.0", cfg.network().bindAddress());
    }

    @Test
    void dispatchBindAddressFallsBackToNetworkDefault() throws Exception {
        EngineConfig cfg = EngineConfig.parse(
                "{\"network\":{\"bindAddress\":\"127.0.0.1\",\"gateway\":{\"bindAddress\":\"0.0.0.0\"}}}", "test");
        assertEquals("127.0.0.1", cfg.network().dispatchBindAddress());
        assertEquals("0.0.0.0", cfg.network().gatewayBindAddress());
    }

    @Test
    void invalidPortIsRejected() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> EngineConfig.parse("{\"network\":{\"dispatch\":{\"port\":70000}}}", "cfg"));
        assertTrue(e.getMessage().contains("dispatch.port"));
    }

    @Test
    void bothListenersDisabledIsRejected() {
        assertThrows(ConfigException.class, () -> EngineConfig.parse(
                "{\"network\":{\"dispatch\":{\"enabled\":false},\"gateway\":{\"enabled\":false}}}", "cfg"));
    }

    @Test
    void unknownKeysAreIgnoredNotFatal() throws Exception {
        EngineConfig cfg = EngineConfig.parse(
                "{\"network\":{\"dispatch\":{\"port\":9100}},\"bogusKey\":123}", "cfg");
        assertEquals(9100, cfg.network().dispatch().port());
    }

    @Test
    void encryptionEnabledWithoutKeyIsRejected() {
        assertThrows(ConfigException.class, () -> EngineConfig.parse(
                "{\"security\":{\"encryption\":{\"enabled\":true}}}", "cfg"));
    }

    @Test
    void encryptionKeyIsDecodedFromHex() throws Exception {
        EngineConfig cfg = EngineConfig.parse(
                "{\"security\":{\"encryption\":{\"enabled\":true,\"keyHex\":\"00ff10\"}}}", "cfg");
        assertArrayEquals(new byte[]{0x00, (byte) 0xFF, 0x10}, cfg.security().encryption().keyOrNull());
    }

    @Test
    void disabledEncryptionHasNoKey() {
        EngineConfig cfg = EngineConfig.defaults();
        org.junit.jupiter.api.Assertions.assertNull(cfg.security().encryption().keyOrNull());
    }

    @Test
    void oddLengthHexIsRejected() {
        assertThrows(ConfigException.class, () -> EngineConfig.parse(
                "{\"security\":{\"encryption\":{\"enabled\":true,\"keyHex\":\"abc\"}}}", "cfg"));
    }

    @Test
    void emptyDocumentIsRejected() {
        assertThrows(ConfigException.class, () -> EngineConfig.parse("   ", "cfg"));
    }

    @Test
    void nonObjectTopLevelIsRejected() {
        assertThrows(ConfigException.class, () -> EngineConfig.parse("[1,2,3]", "cfg"));
    }

    @Test
    void toJsonRoundTripsBackToAnEqualConfig() throws Exception {
        EngineConfig original = EngineConfig.parse(
                "{\"network\":{\"dispatch\":{\"port\":9000}},\"security\":{\"requireHandshake\":true}}", "cfg");
        EngineConfig reparsed = EngineConfig.parse(original.toJson(), "roundtrip");
        assertEquals(original.toJson(), reparsed.toJson());
        assertEquals(9000, reparsed.network().dispatch().port());
        assertTrue(reparsed.security().requireHandshake());
    }

    @Test
    void protocolVersionAllowListWorks() throws Exception {
        EngineConfig cfg = EngineConfig.parse(
                "{\"security\":{\"allowedProtocolVersions\":[2,5]}}", "cfg");
        assertTrue(cfg.security().isProtocolVersionAllowed(2));
        assertTrue(cfg.security().isProtocolVersionAllowed(5));
        assertFalse(cfg.security().isProtocolVersionAllowed(1));
    }
}
