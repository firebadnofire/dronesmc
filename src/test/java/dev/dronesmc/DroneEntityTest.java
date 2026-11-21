package dev.dronesmc;

import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class DroneEntityTest {
    @BeforeClass public static void initializeVanilla() { Bootstrap.register(); }

    @Test public void networkPositionUpdatesInterpolateInsteadOfSnapping() {
        DroneEntity drone = new DroneEntity(null);
        drone.setPosition(0, 10, 0);
        drone.setPositionAndRotationDirect(3, 13, 6, 0, 0, 3, false);
        assertEquals(0, drone.posX, 0);
        drone.interpolate();
        assertEquals(1, drone.posX, 0.000001);
        assertEquals(11, drone.posY, 0.000001);
        assertEquals(2, drone.posZ, 0.000001);
        drone.interpolate(); drone.interpolate();
        assertEquals(3, drone.posX, 0.000001);
        assertEquals(13, drone.posY, 0.000001);
        assertEquals(6, drone.posZ, 0.000001);
    }

    @Test public void rotationInterpolationTakesShortPathAcrossNorth() {
        DroneEntity drone = new DroneEntity(null);
        drone.rotationYaw = 179;
        drone.setPositionAndRotationDirect(0, 0, 0, -179, 0, 2, false);
        drone.interpolate();
        assertEquals(180, drone.rotationYaw, 0.001);
    }

    @Test public void locallyPredictedDroneDoesNotWaitForRemoteInterpolation() {
        DroneEntity drone = new DroneEntity(null);
        drone.setPosition(1, 5, 1);
        drone.setClientControlled(true);
        drone.setPositionAndRotationDirect(0, 5, 0, 0, 0, 3, false);
        drone.interpolate();
        assertEquals(1, drone.posX, 0);
        drone.setClientControlled(false);
        drone.interpolate();
        assertTrue(drone.posX < 1);
    }

    @Test public void savesCustomNameWithoutPermanentOwnership() {
        DroneEntity drone = new DroneEntity(null);
        drone.setCustomNameTag("Survey Two");
        NBTTagCompound saved = drone.writeToNBT(new NBTTagCompound());
        assertFalse(saved.hasUniqueId("owner"));
        assertEquals("Survey Two", saved.getString("CustomName"));
    }

    @Test public void pendingLandingSurvivesSaveReloadWithoutRetainingPilot() {
        DroneEntity drone = new DroneEntity(null);
        drone.setPosition(12.5, 90, -8.5);
        drone.queueLanding();
        NBTTagCompound saved = new NBTTagCompound();
        drone.writeEntityToNBT(saved);
        assertTrue(saved.getBoolean("resumeLanding"));
        assertEquals(12.5, saved.getDouble("landingHomeX"), 0);
        DroneEntity restored = new DroneEntity(null);
        restored.readEntityFromNBT(saved);
        assertEquals(-1, restored.pilotId());
        NBTTagCompound resaved = new NBTTagCompound();
        restored.writeEntityToNBT(resaved);
        assertTrue(resaved.getBoolean("resumeLanding"));
        assertEquals(-8.5, resaved.getDouble("landingHomeZ"), 0);
    }

    @Test public void landedDroneDoesNotResumeStaleLandingRequest() {
        NBTTagCompound saved = new NBTTagCompound();
        saved.setBoolean("landed", true);
        saved.setBoolean("resumeLanding", true);
        DroneEntity drone = new DroneEntity(null);
        drone.readEntityFromNBT(saved);
        NBTTagCompound resaved = new NBTTagCompound();
        drone.writeEntityToNBT(resaved);
        assertEquals(DroneEntity.State.LANDED, drone.state());
        assertFalse(resaved.getBoolean("resumeLanding"));
    }
}
