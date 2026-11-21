package dev.dronesmc;

import net.minecraft.util.math.Vec3d;
import org.junit.Test;
import static org.junit.Assert.*;

public class FlightMathTest {
    @Test public void diagonalAndVerticalSpeedAreBounded() {
        assertEquals(1, FlightMath.direction(1, 1, 1, 45).lengthSquared(), 0.000001);
    }
    @Test public void movementIsRelativeToYawNotPitch() {
        Vec3d north = FlightMath.direction(1, 0, 0, 180);
        assertEquals(-1, north.z, 0.000001);
        assertEquals(0, north.y, 0);
    }
    @Test public void releaseHoversWithoutResidualVelocity() {
        assertEquals(0, FlightMath.direction(0, 0, 0, 20).lengthSquared(), 0);
    }
    @Test public void strafeMatchesVanillaLeftAndRightAtEveryHeading() {
        // At yaw 0 the player faces south: east (+X) is left, west (-X) is right.
        assertEquals(1, FlightMath.direction(0, 1, 0, 0).x, 0.000001);
        assertEquals(-1, FlightMath.direction(0, -1, 0, 0).x, 0.000001);
        for (int yaw = 0; yaw < 360; yaw += 45) {
            Vec3d left = FlightMath.direction(0, 1, 0, yaw);
            Vec3d leftForward = FlightMath.direction(1, 0, 0, yaw - 90);
            assertEquals(leftForward.x, left.x, 0.000001);
            assertEquals(leftForward.z, left.z, 0.000001);
            Vec3d right = FlightMath.direction(0, -1, 0, yaw);
            assertEquals(-left.x, right.x, 0.000001);
            assertEquals(-left.z, right.z, 0.000001);
        }
    }
    @Test public void defaultSpeedsMatchCreativeFlightScale() {
        assertEquals(11, DroneConfig.DEFAULT_SPEED * 20, 0.001);
        assertEquals(22, DroneConfig.DEFAULT_SPRINT_SPEED * 20, 0.001);
    }
    @Test public void rejectsMalformedNetworkControls() {
        assertFalse(new ControlPacket(1, 0, Float.NaN, 0, 0, 0, 0, false).valid());
        assertFalse(new ControlPacket(1, 0, 2, 0, 0, 0, 0, false).valid());
        assertFalse(new ControlPacket(1, 5, 0, 0, 0, 0, 0, false).valid());
        assertFalse(new ControlPacket(1, 0, 0, 0, 0, Float.POSITIVE_INFINITY, 0, false).valid());
    }
}
