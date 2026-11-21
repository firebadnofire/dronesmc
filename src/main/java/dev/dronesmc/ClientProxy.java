package dev.dronesmc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBat;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import net.minecraftforge.fml.common.eventhandler.EventPriority;

public final class ClientProxy extends CommonProxy {
    private final KeyBinding exit = new KeyBinding("key.dronesmc.exit", Keyboard.KEY_R, "key.categories.dronesmc");
    private DroneEntity active;
    private float bodyYaw, bodyPitch;
    private float cameraYaw, cameraPitch;
    private boolean landRequested;
    private boolean releaseInteractions;
    private int exitingEntity = -1;
    private int selectedEntity = -1, sequence;
    private final Deque<ControlPacket> pendingMoves = new ArrayDeque<>();
    private double flightSpeed = DroneConfig.DEFAULT_SPEED, sprintSpeed = DroneConfig.DEFAULT_SPRINT_SPEED;
    @Override public void preInit() {
        MinecraftForge.EVENT_BUS.register(this);
        ClientRegistry.registerKeyBinding(exit);
        RenderingRegistry.registerEntityRenderingHandler(DroneEntity.class, BatRenderer::new);
    }
    @SubscribeEvent public void models(ModelRegistryEvent event) {
        ModelLoader.setCustomModelResourceLocation(DronesMC.DRONE, 0, new ModelResourceLocation("dronesmc:drone", "inventory"));
    }
    private boolean ctrl() { return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL); }
    @SubscribeEvent public void key(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (active == null) return;
        if (ctrl() && Keyboard.isKeyDown(Keyboard.KEY_E)) {
            landRequested = true;
            // Remove the queued vanilla inventory keypress before Minecraft processes it.
            while (mc.gameSettings.keyBindInventory.isPressed()) { }
        }
    }
    @SubscribeEvent public void gui(GuiOpenEvent event) {
        if (active != null && ctrl() && Keyboard.isKeyDown(Keyboard.KEY_E)) {
            landRequested = true; event.setCanceled(true);
        }
    }
    @SubscribeEvent public void mouse(MouseEvent event) {
        if ((active != null || releaseInteractions) && event.getButton() >= 0) {
            clearInteractions(Minecraft.getMinecraft());
            event.setCanceled(true);
        }
    }
    private void clearInteractions(Minecraft mc) {
        clearInteractions(mc.gameSettings.keyBindAttack, mc.gameSettings.keyBindUseItem, mc.gameSettings.keyBindPickBlock);
    }
    static void clearInteractions(KeyBinding... bindings) {
        for (KeyBinding binding : bindings) {
            KeyBinding.setKeyBindState(binding.getKeyCode(), false);
            while (binding.isPressed()) { }
        }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public void overlayCamera(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (active != null && mc.getRenderViewEntity() == active) {
            // Overlay mods read these direction vectors instead of CameraSetup angles.
            ActiveRenderInfo.updateRenderInfo(active, mc.gameSettings.thirdPersonView == 2);
        }
    }
    @SubscribeEvent public void hand(RenderHandEvent event) { if (active != null) event.setCanceled(true); }
    @SubscribeEvent public void body(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (active == null || mc.player == null || !mc.player.isEntityAlive()) return;
        float partial = event.getPartialTicks();
        RenderManager manager = mc.getRenderManager();
        Entity previousView = manager.renderViewEntity;
        float previousLightX = OpenGlHelper.lastBrightnessX;
        float previousLightY = OpenGlHelper.lastBrightnessY;
        GlStateManager.pushMatrix();
        mc.entityRenderer.enableLightmap();
        RenderHelper.enableStandardItemLighting();
        try {
            // RenderPlayer intentionally skips the local body with a remote view entity.
            // Render it once explicitly, without changing the actual gameplay camera.
            manager.renderViewEntity = mc.player;
            // The normal entry point supplies this entity's lightmap brightness and
            // resets the tint, rather than inheriting the last world render's state.
            manager.renderEntityStatic(mc.player, partial, false);
        } finally {
            manager.renderViewEntity = previousView;
            RenderHelper.disableStandardItemLighting();
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, previousLightX, previousLightY);
            GlStateManager.color(1, 1, 1, 1);
            mc.entityRenderer.disableLightmap();
            GlStateManager.popMatrix();
        }
    }
    @SubscribeEvent public void camera(EntityViewRenderEvent.CameraSetup event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (active == null || mc.player == null || event.getEntity() != active) return;
        // Vanilla applies mouse input immediately before CameraSetup, once per frame.
        // Keep this local view independent of the server's delayed rotation echo.
        captureLook(mc);
        event.setYaw(cameraYaw + 180);
        event.setPitch(cameraPitch);
    }
    private void captureLook(Minecraft mc) {
        cameraYaw = net.minecraft.util.math.MathHelper.wrapDegrees(cameraYaw
                + net.minecraft.util.math.MathHelper.wrapDegrees(mc.player.rotationYaw - bodyYaw));
        cameraPitch = net.minecraft.util.math.MathHelper.clamp(cameraPitch + mc.player.rotationPitch - bodyPitch, -90, 90);
        active.rotationYaw = active.prevRotationYaw = cameraYaw;
        active.rotationPitch = active.prevRotationPitch = cameraPitch;
        mc.player.rotationYaw = mc.player.prevRotationYaw = bodyYaw;
        mc.player.rotationPitch = mc.player.prevRotationPitch = bodyPitch;
    }
    @SubscribeEvent public void input(InputUpdateEvent event) {
        if (active == null) return;
        event.getMovementInput().moveForward = event.getMovementInput().moveStrafe = 0;
        event.getMovementInput().jump = event.getMovementInput().sneak = false;
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.phase == TickEvent.Phase.START) {
            if (active != null || releaseInteractions) clearInteractions(mc);
            if (active == null && releaseInteractions
                    && !GameSettings.isKeyDown(mc.gameSettings.keyBindAttack)
                    && !GameSettings.isKeyDown(mc.gameSettings.keyBindUseItem)
                    && !GameSettings.isKeyDown(mc.gameSettings.keyBindPickBlock)) releaseInteractions = false;
            return;
        }
        if (mc.world == null || mc.player == null) { restore(mc); exitingEntity = selectedEntity = -1; return; }
        if (exitingEntity != -1) {
            Entity exiting = mc.world.getEntityByID(exitingEntity);
            if (!(exiting instanceof DroneEntity) || ((DroneEntity) exiting).pilotId() != mc.player.getEntityId()) exitingEntity = -1;
        }
        if (active == null) {
            for (Entity entity : mc.world.loadedEntityList) {
                if (entity instanceof DroneEntity && ((DroneEntity) entity).pilotId() == mc.player.getEntityId()) {
                    if (entity.getEntityId() == exitingEntity) continue;
                    active = (DroneEntity) entity;
                    releaseInteractions = true; clearInteractions(mc);
                    selectedEntity = active.getEntityId(); sequence = 0; pendingMoves.clear();
                    active.setClientControlled(true);
                    bodyYaw = mc.player.rotationYaw; bodyPitch = mc.player.rotationPitch;
                    cameraYaw = active.rotationYaw; cameraPitch = active.rotationPitch;
                    mc.setRenderViewEntity(active); break;
                }
            }
        }
        boolean toggle = exit.isPressed() && mc.currentScreen == null && mc.inGameHasFocus;
        if (toggle && ctrl()) { cycleDrone(mc); return; }
        if (active == null) {
            DroneEntity selected = selectedDrone(mc);
            if (toggle && selected != null && exitingEntity == -1) DronesMC.NETWORK.sendToServer(
                    new ControlPacket(selected.getEntityId(), 3, 0, 0, 0, 0, 0, false, 0, selected.sessionId()));
            return;
        }
        if (active.isDead || active.world != mc.world || active.pilotId() != mc.player.getEntityId() || !mc.player.isEntityAlive()) {
            restore(mc); return;
        }
        captureLook(mc);
        int action = landRequested || (ctrl() && Keyboard.isKeyDown(Keyboard.KEY_E)) ? 2 : toggle ? 1 : 0;
        boolean input = mc.currentScreen == null && mc.inGameHasFocus && action == 0;
        float forward = input ? axis(mc.gameSettings.keyBindForward, mc.gameSettings.keyBindBack) : 0;
        float strafe = input ? axis(mc.gameSettings.keyBindLeft, mc.gameSettings.keyBindRight) : 0;
        float vertical = input ? axis(mc.gameSettings.keyBindJump, mc.gameSettings.keyBindSneak) : 0;
        ControlPacket command = new ControlPacket(active.getEntityId(), action, forward, strafe, vertical,
                cameraYaw, cameraPitch, input && ctrl(), sequence++, active.sessionId());
        DronesMC.NETWORK.sendToServer(command);
        if (action == 0 && pendingMoves.size() < 32) {
            pendingMoves.add(command);
            active.predictMove(movement(command));
        }
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
        if (action != 0) {
            exitingEntity = active.getEntityId();
            if (action == 2) selectedEntity = -1;
            restore(mc);
        }
    }
    private Vec3d movement(ControlPacket command) {
        return FlightMath.direction(command.forward, command.strafe, command.vertical, command.yaw)
                .scale(command.sprint ? sprintSpeed : flightSpeed);
    }
    @Override public void receiveSnapshot(DroneSnapshot snapshot) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (active == null || mc.player == null || snapshot.entity != active.getEntityId()
                    || snapshot.pilot != mc.player.getEntityId() || snapshot.session != active.sessionId()) return;
            flightSpeed = snapshot.speed; sprintSpeed = snapshot.sprintSpeed;
            while (!pendingMoves.isEmpty() && pendingMoves.peek().sequence <= snapshot.acknowledged) pendingMoves.remove();
            double oldX = active.posX, oldY = active.posY, oldZ = active.posZ;
            active.setPosition(snapshot.x, snapshot.y, snapshot.z);
            for (ControlPacket command : pendingMoves) active.predictMove(movement(command));
            // Preserve the current render segment when correcting, rather than creating
            // an artificial interpolation step for an otherwise identical prediction.
            active.prevPosX += active.posX - oldX; active.lastTickPosX += active.posX - oldX;
            active.prevPosY += active.posY - oldY; active.lastTickPosY += active.posY - oldY;
            active.prevPosZ += active.posZ - oldZ; active.lastTickPosZ += active.posZ - oldZ;
        });
    }
    private DroneEntity selectedDrone(Minecraft mc) {
        Entity entity = mc.world.getEntityByID(selectedEntity);
        if (entity instanceof DroneEntity && eligible((DroneEntity) entity, mc)) return (DroneEntity) entity;
        selectedEntity = -1; return null;
    }
    private boolean eligible(DroneEntity drone, Minecraft mc) {
        return !drone.isDead && (drone.state() == DroneEntity.State.IDLE
                || (drone.state() == DroneEntity.State.PILOTED && drone.pilotId() == mc.player.getEntityId()));
    }
    private void cycleDrone(Minecraft mc) {
        List<DroneEntity> drones = new ArrayList<>();
        for (Entity entity : mc.world.loadedEntityList) if (entity instanceof DroneEntity && eligible((DroneEntity) entity, mc)) drones.add((DroneEntity) entity);
        drones.sort(Comparator.comparingInt(Entity::getEntityId));
        if (drones.isEmpty()) {
            selectedEntity = -1; mc.player.sendMessage(new TextComponentString("No active hovering drones.")); return;
        }
        int current = -1;
        for (int i = 0; i < drones.size(); i++) if (drones.get(i).getEntityId() == selectedEntity) current = i;
        DroneEntity next = drones.get((current + 1) % drones.size());
        selectedEntity = next.getEntityId();
        mc.player.sendMessage(new TextComponentString("Active drone: \"" + (next.hasCustomName() ? next.getCustomNameTag() : I18n.format("entity.dronesmc.drone.name")) + "\""));
        if (active != null && next != active) {
            DronesMC.NETWORK.sendToServer(new ControlPacket(next.getEntityId(), 4, 0, 0, 0, 0, 0, false, 0, active.sessionId()));
            exitingEntity = active.getEntityId(); restore(mc);
        }
    }
    private float axis(KeyBinding positive, KeyBinding negative) { return (positive.isKeyDown() ? 1 : 0) - (negative.isKeyDown() ? 1 : 0); }
    private void restore(Minecraft mc) {
        if (active != null) { releaseInteractions = true; clearInteractions(mc); }
        if (active != null) active.setClientControlled(false);
        if (active != null && mc.player != null) mc.setRenderViewEntity(mc.player);
        active = null; landRequested = false; pendingMoves.clear();
    }
    private static final class BatRenderer extends Render<DroneEntity> {
        private static final ResourceLocation TEXTURE = new ResourceLocation("minecraft:textures/entity/bat.png");
        private final ModelBat model = new ModelBat();
        private EntityBat pose;
        BatRenderer(RenderManager manager) { super(manager); shadowSize = 0.25F; }
        @Override protected ResourceLocation getEntityTexture(DroneEntity entity) { return TEXTURE; }
        @Override protected void renderName(DroneEntity entity, double x, double y, double z) {
            if (Minecraft.isGuiEnabled() && !entity.isInvisible()) renderLivingLabel(entity,
                    entity.hasCustomName() ? entity.getCustomNameTag() : I18n.format("entity.dronesmc.drone.name"), x, y, z, 64);
        }
        @Override public void doRender(DroneEntity entity, double x, double y, double z, float yaw, float partial) {
            if (Minecraft.getMinecraft().getRenderViewEntity() == entity && Minecraft.getMinecraft().gameSettings.thirdPersonView == 0) return;
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y + 0.5, z); GlStateManager.rotate(180 - yaw, 0, 1, 0);
            GlStateManager.scale(0.5, -0.5, -0.5); bindEntityTexture(entity);
            // ModelBat expects an EntityBat for its hanging-pose check. This render-only
            // bat is never spawned or ticked and cannot run bat AI.
            if (pose == null || pose.world != entity.world) {
                pose = new EntityBat(entity.world); pose.setIsBatHanging(false);
            }
            model.render(pose, 0, 0, entity.ticksExisted + partial, 0, entity.rotationPitch, 0.0625F);
            GlStateManager.popMatrix();
            super.doRender(entity, x, y, z, yaw, partial);
        }
    }
}
