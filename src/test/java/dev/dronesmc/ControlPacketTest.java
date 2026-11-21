package dev.dronesmc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;
import static org.junit.Assert.*;

public class ControlPacketTest {
    @Test public void sequenceAndSessionSurviveWireEncoding() {
        ControlPacket sent = new ControlPacket(42, 0, 1, -1, 0, 80, -25, true, 17, 3);
        ByteBuf buffer = Unpooled.buffer();
        try {
            sent.toBytes(buffer);
            ControlPacket received = new ControlPacket(); received.fromBytes(buffer);
            assertTrue(received.valid());
            assertEquals(17, received.sequence); assertEquals(3, received.session);
            assertEquals(42, received.entity); assertEquals(-1, received.strafe, 0);
            assertTrue(received.sprint);
        } finally { buffer.release(); }
    }
    @Test public void rejectsNegativeSequenceAndAcceptsExplicitSwitchActions() {
        assertFalse(new ControlPacket(1, 0, 0, 0, 0, 0, 0, false, -1, 1).valid());
        assertTrue(new ControlPacket(1, 3, 0, 0, 0, 0, 0, false).valid());
        assertTrue(new ControlPacket(1, 4, 0, 0, 0, 0, 0, false).valid());
    }
}
