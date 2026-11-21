package dev.dronesmc;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public final class ControlPacket implements IMessage {
    int entity, action, sequence, session;
    float forward, strafe, vertical, yaw, pitch;
    boolean sprint;
    public ControlPacket() { }
    public ControlPacket(int entity, int action, float forward, float strafe, float vertical, float yaw, float pitch, boolean sprint) {
        this(entity, action, forward, strafe, vertical, yaw, pitch, sprint, 0, 0);
    }
    public ControlPacket(int entity, int action, float forward, float strafe, float vertical, float yaw, float pitch, boolean sprint,
            int sequence, int session) {
        this.entity = entity; this.action = action; this.forward = forward; this.strafe = strafe;
        this.vertical = vertical; this.yaw = yaw; this.pitch = pitch; this.sprint = sprint;
        this.sequence = sequence; this.session = session;
    }
    public boolean valid() {
        return action >= 0 && action <= 4 && sequence >= 0 && Float.isFinite(yaw) && Float.isFinite(pitch)
                && Float.isFinite(forward) && Math.abs(forward) <= 1
                && Float.isFinite(strafe) && Math.abs(strafe) <= 1
                && Float.isFinite(vertical) && Math.abs(vertical) <= 1;
    }
    @Override public void fromBytes(ByteBuf buf) {
        entity = buf.readInt(); action = buf.readUnsignedByte(); forward = buf.readFloat(); strafe = buf.readFloat();
        vertical = buf.readFloat(); yaw = buf.readFloat(); pitch = buf.readFloat(); sprint = buf.readBoolean();
        sequence = buf.readInt(); session = buf.readInt();
    }
    @Override public void toBytes(ByteBuf buf) {
        buf.writeInt(entity); buf.writeByte(action); buf.writeFloat(forward); buf.writeFloat(strafe);
        buf.writeFloat(vertical); buf.writeFloat(yaw); buf.writeFloat(pitch); buf.writeBoolean(sprint);
        buf.writeInt(sequence); buf.writeInt(session);
    }
    public static final class Handler implements IMessageHandler<ControlPacket, IMessage> {
        @Override public IMessage onMessage(ControlPacket packet, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            if (!packet.valid()) return null;
            player.getServerWorld().addScheduledTask(() -> {
                Entity entity = player.world.getEntityByID(packet.entity);
                if (entity instanceof DroneEntity) {
                    if (packet.action == 3 || packet.action == 4) DroneEvents.switchDrone(player, (DroneEntity) entity, packet.session);
                    else ((DroneEntity) entity).control(player, packet);
                }
            });
            return null;
        }
    }
}
