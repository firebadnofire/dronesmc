package dev.dronesmc;

import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.common.registry.EntityRegistry;
import net.minecraftforge.fml.relauncher.Side;

@Mod(modid = DronesMC.ID, name = "DronesMC", version = "0.0.2", acceptedMinecraftVersions = "[1.12.2]",
        acceptableRemoteVersions = "[0.0.2]",
        dependencies = "required-after:forge@[14.23.5.2859,)")
@Mod.EventBusSubscriber(modid = DronesMC.ID)
public final class DronesMC {
    public static final String ID = "dronesmc";
    @Mod.Instance(ID) public static DronesMC instance;
    @SidedProxy(clientSide = "dev.dronesmc.ClientProxy", serverSide = "dev.dronesmc.CommonProxy")
    public static CommonProxy proxy;
    public static final Item DRONE = new DroneItem();
    public static final SimpleNetworkWrapper NETWORK = NetworkRegistry.INSTANCE.newSimpleChannel(ID);

    @Mod.EventHandler public void preInit(FMLPreInitializationEvent event) {
        DroneConfig.load(event.getSuggestedConfigurationFile());
        NETWORK.registerMessage(ControlPacket.Handler.class, ControlPacket.class, 0, Side.SERVER);
        NETWORK.registerMessage(DroneSnapshot.Handler.class, DroneSnapshot.class, 1, Side.CLIENT);
        EntityRegistry.registerModEntity(new ResourceLocation(ID, "drone"), DroneEntity.class, "drone", 0,
                this, 256, 1, true);
        proxy.preInit();
        event.getModLog().info("DronesMC initialized; physical drone entity and control channel registered");
    }
    @SubscribeEvent public static void registerItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().register(DRONE);
    }
}
