package dev.dronesmc;

import java.io.File;
import net.minecraftforge.common.config.Configuration;

public final class DroneConfig {
    public static final float DEFAULT_SPEED = 0.55F, DEFAULT_SPRINT_SPEED = 1.1F;
    public static double speed, sprintSpeed;
    public static int landingRadius;
    private DroneConfig() { }
    public static void load(File file) {
        Configuration config = new Configuration(file);
        config.load();
        speed = config.getFloat("normalSpeed", "flight", DEFAULT_SPEED, 0.05F, 1F,
                "Blocks per server tick. Default approximates Creative flight (11 blocks/second).");
        sprintSpeed = config.getFloat("sprintSpeed", "flight", DEFAULT_SPRINT_SPEED, 0.05F, 2F,
                "Blocks per server tick while sprinting. Default approximates Creative sprint flight (22 blocks/second).");
        landingRadius = config.getInt("landingRadius", "landing", 3, 1, 16, "Horizontal landing radius in blocks.");
        if (config.hasChanged()) config.save();
    }
}
