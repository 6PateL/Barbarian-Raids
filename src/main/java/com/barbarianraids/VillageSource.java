package com.barbarianraids;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Reads the settlements of Recruits Villages (Living Villages) by reflection, so the mod is only needed at runtime. */
public final class VillageSource {
    public record Village(String name, BlockPos center, boolean playerOwned) {}

    private static boolean failed = false;

    private VillageSource() {}

    public static List<Village> list(MinecraftServer server) {
        List<Village> out = new ArrayList<>();
        if (failed || !ModList.get().isLoaded("recruitsvillages")) {
            return out;
        }
        try {
            Class<?> dataClass = Class.forName("com.diego.recruitsvillages.data.LivingVillagesSavedData");
            Object data = dataClass.getMethod("get", MinecraftServer.class).invoke(null, server);
            Collection<?> factions = (Collection<?>) dataClass.getMethod("getFactions").invoke(data);
            Method center = null;
            Method name = null;
            Method player = null;
            for (Object f : factions) {
                if (center == null) {
                    center = f.getClass().getMethod("getCenterPos");
                    name = f.getClass().getMethod("getName");
                    player = f.getClass().getMethod("isPlayerFaction");
                }
                BlockPos c = (BlockPos) center.invoke(f);
                if (c == null) {
                    continue;
                }
                out.add(new Village(String.valueOf(name.invoke(f)), c, Boolean.TRUE.equals(player.invoke(f))));
            }
        } catch (Throwable t) {
            failed = true;
            org.slf4j.LoggerFactory.getLogger("barbarianraids").warn("Could not read Recruits Villages data: {}", t.toString());
        }
        return out;
    }
}
