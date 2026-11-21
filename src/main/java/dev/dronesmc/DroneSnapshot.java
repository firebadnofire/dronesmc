package dev.dronesmc;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Server position/acknowledgment for local collision prediction; never accepts client coordinates. */
public final class DroneSnapshot implements IMessage {
    int entity, pilot, session, acknowledged;
    double x, y, z, speed, sprintSpeed;
    public DroneSnapshot() { }
    public DroneSnapshot(DroneEntity drone, int acknowledged) {
        entity = drone.getEntityId(); pilot = drone.pilotId(); session = drone.sessionId(); this.acknowledged = acknowledged;
        x = drone.posX; y = drone.posY; z = drone.posZ; speed = DroneConfig.speed; sprintSpeed = DroneConfig.sprintSpeed;
    }
    @Override public void fromBytes(ByteBuf buffer) {
        entity = buffer.readInt(); pilot = buffer.readInt(); session = buffer.readInt(); acknowledged = buffer.readInt();
        x = buffer.readDouble(); y = buffer.readDouble(); z = buffer.readDouble(); speed = buffer.readDouble(); sprintSpeed = buffer.readDouble();
    }
    @Override public void toBytes(ByteBuf buffer) {
        buffer.writeInt(entity); buffer.writeInt(pilot); buffer.writeInt(session); buffer.writeInt(acknowledged);
        buffer.writeDouble(x); buffer.writeDouble(y); buffer.writeDouble(z); buffer.writeDouble(speed); buffer.writeDouble(sprintSpeed);
    }
    public static final class Handler implements IMessageHandler<DroneSnapshot, IMessage> {
        @Override public IMessage onMessage(DroneSnapshot snapshot, MessageContext context) {
            DronesMC.proxy.receiveSnapshot(snapshot); return null;
        }
    }
}
