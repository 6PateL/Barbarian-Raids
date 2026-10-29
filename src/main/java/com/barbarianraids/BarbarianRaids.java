package com.barbarianraids;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(BarbarianRaids.MODID)
public class BarbarianRaids {
    public static final String MODID = "barbarianraids";

    public BarbarianRaids() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BrConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(new SpawnControl());
        MinecraftForge.EVENT_BUS.register(new BossManager());
        MinecraftForge.EVENT_BUS.register(new RaidManager());
        MinecraftForge.EVENT_BUS.register(new RecruitSanity());
    }
}
