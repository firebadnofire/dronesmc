package dev.dronesmc;

import net.minecraft.util.DamageSource;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.fml.common.eventhandler.Cancelable;

/** Cancellable damage hook for protection integrations; drones are deliberately not living entities. */
@Cancelable
public final class DroneDamageEvent extends EntityEvent {
    public final DamageSource source;
    public final float amount;
    public DroneDamageEvent(DroneEntity drone, DamageSource source, float amount) {
        super(drone); this.source = source; this.amount = amount;
    }
}
