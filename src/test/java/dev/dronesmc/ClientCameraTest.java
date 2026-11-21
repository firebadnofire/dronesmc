package dev.dronesmc;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClientCameraTest {
    @Test public void correctedBodyPitchIsNotCameraInput() {
        assertEquals(12, ClientProxy.cameraPitchAfterMouse(12, -80, -80), 0);
        assertEquals(15, ClientProxy.cameraPitchAfterMouse(12, -80, -77), 0);
        assertEquals(-90, ClientProxy.cameraPitchAfterMouse(-89, 0, -5), 0);
        assertEquals(90, ClientProxy.cameraPitchAfterMouse(89, 0, 5), 0);
    }
    @Test public void cameraInsideBodyOrArmorDoesNotRenderLocalBody() {
        AxisAlignedBB body = new AxisAlignedBB(10, 64, 20, 10.6, 65.8, 20.6);
        assertFalse(ClientProxy.shouldRenderBody(new Vec3d(10.3, 65.6, 20.3), body));
        assertFalse(ClientProxy.shouldRenderBody(new Vec3d(10.8, 65, 20.3), body));
        assertTrue(ClientProxy.shouldRenderBody(new Vec3d(11, 65, 20.3), body));
        assertTrue(ClientProxy.shouldRenderBody(new Vec3d(10.3, 66.2, 20.3), body));
    }

    @Test public void overlapCheckUsesWorldCoordinatesWithoutChangingBodyBounds() {
        AxisAlignedBB body = new AxisAlignedBB(-100, 70, 240, -99.4, 71.8, 240.6);
        assertFalse(ClientProxy.shouldRenderBody(new Vec3d(-99.7, 71, 240.3), body));
        assertTrue(ClientProxy.shouldRenderBody(new Vec3d(0, 71, 0), body));
        assertEquals(-100, body.minX, 0);
        assertEquals(240.6, body.maxZ, 0);
    }
}
