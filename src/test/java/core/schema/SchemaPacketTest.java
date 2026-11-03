package core.schema;

import core.handlers.PacketDecodeException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaPacketTest {

    static final String SCHEMA = """
            {
              "structs": {
                "Vec3": [ {"name":"x","type":"f32"}, {"name":"y","type":"f32"}, {"name":"z","type":"f32"} ],
                "Item": [ {"name":"id","type":"u32"}, {"name":"count","type":"u16"} ]
              },
              "packets": [ { "name": "Everything", "opcode": "0x0300", "fields": [
                {"name":"u8","type":"u8"},   {"name":"i8","type":"i8"},   {"name":"u16","type":"u16"},
                {"name":"i16","type":"i16"}, {"name":"u32","type":"u32"}, {"name":"i32","type":"i32"},
                {"name":"i64","type":"i64"}, {"name":"f32","type":"f32"}, {"name":"f64","type":"f64"},
                {"name":"flag","type":"bool"}, {"name":"name","type":"string"}, {"name":"blob","type":"bytes"},
                {"name":"pos","type":"struct","struct":"Vec3"},
                {"name":"items","type":"array","element":"struct","struct":"Item"},
                {"name":"ids","type":"array","element":"u16"}
              ] } ]
            }
            """;

    private static SchemaSet set() throws SchemaException {
        return SchemaLoader.parse(SCHEMA, "test");
    }

    private static SchemaPacket packet(SchemaSet set) {
        return new SchemaPacket(set.packet("Everything").orElseThrow(), set);
    }

    @Test
    void roundTripsEveryFieldType() throws Exception {
        SchemaSet set = set();
        SchemaPacket p = packet(set)
                .set("u8", 255).set("i8", -5).set("u16", 65535).set("i16", -300)
                .set("u32", 4_000_000_000L).set("i32", -70_000).set("i64", Long.MIN_VALUE)
                .set("f32", 1.5).set("f64", Math.PI).set("flag", true).set("name", "héllo")
                .set("blob", "deadbeef")
                .set("pos", Map.of("x", 1.0, "y", 2.0, "z", 3.0))
                .set("items", List.of(Map.of("id", 7, "count", 2), Map.of("id", 9, "count", 1)))
                .set("ids", List.of(1, 2, 3));

        ByteBuf wire = Unpooled.buffer();
        p.encode(wire);
        SchemaPacket q = packet(set);
        q.decode(wire);
        assertEquals(0, wire.readableBytes(), "decoder consumes exactly what the encoder wrote");

        assertEquals(255L, q.getLong("u8"));
        assertEquals(-5L, q.getLong("i8"));
        assertEquals(65535L, q.getLong("u16"));
        assertEquals(-300L, q.getLong("i16"));
        assertEquals(4_000_000_000L, q.getLong("u32"));
        assertEquals(-70_000L, q.getLong("i32"));
        assertEquals(Long.MIN_VALUE, q.getLong("i64"));
        assertEquals(1.5, q.getDouble("f32"), 1e-6);
        assertEquals(Math.PI, q.getDouble("f64"), 1e-12);
        assertTrue(q.getBoolean("flag"));
        assertEquals("héllo", q.getString("name"));
        assertArrayEquals(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF}, (byte[]) q.get("blob"));
        assertEquals(2.0, q.getDouble("pos.y"), 1e-6);
        assertEquals(9L, q.getLong("items.1.id"));
        assertEquals(2L, q.getLong("items.0.count"));
        assertEquals(3L, q.getLong("ids.2"));
        assertEquals(0x0300, q.opcode());
        wire.release();
    }

    @Test
    void defaultsAreZeroValued() throws Exception {
        SchemaPacket p = packet(set());
        assertEquals(0L, p.getLong("u32"));
        assertEquals("", p.getString("name"));
        assertEquals(0.0, p.getDouble("pos.x"));
        assertTrue(((List<?>) p.get("items")).isEmpty());
        assertEquals(0, ((byte[]) p.get("blob")).length);
    }

    @Test
    void truncatedPayloadIsADecodeError() throws Exception {
        SchemaSet set = set();
        ByteBuf wire = Unpooled.buffer();
        packet(set).set("name", "something long enough").encode(wire);
        ByteBuf truncated = wire.copy(0, 5);
        PacketDecodeException e =
                assertThrows(PacketDecodeException.class, () -> packet(set).decode(truncated));
        assertTrue(e.errorOffset() >= 0, "decode failure should carry a byte offset, was " + e.errorOffset());
        wire.release();
        truncated.release();
    }

    @Test
    void outOfRangeIntegerFailsAtEncode() throws Exception {
        SchemaPacket p = packet(set()).set("u8", 300);
        ByteBuf wire = Unpooled.buffer();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> p.encode(wire));
        assertTrue(e.getMessage().contains("u8"));
        wire.release();
    }

    @Test
    void unknownFieldAndWrongShapesAreRejected() throws Exception {
        SchemaPacket p = packet(set());
        assertThrows(IllegalArgumentException.class, () -> p.set("nope", 1));
        assertThrows(IllegalArgumentException.class, () -> p.set("pos", "not an object"));
        assertThrows(IllegalArgumentException.class, () -> p.set("ids", "not a list"));
        assertThrows(IllegalArgumentException.class, () -> p.set("u16", "abc"));
    }

    @Test
    void looseInputsAreCoerced() throws Exception {
        SchemaPacket p = packet(set())
                .set("u16", "0x10")      // hex string
                .set("flag", "yes")      // truthy string
                .set("i64", 12.0)        // float to integer
                .set("name", 123)        // number to string
                .set("blob", List.of(1, 2, 255));
        assertEquals(16L, p.getLong("u16"));
        assertTrue(p.getBoolean("flag"));
        assertEquals(12L, p.getLong("i64"));
        assertEquals("123", p.getString("name"));
        assertArrayEquals(new byte[]{1, 2, (byte) 255}, (byte[]) p.get("blob"));
    }

    @Test
    void toStringAndJsonTreeDescribeValues() throws Exception {
        SchemaPacket p = packet(set()).set("name", "x").set("ids", List.of(4));
        assertTrue(p.toString().startsWith("Everything{"));
        assertEquals("x", p.toJsonTree().getAsJsonObject().get("name").getAsString());
        assertEquals(4, p.toJsonTree().getAsJsonObject().getAsJsonArray("ids").get(0).getAsInt());
    }
}
