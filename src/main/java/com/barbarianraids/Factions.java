package com.barbarianraids;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.entity.PathfinderMob;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Which Hostile Civilization mobs count as barbarians. Name based, so we need no compile-time dependency. */
public final class Factions {
    private static final String PKG = "net.mcreator.hostilecivilization.entity.";
    private static final Map<Class<?>, Boolean> CACHE = new ConcurrentHashMap<>();

    // persistent-data tags
    public static final String ORIGIN = "br_origin";
    public static final String GROUP = "br_group";
    public static final String BOSS = "br_boss";
    public static final String RAID_BOSS = "br_raid_boss";
    public static final String HIDEOUT = "br_hideout";
    public static final String PACIFIED = "br_pacified";
    public static final String RAID_END = "br_raid_end";
    public static final String RAID_TYPE = "br_raid_type"; // 0 settlement, 1 tracks
    public static final String TX = "br_tx";
    public static final String TY = "br_ty";
    public static final String TZ = "br_tz";
    public static final String HOME = "br_home";
    public static final String BUDGET = "br_budget";
    public static final String RETURNING = "br_returning";

    private Factions() {}

    public static boolean isFaction(Entity e) {
        if (!(e instanceof PathfinderMob)) {
            return false;
        }
        return CACHE.computeIfAbsent(e.getClass(), c -> {
            String n = c.getName();
            if (!n.startsWith(PKG)) {
                return false;
            }
            String s = n.substring(PKG.length());
            return !s.contains("Friendly") && (s.startsWith("Castaway") || s.startsWith("Masked") || s.startsWith("Burrower"));
        });
    }

    private static final Map<EntityType<?>, Boolean> EXTRA = new ConcurrentHashMap<>();

    /** Mobs of the mods listed in the config (e.g. Flesh That Hates) - mutual enemies of the barbarians. */
    public static boolean isExtraEnemy(Entity e) {
        if (!(e instanceof net.minecraft.world.entity.Mob) || e.getClass().getSimpleName().contains("Spawner")) {
            return false;
        }
        return EXTRA.computeIfAbsent(e.getType(), t -> {
            var key = ForgeRegistries.ENTITY_TYPES.getKey(t);
            return key != null && BrConfig.EXTRA_ENEMY_NAMESPACES.get().contains(key.getNamespace());
        });
    }

    public static boolean isLeaderClass(Entity e) {
        return e.getClass().getSimpleName().contains("Leader");
    }
}
