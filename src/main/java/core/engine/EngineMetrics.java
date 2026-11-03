package core.engine;

import java.util.concurrent.atomic.LongAdder;

/** Lock-free engine-wide counters, safe to bump from any network thread. */
public final class EngineMetrics {

    /** Point-in-time copy of all counters. */
    public record Snapshot(long connectionsAccepted, long connectionsRejected, long packetsIn, long bytesIn,
                           long packetsOut, long bytesOut, long packetsHandled, long packetsDropped,
                           long packetsRejected, long unknownOpcodes, long malformedPackets, long handlerErrors) {
    }

    private final LongAdder connectionsAccepted = new LongAdder();
    private final LongAdder connectionsRejected = new LongAdder();
    private final LongAdder packetsIn = new LongAdder();
    private final LongAdder bytesIn = new LongAdder();
    private final LongAdder packetsOut = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();
    private final LongAdder packetsHandled = new LongAdder();
    private final LongAdder packetsDropped = new LongAdder();
    private final LongAdder packetsRejected = new LongAdder();
    private final LongAdder unknownOpcodes = new LongAdder();
    private final LongAdder malformedPackets = new LongAdder();
    private final LongAdder handlerErrors = new LongAdder();

    public void connectionAccepted() {
        connectionsAccepted.increment();
    }

    public void connectionRejected() {
        connectionsRejected.increment();
    }

    public void packetIn(int bytes) {
        packetsIn.increment();
        bytesIn.add(bytes);
    }

    public void packetOut(int bytes) {
        packetsOut.increment();
        bytesOut.add(bytes);
    }

    public void packetHandled() {
        packetsHandled.increment();
    }

    public void packetDropped() {
        packetsDropped.increment();
    }

    public void packetRejected() {
        packetsRejected.increment();
    }

    public void unknownOpcode() {
        unknownOpcodes.increment();
    }

    public void malformedPacket() {
        malformedPackets.increment();
    }

    public void handlerError() {
        handlerErrors.increment();
    }

    public Snapshot snapshot() {
        return new Snapshot(connectionsAccepted.sum(), connectionsRejected.sum(), packetsIn.sum(), bytesIn.sum(),
                packetsOut.sum(), bytesOut.sum(), packetsHandled.sum(), packetsDropped.sum(), packetsRejected.sum(),
                unknownOpcodes.sum(), malformedPackets.sum(), handlerErrors.sum());
    }
}
