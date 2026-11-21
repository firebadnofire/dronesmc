package dev.dronesmc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraft.util.EnumHand;

@Mod.EventBusSubscriber(modid = DronesMC.ID)
public final class DroneEvents {
    private static final Map<UUID, DroneEntity> SESSIONS = new HashMap<>();
    public static boolean isPiloting(EntityPlayer player) { return SESSIONS.containsKey(player.getUniqueID()); }
    public static void attach(EntityPlayerMP player, DroneEntity drone) { SESSIONS.put(player.getUniqueID(), drone); }
    public static void detach(EntityPlayerMP player, DroneEntity drone) { SESSIONS.remove(player.getUniqueID(), drone); }
    public static void switchDrone(EntityPlayerMP player, DroneEntity target, int session) {
        DroneEntity current = SESSIONS.get(player.getUniqueID());
        if (!target.canRemotePilot(player) || (current != null && current.sessionId() != session)) return;
        if (current != null) current.exitPilot();
        target.processInitialInteract(player, EnumHand.MAIN_HAND);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void death(LivingDeathEvent event) {
        if (!(event.getEntityLiving() instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.getEntityLiving();
        DroneEntity drone = SESSIONS.get(player.getUniqueID());
        if (drone != null) drone.landAfterDeath(player);
    }
    @SubscribeEvent public static void tick(TickEvent.PlayerTickEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        DroneEntity drone = SESSIONS.get(event.player.getUniqueID());
        if (drone != null) drone.holdPlayer((EntityPlayerMP) event.player);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        DroneEntity drone = SESSIONS.get(event.player.getUniqueID());
        if (drone != null) drone.landAfterDisconnect(event.player);
    }
    @SubscribeEvent public static void unload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) return;
        for (DroneEntity drone : SESSIONS.values().toArray(new DroneEntity[0])) {
            if (drone.world == event.getWorld()) drone.queueLanding();
        }
    }
    @SubscribeEvent public static void unloadChunk(ChunkEvent.Unload event) {
        if (event.getWorld().isRemote) return;
        for (DroneEntity drone : SESSIONS.values().toArray(new DroneEntity[0])) {
            if (drone.world == event.getWorld() && drone.chunkCoordX == event.getChunk().x
                    && drone.chunkCoordZ == event.getChunk().z) drone.queueLanding();
        }
    }
}
