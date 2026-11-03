package core.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;

/**
 * One wire unit: a 16-bit opcode plus a reference-counted payload.
 *
 * <p>Frames are what the codec produces from bytes and what the transports accept for sending.
 * The payload is owned by the frame; releasing the frame releases the payload. Inbound frames are
 * released by the network handler after dispatch, outbound frames by the transport after writing.
 */
public final class Frame extends DefaultByteBufHolder {

    private final int opcode;

    public Frame(int opcode, ByteBuf payload) {
        super(payload);
        if (opcode < 0 || opcode > 0xFFFF) {
            payload.release();
            throw new IllegalArgumentException("opcode out of range (0..65535): " + opcode);
        }
        this.opcode = opcode;
    }

    public int opcode() {
        return opcode;
    }

    public int payloadLength() {
        return content().readableBytes();
    }

    @Override
    public Frame copy() {
        return replace(content().copy());
    }

    @Override
    public Frame duplicate() {
        return replace(content().duplicate());
    }

    @Override
    public Frame retainedDuplicate() {
        return replace(content().retainedDuplicate());
    }

    @Override
    public Frame replace(ByteBuf content) {
        return new Frame(opcode, content);
    }

    @Override
    public Frame retain() {
        super.retain();
        return this;
    }

    @Override
    public Frame retain(int increment) {
        super.retain(increment);
        return this;
    }

    @Override
    public Frame touch() {
        super.touch();
        return this;
    }

    @Override
    public Frame touch(Object hint) {
        super.touch(hint);
        return this;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Frame other && other.opcode == opcode && super.equals(o);
    }

    @Override
    public int hashCode() {
        return 31 * opcode + super.hashCode();
    }

    @Override
    public String toString() {
        return String.format("Frame{opcode=0x%04X, length=%d}", opcode, payloadLength());
    }
}
