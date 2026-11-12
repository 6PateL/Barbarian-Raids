package com.barbarianraids;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Debug tool (creative tab). Shift + right click switches the mode, right click uses it: raid the nearest village now,
 * reinforced raid now, bring the scheduled wave closer, push it back.
 */
public class RaidHornItem extends Item {
    private static final int MODES = 4;
    private static final long STEP = 6000L; // a quarter of a day

    public RaidHornItem() {
        super(new Item.Properties().stacksTo(1));
    }

    private static int mode(ItemStack s) {
        CompoundTag t = s.getTag();
        return t == null ? 0 : Math.floorMod(t.getInt("mode"), MODES);
    }

    private static String time(long s) {
        return s >= 3600L ? String.format("%d:%02d:%02d", s / 3600L, s % 3600L / 60L, s % 60L) : String.format("%d:%02d", s / 60L, s % 60L);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && level instanceof ServerLevel sl) {
            int m = mode(stack);
            if (player.isShiftKeyDown()) {
                m = (m + 1) % MODES;
                stack.getOrCreateTag().putInt("mode", m);
                player.displayClientMessage(Component.translatable("msg.barbarianraids.horn_mode",
                        Component.translatable("msg.barbarianraids.horn_mode." + m)).withStyle(ChatFormatting.YELLOW), true);
                return InteractionResultHolder.success(stack);
            }
            if (m <= 1) {
                String err = VillageRaids.raidNearest(sl.getServer(), sl, player.blockPosition(), m == 1);
                if (err != null) {
                    player.displayClientMessage(Component.translatable(err), true);
                }
                player.getCooldowns().addCooldown(this, 40);
            } else {
                long left = VillageRaids.shift(sl.getServer(), m == 2 ? -STEP : STEP);
                player.displayClientMessage(Component.translatable(m == 2 ? "msg.barbarianraids.horn_sooner" : "msg.barbarianraids.horn_later",
                        time(left)), true);
                player.getCooldowns().addCooldown(this, 5);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tips, TooltipFlag flag) {
        tips.add(Component.translatable("msg.barbarianraids.horn_mode", Component.translatable("msg.barbarianraids.horn_mode." + mode(stack)))
                .withStyle(ChatFormatting.GRAY));
        tips.add(Component.translatable("msg.barbarianraids.horn_hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
