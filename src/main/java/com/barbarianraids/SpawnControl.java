package com.barbarianraids;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Makes natural spawns rarer and keeps structure spawners from flooding the world (lag). */
public class SpawnControl {

    @SubscribeEvent
    public void onPositionCheck(MobSpawnEvent.PositionCheck event) {
        Mob mob = event.getEntity();
        if (!Factions.isFaction(mob)) {
            return;
        }
        ServerLevel level = event.getLevel().getLevel();
        MobSpawnType type = event.getSpawnType();

        if (type == MobSpawnType.NATURAL) {
            if (level.random.nextDouble() >= BrConfig.NATURAL_SPAWN_CHANCE.get()) {
                event.setResult(Event.Result.DENY);
                return;
            }
            mob.getPersistentData().putString(Factions.ORIGIN, "NATURAL");
            return;
        }

        if (type == MobSpawnType.SPAWNER) {
            if (level.random.nextDouble() >= BrConfig.SPAWNER_SPAWN_CHANCE.get()) {
                event.setResult(Event.Result.DENY);
                return;
            }
            AABB box = new AABB(mob.blockPosition()).inflate(40.0D);
            int near = level.getEntitiesOfClass(Mob.class, box, Factions::isFaction).size();
            if (near >= BrConfig.MAX_MOBS_NEAR_SPAWNER.get()) {
                event.setResult(Event.Result.DENY);
                return;
            }
            int budget = BrConfig.SPAWNER_AREA_BUDGET.get();
            if (budget > 0) {
                BarbarianData data = BarbarianData.get(level.getServer());
                String cell = level.dimension().location() + "|" + (mob.blockPosition().getX() >> 6) + "," + (mob.blockPosition().getZ() >> 6);
                int used = data.spawned.getOrDefault(cell, 0);
                if (used >= budget) {
                    event.setResult(Event.Result.DENY);
                    return;
                }
                data.spawned.put(cell, used + 1);
                data.setDirty();
            }
            mob.getPersistentData().putString(Factions.ORIGIN, "SPAWNER");
        }
    }
}
