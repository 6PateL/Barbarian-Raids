package com.barbarianraids;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Decides when a hideout boss sends a raiding party, and where to. */
public class RaidManager {
    private int counter = 0;

    private record Target(int type, BlockPos pos) {}

    public static long randomInterval(ServerLevel level) {
        int min = Math.min(BrConfig.RAID_INTERVAL_MIN_MINUTES.get(), BrConfig.RAID_INTERVAL_MAX_MINUTES.get());
        int max = Math.max(BrConfig.RAID_INTERVAL_MIN_MINUTES.get(), BrConfig.RAID_INTERVAL_MAX_MINUTES.get());
        return (min + level.random.nextInt(max - min + 1)) * 1200L;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++counter % 100 != 0) {
            return;
        }
        MinecraftServer server = event.getServer();
        BarbarianData data = BarbarianData.get(server);
        if (data.bosses.isEmpty()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            String dim = level.dimension().location().toString();
            long now = level.getGameTime();
            List<BarbarianData.Boss> due = new ArrayList<>();
            for (BarbarianData.Boss b : data.bosses.values()) {
                if (!b.dead && dim.equals(b.dim) && b.nextRaid <= now) {
                    due.add(b);
                }
            }
            for (BarbarianData.Boss b : due) {
                Entity e = level.getEntity(b.uuid);
                if (!(e instanceof PathfinderMob boss) || !boss.isAlive()) {
                    continue; // hideout is not loaded
                }
                if (b.nextRaid > now) {
                    continue; // already launched as part of a coalition
                }
                if (!launch(level, data, b, boss)) {
                    b.nextRaid = now + 2400L;
                }
                data.setDirty();
            }
        }
    }

    // ------------------------------------------------------------------ launching

    private boolean launch(ServerLevel level, BarbarianData data, BarbarianData.Boss b, PathfinderMob boss) {
        Target target = pickTarget(level, boss);
        if (target == null) {
            return false;
        }
        if (!dispatch(level, b, boss, target)) {
            return false;
        }
        long now = level.getGameTime();
        b.nextRaid = now + randomInterval(level);

        // other nearby hideouts join in
        int radius = BrConfig.COALITION_RADIUS.get();
        if (radius > 0) {
            String dim = level.dimension().location().toString();
            for (Map.Entry<String, BarbarianData.Boss> en : data.bosses.entrySet()) {
                BarbarianData.Boss other = en.getValue();
                if (other == b || other.dead || !dim.equals(other.dim)
                        || other.home.distSqr(target.pos()) > (double) radius * radius) {
                    continue;
                }
                Entity oe = level.getEntity(other.uuid);
                if (oe instanceof PathfinderMob otherBoss && otherBoss.isAlive() && dispatch(level, other, otherBoss, target)) {
                    other.nextRaid = now + randomInterval(level);
                }
            }
        }
        if (BrConfig.ANNOUNCE_RAIDS.get()) {
            String key = target.type() == 0 ? "msg.barbarianraids.raid_village" : "msg.barbarianraids.raid_track";
            for (ServerPlayer p : level.players()) {
                if (p.blockPosition().distSqr(target.pos()) <= 128.0D * 128.0D) {
                    p.displayClientMessage(Component.translatable(key), false);
                }
            }
        }
        return true;
    }

    private boolean dispatch(ServerLevel level, BarbarianData.Boss b, PathfinderMob boss, Target target) {
        String group = boss.getPersistentData().getString(Factions.GROUP);
        AABB box = boss.getBoundingBox().inflate(48.0D, 24.0D, 48.0D);
        List<PathfinderMob> pool = new ArrayList<>(level.getEntitiesOfClass(PathfinderMob.class, box, m ->
                Factions.isFaction(m) && m != boss && m.isAlive()
                        && group.equals(m.getPersistentData().getString(Factions.GROUP))
                        && !m.getPersistentData().contains(Factions.RAID_END)
                        && !m.getPersistentData().getBoolean(Factions.PACIFIED)));
        int min = BrConfig.RAID_MIN_SOLDIERS.get();
        int max = Math.max(min, BrConfig.RAID_MAX_SOLDIERS.get());
        if (pool.size() < min) {
            return false;
        }
        java.util.Collections.shuffle(pool, new java.util.Random(level.random.nextLong()));
        int count = Math.min(pool.size(), min + level.random.nextInt(max - min + 1));
        List<PathfinderMob> party = pool.subList(0, count);

        long end = level.getGameTime() + BrConfig.RAID_DURATION_MINUTES.get() * 1200L;
        PathfinderMob strongest = null;
        for (PathfinderMob m : party) {
            CompoundTag d = m.getPersistentData();
            d.putLong(Factions.RAID_END, end);
            d.putInt(Factions.RAID_TYPE, target.type());
            d.putInt(Factions.TX, target.pos().getX());
            d.putInt(Factions.TY, target.pos().getY());
            d.putInt(Factions.TZ, target.pos().getZ());
            d.putLong(Factions.HOME, b.home.asLong());
            d.putInt(Factions.BUDGET, BrConfig.TRACK_BLOCKS_PER_RAIDER.get());
            d.remove(Factions.RETURNING);
            m.setTarget(null);
            m.setPersistenceRequired();
            if (strongest == null || m.getMaxHealth() > strongest.getMaxHealth()) {
                strongest = m;
            }
        }
        if (strongest != null) {
            BossManager.makeRaidBoss(strongest, b.home);
        }
        return true;
    }

    // ------------------------------------------------------------------ targets

    /** Hideout bosses only send track-breaking raids; village raids are scheduled by VillageRaids. */
    private Target pickTarget(ServerLevel level, PathfinderMob boss) {
        return trackTarget(level, boss);
    }

    private Target trackTarget(ServerLevel level, PathfinderMob boss) {
        List<BlockPos> found = TrackScanner.find(level, boss.blockPosition(), BrConfig.TARGET_RADIUS.get());
        if (found.isEmpty()) {
            return null;
        }
        int pick = level.random.nextInt(Math.min(found.size(), 6));
        return new Target(1, found.get(pick));
    }
}
