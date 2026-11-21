package dev.dronesmc;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

public final class DroneEntity extends Entity {
    public enum State { IDLE, PILOTED, RETURNING, DESCENDING, LANDED }
    private static final DataParameter<Integer> PILOT = EntityDataManager.createKey(DroneEntity.class, DataSerializers.VARINT);
    private static final DataParameter<Integer> STATE = EntityDataManager.createKey(DroneEntity.class, DataSerializers.VARINT);
    private static final DataParameter<Integer> SESSION = EntityDataManager.createKey(DroneEntity.class, DataSerializers.VARINT);
    // The last operator is only needed while piloting/returning, never ownership.
    private UUID operator;
    private EntityPlayerMP pilot;
    private Vec3d anchor, input = Vec3d.ZERO;
    private int lastReceivedSequence = -1, lastAppliedSequence = -1;
    private final Deque<ControlPacket> controls = new ArrayDeque<>();
    private boolean sprint, dropped;
    private final Deque<Vec3d> route = new ArrayDeque<>();
    private Vec3d landing;
    private Vec3d recoveryHome;
    private boolean landingPending;
    private int landingTicks, stalled;
    private double targetX, targetY, targetZ;
    private float targetYaw, targetPitch;
    private int interpolationSteps;
    private boolean clientControlled;

    public DroneEntity(World world) { super(world); setSize(0.5F, 0.5F); setNoGravity(true); }
    @Override protected void entityInit() {
        dataManager.register(PILOT, -1); dataManager.register(STATE, State.IDLE.ordinal()); dataManager.register(SESSION, 0);
    }
    public int pilotId() { return dataManager.get(PILOT); }
    public int sessionId() { return dataManager.get(SESSION); }
    public State state() { return State.values()[dataManager.get(STATE)]; }
    private void state(State state) { dataManager.set(STATE, state.ordinal()); }
    @Override public boolean canBeCollidedWith() { return !isDead; }
    @Override public boolean canBePushed() { return false; }
    @Override public float getEyeHeight() { return 0.3F; }
    @Override public boolean isInRangeToRenderDist(double distance) { return distance < 256 * 256; }

    @Override public boolean processInitialInteract(EntityPlayer player, EnumHand hand) {
        if (world.isRemote) return true;
        if (!(player instanceof EntityPlayerMP)) return false;
        if (pilot != null || player.isRiding() || player.isPlayerSleeping() || !player.isEntityAlive() || DroneEvents.isPiloting(player)) return true;
        pilot = (EntityPlayerMP) player;
        operator = player.getUniqueID();
        anchor = pilot.getPositionVector();
        input = Vec3d.ZERO; controls.clear(); lastReceivedSequence = lastAppliedSequence = -1;
        dataManager.set(SESSION, sessionId() + 1);
        route.clear(); landing = null; recoveryHome = null; landingPending = false;
        state(State.PILOTED); dataManager.set(PILOT, pilot.getEntityId());
        DroneEvents.attach(pilot, this);
        pilot.sendStatusMessage(new TextComponentString("Drone: R toggles view; Ctrl+R cycles drones; Ctrl+E returns and lands."), true);
        return true;
    }

    public boolean canRemotePilot(EntityPlayer player) {
        return !isDead && pilot == null && state() == State.IDLE && player.world == world && player.isEntityAlive()
                && !player.isRiding() && !player.isPlayerSleeping() && safeForPlayer(getEntityBoundingBox(), player);
    }

    public void control(EntityPlayerMP player, ControlPacket packet) {
        if (player != pilot || state() != State.PILOTED || packet.session != sessionId() || !packet.valid()) return;
        if (packet.action == 1) { exitPilot(); return; }
        if (packet.action == 2) { Vec3d home = anchor; exitPilot(); returnAndLand(home); return; }
        // At most one accepted movement step is simulated per server tick. Extra or
        // stale packets cannot increase movement speed; snapshots acknowledge steps.
        if (packet.sequence <= lastReceivedSequence || controls.size() >= 8) return;
        lastReceivedSequence = packet.sequence;
        controls.add(packet);
    }

    public void holdPlayer(EntityPlayerMP player) {
        if (player != pilot) return;
        if (!player.isEntityAlive()) { landAfterDeath(player); return; }
        if (player.world != world || isDead || !safeArea(getEntityBoundingBox())) {
            exitPilot(); return;
        }
        player.motionX = player.motionY = player.motionZ = 0;
        player.setSprinting(false);
        if (player.getPositionVector().squareDistanceTo(anchor) > 0.0001) {
            player.connection.setPlayerLocation(anchor.x, anchor.y, anchor.z, player.rotationYaw, player.rotationPitch);
        }
    }

    public void exitPilot() {
        if (pilot != null) DroneEvents.detach(pilot, this);
        pilot = null; anchor = null; input = Vec3d.ZERO; controls.clear();
        dataManager.set(PILOT, -1);
        if (state() == State.PILOTED) state(State.IDLE);
    }

    public void landAfterDeath(EntityPlayer player) {
        if (player != pilot) return;
        Vec3d home = player.getPositionVector();
        exitPilot();
        if (!isDead) returnAndLand(home);
    }

    public void landAfterDisconnect(EntityPlayer player) {
        if (player != pilot) return;
        queueLanding();
    }

    public void queueLanding() {
        // Capture the anchored body position before exitPilot clears the session.
        Vec3d home = anchor != null ? anchor : getPositionVector();
        exitPilot();
        if (isDead) return;
        recoveryHome = home; landingPending = true;
        route.clear(); landing = null; state(State.IDLE);
    }

    private void returnAndLand(Vec3d home) {
        recoveryHome = home; landingPending = false;
        LandingPlanner.Result result = LandingPlanner.plan(this, home);
        if (result == null) result = LandingPlanner.planNearest(this);
        if (result == null) { landingFailed(); return; }
        route.clear(); route.addAll(result.route); landing = result.landing;
        landingTicks = stalled = 0; state(State.RETURNING);
    }

    private void landingFailed() {
        state(State.IDLE); route.clear(); landing = null; recoveryHome = null; landingPending = false;
        EntityPlayer player = operator == null ? null : world.getPlayerEntityByUUID(operator);
        if (player != null) player.sendStatusMessage(new TextComponentString("Drone landing failed: no safe loaded route or landing surface. Drone is hovering."), false);
    }

    public AxisAlignedBB boxAt(Vec3d pos) {
        return new AxisAlignedBB(pos.x - width / 2, pos.y, pos.z - width / 2,
                pos.x + width / 2, pos.y + height, pos.z + width / 2);
    }
    public boolean clearAt(Vec3d pos) {
        AxisAlignedBB box = boxAt(pos);
        return safeArea(box) && world.getCollisionBoxes(this, box).isEmpty() && !world.containsAnyLiquid(box);
    }
    private boolean safeArea(AxisAlignedBB box) {
        if (box.minY < 0 || box.maxY >= world.getHeight() || !world.getWorldBorder().contains(box)) return false;
        BlockPos min = new BlockPos(box.minX - 1, box.minY, box.minZ - 1);
        BlockPos max = new BlockPos(box.maxX + 1, box.maxY, box.maxZ + 1);
        if (!world.isAreaLoaded(min, max)) return false;
        return pilot == null || safeForPlayer(box, pilot);
    }
    private boolean safeForPlayer(AxisAlignedBB box, EntityPlayer player) {
        if (player.world != world || !world.isAreaLoaded(new BlockPos(box.minX - 1, box.minY, box.minZ - 1),
                new BlockPos(box.maxX + 1, box.maxY, box.maxZ + 1))) return false;
        int distance = Math.max(1, world.getMinecraftServer().getPlayerList().getViewDistance() - 2);
        return Math.abs((box.minX + box.maxX) / 2 / 16 - player.posX / 16) < distance
                && Math.abs((box.minZ + box.maxZ) / 2 / 16 - player.posZ / 16) < distance;
    }
    public void setClientControlled(boolean controlled) { clientControlled = controlled; }
    public void predictMove(Vec3d movement) {
        // Presentation only. The server independently simulates collision and speed.
        AxisAlignedBB swept = getEntityBoundingBox().expand(movement.x, movement.y, movement.z);
        if (world.isAreaLoaded(new BlockPos(swept.minX - 1, swept.minY, swept.minZ - 1),
                new BlockPos(swept.maxX + 1, swept.maxY, swept.maxZ + 1)) && world.getWorldBorder().contains(swept)
                && swept.minY >= 0 && swept.maxY < world.getHeight()) move(MoverType.SELF, movement.x, movement.y, movement.z);
        motionX = motionY = motionZ = 0;
    }
    private boolean moveSafely(Vec3d delta) {
        AxisAlignedBB swept = getEntityBoundingBox().expand(delta.x, delta.y, delta.z);
        if (!safeArea(swept)) return false;
        move(MoverType.SELF, delta.x, delta.y, delta.z);
        motionX = motionY = motionZ = 0;
        return true;
    }
    @Override public void setPositionAndRotationDirect(double x, double y, double z, float yaw, float pitch,
            int increments, boolean teleport) {
        // Vanilla's base Entity snaps immediately. Retain authoritative network targets
        // and interpolate like vanilla vehicles, including unoccupied/returning drones.
        targetX = x; targetY = y; targetZ = z; targetYaw = yaw; targetPitch = pitch;
        interpolationSteps = Math.max(1, Math.min(increments, 3));
    }
    void interpolate() {
        if (clientControlled || interpolationSteps <= 0) return;
        setPosition(posX + (targetX - posX) / interpolationSteps,
                posY + (targetY - posY) / interpolationSteps, posZ + (targetZ - posZ) / interpolationSteps);
        rotationYaw += net.minecraft.util.math.MathHelper.wrapDegrees(targetYaw - rotationYaw) / interpolationSteps;
        rotationPitch += (targetPitch - rotationPitch) / interpolationSteps;
        interpolationSteps--;
    }
    @Override public void onUpdate() {
        super.onUpdate();
        if (world.isRemote) { interpolate(); return; }
        // Entity.move invokes environmental collision hooks; zero movement still checks hazards.
        move(MoverType.SELF, 0, 0, 0);
        if (isDead) return;
        if (landingPending) returnAndLand(recoveryHome);
        if (pilot != null) {
            holdPlayer(pilot);
            if (pilot != null) {
                ControlPacket command = controls.poll();
                if (command != null) {
                    rotationYaw = command.yaw; rotationPitch = Math.max(-90, Math.min(90, command.pitch));
                    input = FlightMath.direction(command.forward, command.strafe, command.vertical, rotationYaw);
                    sprint = command.sprint; lastAppliedSequence = command.sequence;
                    if (!moveSafely(input.scale(sprint ? DroneConfig.sprintSpeed : DroneConfig.speed))) exitPilot();
                } else input = Vec3d.ZERO;
                if (pilot != null) DronesMC.NETWORK.sendTo(new DroneSnapshot(this, lastAppliedSequence), pilot);
            }
        } else if (state() == State.RETURNING || state() == State.DESCENDING) {
            if (++landingTicks > 1200 || landing == null || !LandingPlanner.safeSupport(this, landing)) { landingFailed(); return; }
            Vec3d target;
            if (state() == State.RETURNING && route.isEmpty()) state(State.DESCENDING);
            target = state() == State.DESCENDING ? landing : route.peek();
            Vec3d delta = target.subtract(getPositionVector());
            if (delta.lengthSquared() < 0.0025) {
                if (state() == State.DESCENDING) { state(State.LANDED); landing = null; recoveryHome = null; }
                else route.remove();
                stalled = 0; return;
            }
            Vec3d before = getPositionVector();
            double length = Math.sqrt(delta.lengthSquared());
            Vec3d step = delta.scale(Math.min(0.2, length) / length);
            if (!clearAt(getPositionVector().add(step)) || !moveSafely(step)) { landingFailed(); return; }
            if (before.squareDistanceTo(getPositionVector()) < 0.00001) {
                if (++stalled > 40) landingFailed();
            } else stalled = 0;
        }
        if (isInLava()) attackEntityFrom(DamageSource.LAVA, 1);
        if (isEntityInsideOpaqueBlock()) attackEntityFrom(DamageSource.IN_WALL, 1);
        if (posY < -64) attackEntityFrom(DamageSource.OUT_OF_WORLD, 1);
    }
    @Override public boolean attackEntityFrom(DamageSource source, float amount) {
        if (world.isRemote || isDead || dropped || !Float.isFinite(amount) || amount <= 0 || isEntityInvulnerable(source)) return false;
        if (net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new DroneDamageEvent(this, source, amount))) return false;
        dropped = true;
        entityDropItem(recoveryItem(), 0);
        setDead(); return true;
    }
    @Override public void setDead() {
        if (!world.isRemote && !isDead) {
            exitPilot();
        }
        super.setDead();
    }
    @Override protected void readEntityFromNBT(NBTTagCompound nbt) {
        dropped = nbt.getBoolean("dropped");
        state(nbt.getBoolean("landed") ? State.LANDED : State.IDLE);
        landingPending = nbt.getBoolean("resumeLanding") && state() != State.LANDED;
        if (landingPending) {
            double x = nbt.getDouble("landingHomeX"), y = nbt.getDouble("landingHomeY"), z = nbt.getDouble("landingHomeZ");
            recoveryHome = Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    ? new Vec3d(x, y, z) : getPositionVector();
        }
    }
    @Override protected void writeEntityToNBT(NBTTagCompound nbt) {
        nbt.setBoolean("dropped", dropped); nbt.setBoolean("landed", state() == State.LANDED);
        Vec3d home = anchor != null ? anchor : recoveryHome;
        boolean resume = pilot != null || landingPending || state() == State.RETURNING || state() == State.DESCENDING;
        nbt.setBoolean("resumeLanding", resume);
        if (resume) {
            if (home == null) home = getPositionVector();
            nbt.setDouble("landingHomeX", home.x); nbt.setDouble("landingHomeY", home.y); nbt.setDouble("landingHomeZ", home.z);
        }
    }
    public ItemStack recoveryItem() {
        ItemStack stack = new ItemStack(DronesMC.DRONE);
        if (hasCustomName()) stack.setStackDisplayName(getCustomNameTag());
        return stack;
    }
}
