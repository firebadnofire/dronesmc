package dev.dronesmc;

import net.minecraft.client.settings.KeyBinding;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClientInputTest {
    @Test public void droneTransitionsClearHeldButtonsAndAllQueuedClicks() throws Exception {
        // Forge's onTick polls native modifier keys; seed its queue headlessly.
        java.lang.reflect.Field queued = KeyBinding.class.getDeclaredField("pressTime");
        queued.setAccessible(true);
        KeyBinding attack = new KeyBinding("test.dronesmc.attack", -100, "test.dronesmc");
        KeyBinding use = new KeyBinding("test.dronesmc.use", -99, "test.dronesmc");
        for (KeyBinding binding : new KeyBinding[] { attack, use }) {
            KeyBinding.setKeyBindState(binding.getKeyCode(), true);
            queued.setInt(binding, 2);
        }
        ClientProxy.clearInteractions(attack, use);
        for (KeyBinding binding : new KeyBinding[] { attack, use }) {
            assertFalse(binding.isKeyDown());
            assertFalse(binding.isPressed());
        }
        ClientProxy.clearInteractions(attack, use);
        KeyBinding.setKeyBindState(use.getKeyCode(), true);
        queued.setInt(use, 1);
        assertTrue(use.isKeyDown());
        assertTrue(use.isPressed());
        ClientProxy.clearInteractions(attack, use);
    }
}
