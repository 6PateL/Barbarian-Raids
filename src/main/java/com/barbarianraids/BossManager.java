package com.barbarianraids;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hideout bosses, hostility towards settlements, and the peace that follows a boss's death.
 */
public class BossManager {
    private static final UUID BOSS_HP = UUID.fromString("5b1d7a52-1f0e-4a52-9d4b-0a9e3b6c0001");
    private static final UUID RAID_BOSS_HP = UUID.fromString("5b1d7a52-1f0e-4a52-9d4b-0a9e3b6c0002");

    // ------------------------------------------------------------------ victims

    public static boolean isVictim(LivingEntity t) {
        return t.isAlive() && (t instanceof AbstractVillager || t instanceof AbstractRecruitEntity);
    }

    // ------------------------------------------------------------------ join

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Factions.isFaction(event.getEntity())) {
            return;
        }
        PathfinderMob mob = (PathfinderMob) event.getEntity();
        addGoals(mob);

        CompoundTag d = mob.getPersistentData();
        if ("NATURAL".equals(d.getString(Factions.ORIGIN))) {
            return; // wild barbarians have no hideout
        }
        BarbarianData data = BarbarianData.get(level.getServer());
        String group = d.getString(Factions.GROUP);
        if (group.isEmpty()) {
            group = findGroup(level, mob, data);
            if (group == null) {
                return;
            }
            d.putString(Factions.GROUP, group);
        }
        BarbarianData.Boss boss = data.bosses.get(group);
        if (boss != null && boss.dead) {
            pacify(mob);
            return;
        }
        if (d.getBoolean(Factions.BOSS)) {
            if (boss == null || boss.uuid.equals(mob.getUUID())) {
                if (boss != null) {
                    applyBossLook(mob, boss.home);
                }
            } else {
                demote(mob); // a replacement boss was already chosen for this hideout
            }
            return;
        }
        maybePromote(level, data, group, mob);
    }

    private static void addGoals(PathfinderMob mob) {
        for (WrappedGoal g : mob.goalSelector.getAvailableGoals()) {
            if (g.getGoal() instanceof RaidGoal) {
                return;
            }
        }
        mob.goalSelector.addGoal(1, new RaidGoal(mob));
        mob.goalSelector.addGoal(2, new RailHunterGoal(mob));
        if (BrConfig.ATTACK_VILLAGERS_AND_RECRUITS.get()) {
            mob.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(mob, LivingEntity.class, 20, true, false,
                    t -> !mob.getPersistentData().getBoolean(Factions.PACIFIED) && isVictim(t)));
        }
        // mobs of listed mods (Flesh That Hates...) are fought at once
        mob.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(mob, Mob.class, 10, false, false,
                t -> t.isAlive() && Factions.isExtraEnemy(t)));
        // barbarians fight monsters that are hostile to the player (also the only thing they do after their boss dies)
        if (BrConfig.FIGHT_MONSTERS.get()) {
            mob.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(mob, Mob.class, 40, true, false,
                    t -> t instanceof Enemy && !Factions.isFaction(t) && !isHcMob(t) && !Factions.isExtraEnemy(t)));
        }
    }

    private static boolean isHcMob(Entity e) {
        return e.getClass().getName().startsWith("net.mcreator.hostilecivilization");
    }

    /** Ordinary hostile mobs (zombies, skeletons...) also attack barbarians, for balance. */
    @SubscribeEvent
    public void onMonsterJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        // village / hired recruits (not the trading nobles) actively fight barbarians instead of ignoring them
        if (event.getEntity() instanceof AbstractRecruitEntity r
                && !(r instanceof com.talhanation.recruits.entities.VillagerNobleEntity)) {
            if (BrConfig.RECRUITS_ATTACK_FACTIONS.get()) {
                for (WrappedGoal g : r.targetSelector.getAvailableGoals()) {
                    if (g.getGoal() instanceof FactionHunter) {
                        return;
                    }
                }
                r.targetSelector.addGoal(5, new FactionHunter(r, 40, true));
            }
            return;
        }
        if (!BrConfig.MONSTERS_ATTACK_FACTIONS.get()) {
            return;
        }
        if (!(event.getEntity() instanceof PathfinderMob m) || !(m instanceof Enemy) || Factions.isFaction(m) || isHcMob(m)) {
            return;
        }
        for (WrappedGoal g : m.targetSelector.getAvailableGoals()) {
            if (g.getGoal() instanceof FactionHunter) {
                return;
            }
        }
        if (Factions.isExtraEnemy(m)) {
            m.targetSelector.addGoal(1, new FactionHunter(m, 10, false));
            // make sure they really walk up and hit (some of these mobs only know how to fight players)
            m.goalSelector.addGoal(2, new net.minecraft.world.entity.ai.goal.MeleeAttackGoal(m, 1.2D, false));
        } else {
            m.targetSelector.addGoal(4, new FactionHunter(m, 80, true));
        }
    }

    private static class FactionHunter extends NearestAttackableTargetGoal<PathfinderMob> {
        FactionHunter(net.minecraft.world.entity.Mob m, int interval, boolean mustSee) {
            super(m, PathfinderMob.class, interval, mustSee, false,
                    t -> Factions.isFaction(t) && !t.getPersistentData().getBoolean(Factions.PACIFIED)
                            && Math.abs(t.getY() - m.getY()) < 5.0D);
        }
    }

    private static String findGroup(ServerLevel level, Mob mob, BarbarianData data) {
        String dim = level.dimension().location().toString();
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        BlockPos pos = mob.blockPosition();
        for (Map.Entry<Structure, LongSet> en : level.structureManager().getAllStructuresAt(pos).entrySet()) {
            ResourceLocation id = registry.getKey(en.getKey());
            if (id == null || !"hostile_civilization".equals(id.getNamespace())) {
                continue;
            }
            StructureStart start = level.structureManager().getStructureAt(pos, en.getKey());
            if (start != null && start.isValid()) {
                String key = dim + "|" + start.getChunkPos().x + "," + start.getChunkPos().z;
                String near = nearestBossKey(data, dim, pos, BrConfig.BOSS_MERGE_RADIUS.get());
                return (near != null && !data.bosses.containsKey(key)) ? near : key;
            }
        }
        // standing in the yard of a known hideout
        return nearestBossKey(data, dim, pos, 40);
    }

    /** Readable name of the Hostile Civilization structure at pos, e.g. "Castaway Hideout". */
    static String structureName(ServerLevel level, BlockPos pos) {
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Map.Entry<Structure, LongSet> en : level.structureManager().getAllStructuresAt(pos).entrySet()) {
            ResourceLocation id = registry.getKey(en.getKey());
            if (id != null && "hostile_civilization".equals(id.getNamespace())) {
                StringBuilder sb = new StringBuilder();
                for (String w : id.getPath().split("[_/]")) {
                    if (!w.isEmpty()) {
                        sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
                    }
                }
                return sb.toString().trim();
            }
        }
        return "";
    }

    private static String nameOf(BarbarianData.Boss b) {
        return (b.structure == null || b.structure.isEmpty()) ? "?" : b.structure;
    }

    /** Key of the closest hideout whose home is within {@code radius} blocks, or null. */
    private static String nearestBossKey(BarbarianData data, String dim, BlockPos pos, int radius) {
        String best = null;
        double bestD = (double) radius * radius;
        for (Map.Entry<String, BarbarianData.Boss> e : data.bosses.entrySet()) {
            if (!dim.equals(e.getValue().dim)) {
                continue;
            }
            double dd = e.getValue().home.distSqr(pos);
            if (dd < bestD) {
                bestD = dd;
                best = e.getKey();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ one boss per hideout (safety net)

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 100 != 0) {
            return;
        }
        BarbarianData data = BarbarianData.get(event.getServer());
        if (data.bosses.isEmpty()) {
            return;
        }
        double r = BrConfig.BOSS_MERGE_RADIUS.get();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            String dim = level.dimension().location().toString();
            for (Map.Entry<String, BarbarianData.Boss> en : new ArrayList<>(data.bosses.entrySet())) {
                BarbarianData.Boss boss = en.getValue();
                if (boss.dead || !dim.equals(boss.dim) || !level.hasChunkAt(boss.home)) {
                    continue;
                }
                AABB box = new AABB(boss.home).inflate(r, 128.0D, r);
                List<PathfinderMob> tagged = level.getEntitiesOfClass(PathfinderMob.class, box,
                        m -> m.isAlive() && Factions.isFaction(m) && m.getPersistentData().getBoolean(Factions.BOSS));
                if (tagged.size() < 2) {
                    continue;
                }
                PathfinderMob keep = null;
                for (PathfinderMob m : tagged) {
                    if (m.getUUID().equals(boss.uuid)) {
                        keep = m;
                        break;
                    }
                }
                if (keep == null) {
                    for (PathfinderMob m : tagged) {
                        if (keep == null || m.getMaxHealth() > keep.getMaxHealth()) {
                            keep = m;
                        }
                    }
                    boss.uuid = keep.getUUID();
                    data.setDirty();
                }
                keep.getPersistentData().putString(Factions.GROUP, en.getKey());
                for (PathfinderMob m : tagged) {
                    if (m != keep) {
                        demote(m);
                        m.getPersistentData().putString(Factions.GROUP, en.getKey());
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ boss promotion

    private void maybePromote(ServerLevel level, BarbarianData data, String group, PathfinderMob mob) {
        double score = mob.getAttributeBaseValue(Attributes.MAX_HEALTH) + (Factions.isLeaderClass(mob) ? 1000.0D : 0.0D);
        BarbarianData.Boss boss = data.bosses.get(group);
        long now = level.getGameTime();
        if (boss == null) {
            boss = new BarbarianData.Boss();
            boss.dim = level.dimension().location().toString();
            boss.home = mob.blockPosition();
            boss.uuid = mob.getUUID();
            boss.score = score;
            boss.structure = structureName(level, mob.blockPosition());
            boss.nextRaid = now + RaidManager.randomInterval(level);
            data.bosses.put(group, boss);
            data.setDirty();
            promote(mob, boss.home);
            return;
        }
        if (boss.uuid.equals(mob.getUUID())) {
            promote(mob, boss.home);
            return;
        }
        boolean bossGone = !boss.dead && level.getEntity(boss.uuid) == null && level.hasChunkAt(boss.home);
        if (bossGone || score > boss.score * 1.25D + 1.0D) {
            Entity old = level.getEntity(boss.uuid);
            if (old instanceof PathfinderMob oldMob) {
                demote(oldMob);
            }
            boss.uuid = mob.getUUID();
            boss.score = score;
            data.setDirty();
            promote(mob, boss.home);
        }
    }

    private static void promote(PathfinderMob mob, BlockPos home) {
        mob.getPersistentData().putBoolean(Factions.BOSS, true);
        applyBossLook(mob, home);
    }

    private static void demote(PathfinderMob mob) {
        mob.getPersistentData().remove(Factions.BOSS);
        AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) {
            hp.removeModifier(BOSS_HP);
        }
        mob.setCustomName(null);
        mob.setCustomNameVisible(false);
        mob.clearRestriction();
    }

    private static void applyBossLook(PathfinderMob mob, BlockPos home) {
        AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null && hp.getModifier(BOSS_HP) == null) {
            hp.addPermanentModifier(new AttributeModifier(BOSS_HP, "barbarian boss",
                    BrConfig.BOSS_HEALTH_MULTIPLIER.get() - 1.0D, AttributeModifier.Operation.MULTIPLY_TOTAL));
            mob.setHealth(mob.getMaxHealth());
        }
        mob.setCustomName(Component.literal("Boss").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        mob.setCustomNameVisible(true);
        mob.setPersistenceRequired();
        mob.restrictTo(home, BrConfig.BOSS_LEASH_RADIUS.get());
    }

    /** Called by the raid manager for the leader of a raiding party. */
    public static void makeRaidBoss(PathfinderMob mob, BlockPos hideout) {
        CompoundTag d = mob.getPersistentData();
        d.putBoolean(Factions.RAID_BOSS, true);
        d.putLong(Factions.HIDEOUT, hideout.asLong());
        AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null && hp.getModifier(RAID_BOSS_HP) == null) {
            hp.addPermanentModifier(new AttributeModifier(RAID_BOSS_HP, "raid boss",
                    BrConfig.RAID_BOSS_HEALTH_MULTIPLIER.get() - 1.0D, AttributeModifier.Operation.MULTIPLY_TOTAL));
            mob.setHealth(mob.getMaxHealth());
        }
        mob.setCustomName(Component.literal("Raid Boss").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        mob.setCustomNameVisible(true);
        mob.setPersistenceRequired();
    }

    // ------------------------------------------------------------------ peace

    public static void pacify(Mob mob) {
        CompoundTag d = mob.getPersistentData();
        d.putBoolean(Factions.PACIFIED, true);
        RaidGoal.clearRaid(d);
        d.remove(Factions.RETURNING);
        LivingEntity t = mob.getTarget();
        if (t != null && (t instanceof Player || isVictim(t) || !(t instanceof Enemy) || Factions.isFaction(t))) {
            mob.setTarget(null);
            mob.getNavigation().stop();
        }
    }

    @SubscribeEvent
    public void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity self = event.getEntity();
        if (!(self instanceof Mob) || !self.getPersistentData().getBoolean(Factions.PACIFIED)) {
            return;
        }
        LivingEntity t = event.getNewTarget();
        if (t == null || t == self.getLastHurtByMob()) {
            return;
        }
        if (t instanceof Enemy && !Factions.isFaction(t)) {
            return;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide || !(dead.level() instanceof ServerLevel level) || !Factions.isFaction(dead)) {
            return;
        }
        CompoundTag d = dead.getPersistentData();
        if (!d.getBoolean(Factions.BOSS)) {
            return;
        }
        String group = d.getString(Factions.GROUP);
        BarbarianData data = BarbarianData.get(level.getServer());
        BarbarianData.Boss boss = data.bosses.get(group);
        if (boss == null || boss.dead) {
            return;
        }
        boss.dead = true;
        data.setDirty();
        AABB box = new AABB(boss.home).inflate(96.0D, 64.0D, 96.0D);
        for (PathfinderMob m : level.getEntitiesOfClass(PathfinderMob.class, box, Factions::isFaction)) {
            if (group.equals(m.getPersistentData().getString(Factions.GROUP))) {
                pacify(m);
            }
        }
        if (boss.structure == null || boss.structure.isEmpty()) {
            boss.structure = structureName(level, boss.home);
            data.setDirty();
        }
        Component msg = Component.translatable("msg.barbarianraids.boss_dead",
                nameOf(boss), boss.home.getX(), boss.home.getY(), boss.home.getZ());
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            p.displayClientMessage(msg, false);
        }
    }

    // ------------------------------------------------------------------ raid boss loot

    private static ServerLevel level0(LivingEntity e) {
        return e.level() instanceof ServerLevel sl ? sl : null;
    }

    @SubscribeEvent
    public void onDrops(LivingDropsEvent event) {
        LivingEntity dead = event.getEntity();
        CompoundTag d = dead.getPersistentData();
        if (dead.level().isClientSide || !d.getBoolean(Factions.RAID_BOSS) || !d.contains(Factions.HIDEOUT)) {
            return;
        }
        BlockPos hideout = BlockPos.of(d.getLong(Factions.HIDEOUT));
        String dim = dead.level().dimension().location().toString();

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = book.getOrCreateTag();
        tag.putString("title", Component.translatable("item.barbarianraids.book_title").getString());
        tag.putString("author", "Barbarians");
        ListTag pages = new ListTag();
        String sname = "?";
        if (level0(dead) != null) {
            sname = structureName(level0(dead), hideout);
            if (sname.isEmpty()) {
                sname = "?";
            }
        }
        Component text = Component.translatable("item.barbarianraids.book_text",
                sname, hideout.getX(), hideout.getY(), hideout.getZ(), dim);
        pages.add(StringTag.valueOf(Component.Serializer.toJson(text)));
        tag.put("pages", pages);
        event.getDrops().add(new ItemEntity(dead.level(), dead.getX(), dead.getY(), dead.getZ(), book));
    }
}
