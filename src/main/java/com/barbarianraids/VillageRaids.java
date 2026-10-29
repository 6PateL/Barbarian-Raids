package com.barbarianraids;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every 2-3 days the three Recruits Villages nearest to a player are raided. The raiders come from the nearest barbarian
 * structure (its own soldiers) or, when there is none in range, are homeless barbarians.
 */
public class VillageRaids {
    public static VillageRaids INSTANCE;

    private static final long DAY = 24000L;

    private record Pending(BlockPos center, String name, long deadline) {}

    private final List<Pending> pending = new ArrayList<>();
    private int counter = 0;
    private List<EntityType<?>> homelessTypes = null;

    public VillageRaids() {
        INSTANCE = this;
    }

    // ------------------------------------------------------------------ scheduler

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++counter % 100 != 0 || !BrConfig.VR_ENABLED.get()) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        long now = level.getGameTime();

        // retry raids whose spawn area was not loaded yet
        if (!pending.isEmpty()) {
            List<Pending> copy = new ArrayList<>(pending);
            pending.clear();
            for (Pending p : copy) {
                if (now > p.deadline()) {
                    continue;
                }
                if (!spawnRaid(server, level, p.name(), p.center())) {
                    pending.add(p);
                }
            }
        }

        BarbarianData data = BarbarianData.get(server);
        if (data.nextVillageRaid == 0L) {
            data.nextVillageRaid = now + interval(level);
            data.setDirty();
            return;
        }
        if (now < data.nextVillageRaid || server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        int launched = launchWave(server, level);
        data.nextVillageRaid = now + (launched > 0 ? interval(level) : 6000L);
        data.setDirty();
    }

    private static long interval(ServerLevel level) {
        int min = Math.min(BrConfig.VR_MIN_DAYS.get(), BrConfig.VR_MAX_DAYS.get());
        int max = Math.max(BrConfig.VR_MIN_DAYS.get(), BrConfig.VR_MAX_DAYS.get());
        return (min + level.random.nextInt(max - min + 1)) * DAY;
    }

    private int launchWave(MinecraftServer server, ServerLevel level) {
        List<VillageSource.Village> all = VillageSource.list(server);
        List<BlockPos> chosen = new ArrayList<>();
        int launched = 0;
        double r = BrConfig.VR_SEARCH_RADIUS.get();
        for (net.minecraft.server.level.ServerPlayer p : level.players()) {
            List<VillageSource.Village> near = new ArrayList<>();
            for (VillageSource.Village v : all) {
                if ((!v.playerOwned() || BrConfig.VR_PLAYER_FACTIONS.get()) && horizontal(v.center(), p.blockPosition()) <= r * r) {
                    near.add(v);
                }
            }
            near.sort(Comparator.comparingDouble(v -> horizontal(v.center(), p.blockPosition())));
            for (int i = 0; i < Math.min(near.size(), BrConfig.VR_COUNT.get()); i++) {
                VillageSource.Village v = near.get(i);
                if (chosen.contains(v.center())) {
                    continue;
                }
                chosen.add(v.center());
                if (spawnRaid(server, level, v.name(), v.center())) {
                    launched++;
                } else {
                    pending.add(new Pending(v.center(), v.name(), level.getGameTime() + 2400L));
                    launched++;
                }
            }
        }
        return launched;
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    // ------------------------------------------------------------------ debug item entry point

    /** Raids the village nearest to the given position right now. Returns a message key for the player. */
    public static String raidNearest(MinecraftServer server, ServerLevel level, BlockPos from) {
        if (INSTANCE == null) {
            return "msg.barbarianraids.horn_none";
        }
        VillageSource.Village best = null;
        double bd = Double.MAX_VALUE;
        for (VillageSource.Village v : VillageSource.list(server)) {
            double d = horizontal(v.center(), from);
            if (d < bd) {
                bd = d;
                best = v;
            }
        }
        if (best == null) {
            return "msg.barbarianraids.horn_none";
        }
        return INSTANCE.spawnRaid(server, level, best.name(), best.center()) ? null : "msg.barbarianraids.horn_unloaded";
    }

    // ------------------------------------------------------------------ the raid itself

    private boolean spawnRaid(MinecraftServer server, ServerLevel level, String villageName, BlockPos center) {
        BarbarianData data = BarbarianData.get(server);
        String dim = level.dimension().location().toString();

        // nearest barbarian structure within range
        BarbarianData.Boss src = null;
        double bd = (double) BrConfig.VR_SOURCE_RADIUS.get() * BrConfig.VR_SOURCE_RADIUS.get();
        for (BarbarianData.Boss b : data.bosses.values()) {
            if (b.dead || !dim.equals(b.dim) || b.types.isEmpty()) {
                continue;
            }
            double d = horizontal(b.home, center);
            if (d < bd) {
                bd = d;
                src = b;
            }
        }
        List<String> srcTypes = new ArrayList<>();
        if (src != null) {
            for (String t : src.types) {
                if (!t.toLowerCase().contains("leader")) {
                    srcTypes.add(t);
                }
            }
            if (srcTypes.isEmpty()) {
                src = null;
            }
        }

        // where the raiders appear: on the side of their hideout, or anywhere around for homeless ones
        double ang = src != null
                ? Math.atan2(src.home.getZ() - center.getZ(), src.home.getX() - center.getX())
                : level.random.nextDouble() * Math.PI * 2.0D;
        BlockPos base = null;
        double dist = BrConfig.VR_SPAWN_DISTANCE.get();
        for (double f : new double[]{1.0D, 0.75D, 0.55D, 0.35D}) {
            BlockPos p = new BlockPos((int) (center.getX() + Math.cos(ang) * dist * f), 64,
                    (int) (center.getZ() + Math.sin(ang) * dist * f));
            if (level.isPositionEntityTicking(p)) {
                base = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p);
                break;
            }
        }
        if (base == null) {
            return false;
        }
        BlockPos target = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center);

        int min = BrConfig.RAID_MIN_SOLDIERS.get();
        int max = Math.max(min, BrConfig.RAID_MAX_SOLDIERS.get());
        int count = min + level.random.nextInt(max - min + 1);
        long end = level.getGameTime() + BrConfig.RAID_DURATION_MINUTES.get() * 1200L;
        String groupKey = src == null ? null : keyOf(data, src);

        List<PathfinderMob> party = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            PathfinderMob m = src != null ? create(level, srcTypes) : createHomeless(level);
            if (m == null) {
                continue;
            }
            int ox = level.random.nextInt(9) - 4;
            int oz = level.random.nextInt(9) - 4;
            BlockPos at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.offset(ox, 0, oz));
            m.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
            m.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null, null);
            CompoundTag d = m.getPersistentData();
            d.putString(Factions.ORIGIN, "RAID");
            if (groupKey != null) {
                d.putString(Factions.GROUP, groupKey);
                d.putLong(Factions.HOME, src.home.asLong());
            }
            d.putLong(Factions.RAID_END, end);
            d.putInt(Factions.RAID_TYPE, 0);
            d.putInt(Factions.TX, target.getX());
            d.putInt(Factions.TY, target.getY());
            d.putInt(Factions.TZ, target.getZ());
            d.putInt(Factions.BUDGET, BrConfig.TRACK_BLOCKS_PER_RAIDER.get());
            m.setPersistenceRequired();
            level.addFreshEntity(m);
            party.add(m);
        }
        if (party.isEmpty()) {
            return true; // nothing could be created; do not retry forever
        }
        if (src != null) {
            PathfinderMob strongest = party.get(0);
            for (PathfinderMob m : party) {
                if (m.getMaxHealth() > strongest.getMaxHealth()) {
                    strongest = m;
                }
            }
            BossManager.makeRaidBoss(strongest, src.home);
        }

        Component msg = src != null
                ? Component.translatable("msg.barbarianraids.vraid_structure", party.size(),
                        src.structure == null || src.structure.isEmpty() ? "?" : src.structure,
                        src.home.getX(), src.home.getZ(), villageName, center.getX(), center.getY(), center.getZ())
                : Component.translatable("msg.barbarianraids.vraid_homeless", party.size(),
                        villageName, center.getX(), center.getY(), center.getZ());
        server.getPlayerList().broadcastSystemMessage(msg, false);
        return true;
    }

    private static String keyOf(BarbarianData data, BarbarianData.Boss b) {
        for (var e : data.bosses.entrySet()) {
            if (e.getValue() == b) {
                return e.getKey();
            }
        }
        return null;
    }

    private static PathfinderMob create(ServerLevel level, List<String> ids) {
        for (int tries = 0; tries < 4; tries++) {
            String id = ids.get(level.random.nextInt(ids.size()));
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id));
            if (type == null) {
                continue;
            }
            Entity e = type.create(level);
            if (e instanceof PathfinderMob m && Factions.isFaction(m)) {
                return m;
            }
            if (e != null) {
                e.discard();
            }
        }
        return null;
    }

    private PathfinderMob createHomeless(ServerLevel level) {
        if (homelessTypes == null) {
            homelessTypes = new ArrayList<>();
            for (var en : ForgeRegistries.ENTITY_TYPES.getEntries()) {
                ResourceLocation id = en.getKey().location();
                String p = id.getPath();
                if ("hostile_civilization".equals(id.getNamespace()) && (p.startsWith("castaway") || p.startsWith("masked"))
                        && !p.contains("leader") && !p.contains("friendly") && !p.contains("spawner") && !p.contains("projectile")) {
                    homelessTypes.add(en.getValue());
                }
            }
        }
        if (homelessTypes.isEmpty()) {
            return null;
        }
        for (int tries = 0; tries < 8; tries++) {
            EntityType<?> type = homelessTypes.get(level.random.nextInt(homelessTypes.size()));
            Entity e = type.create(level);
            if (e instanceof PathfinderMob m && Factions.isFaction(m)) {
                return m;
            }
            if (e != null) {
                e.discard();
            }
        }
        return null;
    }
}
