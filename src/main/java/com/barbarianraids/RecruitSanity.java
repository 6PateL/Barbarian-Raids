package com.barbarianraids;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Recruits sometimes lock onto mobs underground (burrowers, tunnelling mobs, buried monsters) and then stare, walk
 * or shoot at the floor. Whatever mod picks the target, such targets are refused here.
 */
public class RecruitSanity {
    @SubscribeEvent
    public void onChangeTarget(LivingChangeTargetEvent event) {
        if (!BrConfig.RECRUIT_IGNORE_UNDERGROUND.get() || !(event.getEntity() instanceof AbstractRecruitEntity recruit)) {
            return;
        }
        LivingEntity t = event.getNewTarget();
        if (t == null) {
            return;
        }
        double dy = t.getY() - recruit.getY();
        if (Math.abs(dy) > 6.0D || (dy < -1.5D && !recruit.hasLineOfSight(t))) {
            event.setCanceled(true);
        }
    }
}
