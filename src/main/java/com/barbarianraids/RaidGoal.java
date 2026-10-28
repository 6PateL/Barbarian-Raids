package com.barbarianraids;

import com.simibubi.create.content.trains.track.TrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/** Walks raiders to their target (a settlement or a Create railway), makes them attack / break rails, then sends them home. */
public class RaidGoal extends Goal {
    private final PathfinderMob mob;
    private int tick;
    private int idle;

    public RaidGoal(PathfinderMob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    public static void clearRaid(CompoundTag d) {
        d.remove(Factions.RAID_END);
        d.remove(Factions.RAID_TYPE);
        d.remove(Factions.TX);
        d.remove(Factions.TY);
        d.remove(Factions.TZ);
        d.remove(Factions.BUDGET);
    }

    private boolean raiding() {
        CompoundTag d = mob.getPersistentData();
        return d.contains(Factions.RAID_END) && mob.level().getGameTime() < d.getLong(Factions.RAID_END)
                && !d.getBoolean(Factions.PACIFIED);
    }

    private boolean returning() {
        CompoundTag d = mob.getPersistentData();
        return d.getBoolean(Factions.RETURNING) && d.contains(Factions.HOME);
    }

    @Override
    public boolean canUse() {
        CompoundTag d = mob.getPersistentData();
        if (d.contains(Factions.RAID_END) && !raiding()) {
            endRaid();
        }
        LivingEntity t = mob.getTarget();
        if (t != null && t.isAlive()) {
            return false; // normal melee AI handles the fight
        }
        return raiding() || returning();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        tick = 0;
        idle = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    private void endRaid() {
        CompoundTag d = mob.getPersistentData();
        clearRaid(d);
        d.putBoolean(Factions.RETURNING, true);
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        tick++;
        if (tick % 10 != 0) {
            return;
        }
        CompoundTag d = mob.getPersistentData();

        if (!raiding()) {
            if (returning()) {
                BlockPos home = BlockPos.of(d.getLong(Factions.HOME));
                if (mob.blockPosition().distSqr(home) < 36.0D) {
                    d.remove(Factions.RETURNING);
                } else if (tick % 20 == 0) {
                    stepToward(level, Vec3.atCenterOf(home));
                }
            }
            return;
        }

        BlockPos target = new BlockPos(d.getInt(Factions.TX), d.getInt(Factions.TY), d.getInt(Factions.TZ));
        int type = d.getInt(Factions.RAID_TYPE);
        double dist2 = mob.blockPosition().distSqr(target);

        if (type == 0) {
            LivingEntity victim = nearestVictim(level, 28.0D);
            if (victim != null) {
                mob.setTarget(victim);
                idle = 0;
                return;
            }
            if (dist2 > 14.0D * 14.0D) {
                if (tick % 20 == 0) {
                    stepToward(level, Vec3.atCenterOf(target));
                }
            } else if ((idle += 10) > 600) {
                endRaid(); // nobody left here
            }
        } else {
            if (dist2 > 3.5D * 3.5D) {
                if (tick % 20 == 0) {
                    stepToward(level, Vec3.atCenterOf(target));
                }
                return;
            }
            if (tick % 20 != 0) {
                return;
            }
            int budget = d.getInt(Factions.BUDGET);
            int broken = 0;
            BlockPos base = mob.blockPosition();
            for (BlockPos p : BlockPos.betweenClosed(base.offset(-3, -2, -3), base.offset(3, 2, 3))) {
                if (budget - broken <= 0 || broken >= 2) {
                    break;
                }
                if (level.getBlockState(p).getBlock() instanceof TrackBlock) {
                    level.destroyBlock(p.immutable(), true, mob);
                    broken++;
                }
            }
            if (broken > 0) {
                mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                d.putInt(Factions.BUDGET, budget - broken);
                idle = 0;
            } else if ((idle += 20) > 100) {
                endRaid(); // no more track within reach
            }
            if (d.getInt(Factions.BUDGET) <= 0) {
                endRaid();
            }
        }
    }

    private LivingEntity nearestVictim(ServerLevel level, double radius) {
        AABB box = mob.getBoundingBox().inflate(radius, 12.0D, radius);
        List<LivingEntity> list = level.getEntitiesOfClass(LivingEntity.class, box, BossManager::isVictim);
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity e : list) {
            double dd = e.distanceToSqr(mob);
            if (dd < bd) {
                bd = dd;
                best = e;
            }
        }
        return best;
    }

    /** Pathfinding only works over short ranges, so walk to a waypoint up to 16 blocks away in the right direction. */
    private void stepToward(ServerLevel level, Vec3 goal) {
        Vec3 pos = mob.position();
        Vec3 delta = goal.subtract(pos);
        double len = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (len < 1.0D) {
            return;
        }
        double step = Math.min(len, 16.0D);
        double wx = pos.x + delta.x / len * step;
        double wz = pos.z + delta.z / len * step;
        BlockPos wp = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(wx, pos.y, wz));
        double wy = len <= 16.0D ? goal.y : wp.getY();
        mob.getNavigation().moveTo(wx, wy, wz, 1.15D);
    }
}
