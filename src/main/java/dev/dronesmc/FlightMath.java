package dev.dronesmc;

import net.minecraft.util.math.Vec3d;

public final class FlightMath {
    private FlightMath() { }
    public static Vec3d direction(float forward, float strafe, float vertical, float yaw) {
        double angle = Math.toRadians(yaw);
        // Positive strafe is left, matching vanilla MovementInput. At yaw 0,
        // forward is +Z and the player's left is +X.
        Vec3d vector = new Vec3d(strafe * Math.cos(angle) - forward * Math.sin(angle), vertical,
                forward * Math.cos(angle) + strafe * Math.sin(angle));
        return vector.lengthSquared() > 1 ? vector.normalize() : vector;
    }
}
