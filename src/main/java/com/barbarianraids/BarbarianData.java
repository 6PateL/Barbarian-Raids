package com.barbarianraids;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Persistent registry of hideouts (structure bosses) and spawner budgets. */
public class BarbarianData extends SavedData {
    private static final String NAME = "barbarianraids_data";

    public static final class Boss {
        public String dim;
        public UUID uuid;
        public boolean dead;
        public BlockPos home;
        public long nextRaid;
        public double score;
        public String structure = "";
    }

    public final Map<String, Boss> bosses = new HashMap<>();
    public final Map<String, Integer> spawned = new HashMap<>();

    public static BarbarianData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(BarbarianData::load, BarbarianData::new, NAME);
    }

    public static BarbarianData load(CompoundTag tag) {
        BarbarianData d = new BarbarianData();
        ListTag list = tag.getList("bosses", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            Boss b = new Boss();
            b.dim = c.getString("dim");
            b.uuid = c.getUUID("uuid");
            b.dead = c.getBoolean("dead");
            b.home = BlockPos.of(c.getLong("home"));
            b.nextRaid = c.getLong("next");
            b.score = c.getDouble("score");
            b.structure = c.getString("structure");
            d.bosses.put(c.getString("key"), b);
        }
        CompoundTag sp = tag.getCompound("spawned");
        for (String k : sp.getAllKeys()) {
            d.spawned.put(k, sp.getInt(k));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Boss> e : bosses.entrySet()) {
            Boss b = e.getValue();
            CompoundTag c = new CompoundTag();
            c.putString("key", e.getKey());
            c.putString("dim", b.dim);
            c.putUUID("uuid", b.uuid);
            c.putBoolean("dead", b.dead);
            c.putLong("home", b.home.asLong());
            c.putLong("next", b.nextRaid);
            c.putDouble("score", b.score);
            c.putString("structure", b.structure == null ? "" : b.structure);
            list.add(c);
        }
        tag.put("bosses", list);
        CompoundTag sp = new CompoundTag();
        for (Map.Entry<String, Integer> e : spawned.entrySet()) {
            sp.putInt(e.getKey(), e.getValue());
        }
        tag.put("spawned", sp);
        return tag;
    }
}
