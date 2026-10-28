package com.barbarianraids;

import com.simibubi.create.content.trains.track.TrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.List;

/** Even without a raid, any barbarian that notices Create tracks nearby walks over and breaks them. */
public class RailHunterGoal extends Goal {
    private static final String WANDER_BUDGET = "br_rail_budget";

    private final PathfinderMob mob;
    private long nextCheck = 0L;
    private BlockPos target;
    private int tick;
    private int idle;

    public RailHunterGoal(PathfinderMob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    private boolean eligible() {
        CompoundTag d = mob.getPersistentData();
        return !d.getBoolean(Factions.PACIFIED) && !d.getBoolean(Factions.BOSS)
                && !d.contains(Factions.RAID_END) && !d.getBoolean(Factions.RETURNING)
                && budget() > 0;
    }

    private int budget() {
        CompoundTag d = mob.getPersistentData();
        if (!d.contains(WANDER_BUDGET)) {
            d.putInt(WANDER_BUDGET, BrConfig.WANDER_TRACK_BLOCKS.get());
        }
        return d.getInt(WANDER_BUDGET);
    }

    @Override
    public boolean canUse() {
        if (!BrConfig.HUNT_RAILS.get() || !(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        long now = level.getGameTime();
        if (now < nextCheck) {
            return false;
        }
        nextCheck = now + 100L + level.random.nextInt(100);
        if (mob.getTarget() != null || !eligible()) {
            return false;
        }
        List<BlockPos> tracks = TrackScanner.find(level, mob.blockPosition(), BrConfig.RAIL_SENSE_RADIUS.get());
        if (tracks.isEmpty()) {
            return false;
        }
        target = tracks.get(0);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && mob.getTarget() == null && eligible() && idle <= 100;
    }

    @Override
    public void start() {
        tick = 0;
        idle = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        target = null;
    }

    @Override
    public void tick() {
        if (target == null || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        tick++;
        if (mob.blockPosition().distSqr(target) > 3.5D * 3.5D) {
            if (tick % 20 == 1) {
                mob.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.1D);
            }
            if (tick > 600) {
                idle = 1000; // cannot reach it
            }
            return;
        }
        if (tick % 20 != 0) {
            return;
        }
        int budget = budget();
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
            mob.getPersistentData().putInt(WANDER_BUDGET, budget - broken);
            idle = 0;
        } else {
            idle += 20;
        }
    }
}
