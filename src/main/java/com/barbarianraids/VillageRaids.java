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
 * Every 3-4 days (random moment) the three Recruits Villages nearest to a player are raided. The raiders come from the
 * nearest barbarian structure (its own soldiers) or, when there is none in range, are homeless barbarians. After 3-6
 * ordinary waves a reinforced one comes (more and stronger raiders). A raid is over as soon as its village is destroyed:
 * the raiders then go home (homeless ones melt away).
 */
public class VillageRaids {
    public static VillageRaids INSTANCE;

    private static final long DAY = 24000L;

    private record Pending(BlockPos center, String name, long deadline, boolean strong) {}

    /** A raid in progress: when its village disappears from the Recruits Villages data, the raid is over. */
    private record Active(BlockPos center, String name, long end) {}

    private final List<Active> active = new ArrayList<>();
    private static final java.util.Set<Long> OVER = new java.util.HashSet<>();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("barbarianraids");
    private final net.minecraft.server.level.ServerBossEvent bar = new net.minecraft.server.level.ServerBossEvent(
            Component.empty(), net.minecraft.world.BossEvent.BossBarColor.YELLOW, net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** True when the village at these (raid target) coordinates has been destroyed during the raid. */
    public static boolean isOver(int x, int z) {
        return !OVER.isEmpty() && OVER.contains(key(x, z));
    }

    private final List<Pending> pending = new ArrayList<>();
    private int counter = 0;
    private List<EntityType<?>> homelessTypes = null;

    public VillageRaids() {
        INSTANCE = this;
    }

    // ------------------------------------------------------------------ scheduler

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !BrConfig.VR_ENABLED.get()) {
            return;
        }
        counter++;
        if (counter % 20 == 0) {
            warn(event.getServer());
        }
        if (counter % 100 != 0) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        watchVillages(server, now);

        // retry raids whose spawn area was not loaded yet
        if (!pending.isEmpty()) {
            List<Pending> copy = new ArrayList<>(pending);
            pending.clear();
            for (Pending p : copy) {
                if (now > p.deadline()) {
                    continue;
                }
                if (!spawnRaid(server, level, p.name(), p.center(), p.strong())) {
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
        if (data.untilStrong < 0) {
            data.untilStrong = rollStrong(level);
        }
        boolean strong = data.untilStrong == 0;
        int launched = launchWave(server, level, strong);
        if (launched > 0) {
            data.untilStrong = strong ? rollStrong(level) : data.untilStrong - 1;
            LOG.info("[BarbarianRaids] village raid wave: {} village(s), reinforced={}, ordinary waves before the next reinforced one: {}",
                    launched, strong, data.untilStrong);
        }
        data.nextVillageRaid = now + (launched > 0 ? interval(level) : 6000L);
        data.setDirty();
    }

    /** A random moment between min and max days (not a whole number of days). */
    private static long interval(ServerLevel level) {
        long min = Math.min(BrConfig.VR_MIN_DAYS.get(), BrConfig.VR_MAX_DAYS.get()) * DAY;
        long max = Math.max(BrConfig.VR_MIN_DAYS.get(), BrConfig.VR_MAX_DAYS.get()) * DAY;
        return min + (max > min ? level.random.nextInt((int) (max - min) + 1) : 0);
    }

    private static int rollStrong(ServerLevel level) {
        int min = Math.min(BrConfig.VR_STRONG_MIN.get(), BrConfig.VR_STRONG_MAX.get());
        int max = Math.max(BrConfig.VR_STRONG_MIN.get(), BrConfig.VR_STRONG_MAX.get());
        return min + level.random.nextInt(max - min + 1);
    }

    /** Boss bar with a live countdown before the wave. */
    private void warn(MinecraftServer server) {
        int minutes = BrConfig.VR_WARN_MINUTES.get();
        BarbarianData data = BarbarianData.get(server);
        long left = data.nextVillageRaid - server.overworld().getGameTime();
        if (minutes <= 0 || data.nextVillageRaid == 0L || left <= 0L || left > minutes * 1200L) {
            if (!bar.getPlayers().isEmpty()) {
                bar.removeAllPlayers();
            }
            return;
        }
        long s = left / 20L;
        boolean strong = data.untilStrong == 0;
        bar.setName(Component.translatable(strong ? "bar.barbarianraids.soon_strong" : "bar.barbarianraids.soon",
                String.format("%d:%02d", s / 60L, s % 60L)));
        bar.setColor(strong ? net.minecraft.world.BossEvent.BossBarColor.RED : net.minecraft.world.BossEvent.BossBarColor.YELLOW);
        bar.setProgress(Math.max(0.0F, Math.min(1.0F, left / (minutes * 1200.0F))));
        for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
            bar.addPlayer(p);
        }
    }

    /** Raids whose village no longer exists are over. */
    private void watchVillages(MinecraftServer server, long now) {
        if (active.isEmpty()) {
            if (!OVER.isEmpty()) {
                OVER.clear();
            }
            return;
        }
        List<VillageSource.Village> all = VillageSource.list(server);
        java.util.Iterator<Active> it = active.iterator();
        while (it.hasNext()) {
            Active a = it.next();
            if (now > a.end() + 1200L) {
                OVER.remove(key(a.center().getX(), a.center().getZ()));
                it.remove();
                continue;
            }
            if (OVER.contains(key(a.center().getX(), a.center().getZ())) || !VillageSource.available()) {
                continue;
            }
            boolean alive = false;
            for (VillageSource.Village v : all) {
                if (horizontal(v.center(), a.center()) <= 48.0D * 48.0D) {
                    alive = true;
                    break;
                }
            }
            if (!alive) {
                OVER.add(key(a.center().getX(), a.center().getZ()));
                LOG.info("[BarbarianRaids] village '{}' at {} is destroyed: the raid is over, raiders leave", a.name(), a.center().toShortString());
                server.getPlayerList().broadcastSystemMessage(Component.translatable("msg.barbarianraids.vraid_over", a.name()), false);
            }
        }
    }

    /** Debug item: moves the next wave closer (negative) or further away (positive). Returns seconds left. */
    public static long shift(MinecraftServer server, long ticks) {
        BarbarianData data = BarbarianData.get(server);
        long now = server.overworld().getGameTime();
        if (data.nextVillageRaid == 0L) {
            data.nextVillageRaid = now + 6000L;
        }
        data.nextVillageRaid = Math.max(now + 200L, data.nextVillageRaid + ticks);
        data.setDirty();
        return (data.nextVillageRaid - now) / 20L;
    }

    private int launchWave(MinecraftServer server, ServerLevel level, boolean strong) {
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
                if (spawnRaid(server, level, v.name(), v.center(), strong)) {
                    launched++;
                } else {
                    pending.add(new Pending(v.center(), v.name(), level.getGameTime() + 2400L, strong));
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
    public static String raidNearest(MinecraftServer server, ServerLevel level, BlockPos from, boolean strong) {
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
        return INSTANCE.spawnRaid(server, level, best.name(), best.center(), strong) ? null : "msg.barbarianraids.horn_unloaded";
    }

    // ------------------------------------------------------------------ the raid itself

    private boolean spawnRaid(MinecraftServer server, ServerLevel level, String villageName, BlockPos center, boolean strong) {
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
        if (strong) {
            count = (int) Math.round(count * BrConfig.VR_STRONG_COUNT.get());
        }
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
            if (strong) {
                boost(m, net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH, BrConfig.VR_STRONG_HEALTH.get());
                boost(m, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE, BrConfig.VR_STRONG_DAMAGE.get());
                m.setHealth(m.getMaxHealth());
            }
            level.addFreshEntity(m);
            if (src == null && BrConfig.VR_HOMELESS_NO_ARMOR.get()) {
                for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{
                        net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                        net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
                    m.setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
                }
            }
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

        active.removeIf(a -> a.center().getX() == target.getX() && a.center().getZ() == target.getZ());
        OVER.remove(key(target.getX(), target.getZ()));
        active.add(new Active(target, villageName, end));
        LOG.info("[BarbarianRaids] raid on '{}' at {}: {} raiders, reinforced={}, source={}", villageName, center.toShortString(),
                party.size(), strong, src == null ? "homeless" : src.structure);
        net.minecraft.sounds.SoundEvent horn = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("minecraft", "event.raid.horn"));
        for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (horn != null) {
                p.playNotifySound(horn, net.minecraft.sounds.SoundSource.HOSTILE, 64.0F, strong ? 0.6F : 1.0F);
            }
            if (strong) {
                p.sendSystemMessage(Component.translatable("msg.barbarianraids.vraid_strong")
                        .withStyle(net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BOLD));
            }
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

    private static void boost(PathfinderMob m, net.minecraft.world.entity.ai.attributes.Attribute attr, double mult) {
        var inst = m.getAttribute(attr);
        if (inst != null && mult > 1.0D) {
            inst.setBaseValue(inst.getBaseValue() * mult);
        }
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
            List<? extends String> custom = BrConfig.VR_HOMELESS_TYPES.get();
            for (var en : ForgeRegistries.ENTITY_TYPES.getEntries()) {
                ResourceLocation id = en.getKey().location();
                boolean ok = custom.isEmpty()
                        ? "hostile_civilization".equals(id.getNamespace()) && id.getPath().matches("castaway_?\\d+")
                        : custom.contains(id.toString());
                if (ok) {
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
