package com.barbarianraids;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(BarbarianRaids.MODID)
public class BarbarianRaids {
    public static final String MODID = "barbarianraids";

    public static final DeferredRegister<net.minecraft.world.item.Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final RegistryObject<net.minecraft.world.item.Item> RAID_HORN = ITEMS.register("raid_horn", RaidHornItem::new);

    public BarbarianRaids() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BrConfig.SPEC);
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        modBus.addListener(BarbarianRaids::onCreativeTab);
        MinecraftForge.EVENT_BUS.register(new SpawnControl());
        MinecraftForge.EVENT_BUS.register(new BossManager());
        MinecraftForge.EVENT_BUS.register(new RaidManager());
        MinecraftForge.EVENT_BUS.register(new VillageRaids());
        MinecraftForge.EVENT_BUS.register(new RecruitSanity());
    }

    private static void onCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(RAID_HORN);
        }
    }
}
