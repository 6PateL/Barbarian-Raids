package com.barbarianraids;

import net.minecraftforge.common.ForgeConfigSpec;

public class BrConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.DoubleValue NATURAL_SPAWN_CHANCE;
    public static final ForgeConfigSpec.DoubleValue SPAWNER_SPAWN_CHANCE;
    public static final ForgeConfigSpec.IntValue MAX_MOBS_NEAR_SPAWNER;
    public static final ForgeConfigSpec.IntValue SPAWNER_AREA_BUDGET;

    public static final ForgeConfigSpec.BooleanValue ATTACK_VILLAGERS_AND_RECRUITS;
    public static final ForgeConfigSpec.BooleanValue FIGHT_MONSTERS;
    public static final ForgeConfigSpec.BooleanValue RECRUITS_ATTACK_FACTIONS;
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> EXTRA_ENEMY_NAMESPACES;
    public static final ForgeConfigSpec.BooleanValue MONSTERS_ATTACK_FACTIONS;
    public static final ForgeConfigSpec.DoubleValue BOSS_HEALTH_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue BOSS_LEASH_RADIUS;
    public static final ForgeConfigSpec.IntValue BOSS_MERGE_RADIUS;

    public static final ForgeConfigSpec.IntValue RAID_INTERVAL_MIN_MINUTES;
    public static final ForgeConfigSpec.IntValue RAID_INTERVAL_MAX_MINUTES;
    public static final ForgeConfigSpec.IntValue RAID_MIN_SOLDIERS;
    public static final ForgeConfigSpec.IntValue RAID_MAX_SOLDIERS;
    public static final ForgeConfigSpec.IntValue RAID_DURATION_MINUTES;
    public static final ForgeConfigSpec.IntValue TARGET_RADIUS;
    public static final ForgeConfigSpec.IntValue COALITION_RADIUS;
    public static final ForgeConfigSpec.DoubleValue TRACK_RAID_CHANCE;
    public static final ForgeConfigSpec.IntValue TRACK_BLOCKS_PER_RAIDER;
    public static final ForgeConfigSpec.DoubleValue RAID_BOSS_HEALTH_MULTIPLIER;
    public static final ForgeConfigSpec.BooleanValue ANNOUNCE_RAIDS;
    public static final ForgeConfigSpec.BooleanValue HUNT_RAILS;
    public static final ForgeConfigSpec.IntValue RAIL_SENSE_RADIUS;
    public static final ForgeConfigSpec.IntValue WANDER_TRACK_BLOCKS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("spawning");
        NATURAL_SPAWN_CHANCE = b.comment("Chance (0..1) that a natural spawn of a faction mob (Castaway/Masked/Burrower) is allowed. 1 = unchanged, 0.2 = five times rarer.")
                .defineInRange("naturalSpawnChance", 0.25D, 0.0D, 1.0D);
        SPAWNER_SPAWN_CHANCE = b.comment("Chance (0..1) that a spawner attempt in a structure is allowed.")
                .defineInRange("spawnerSpawnChance", 0.5D, 0.0D, 1.0D);
        MAX_MOBS_NEAR_SPAWNER = b.comment("A spawner does not spawn while this many faction mobs are already within 40 blocks (cuts lag).")
                .defineInRange("maxMobsNearSpawner", 14, 1, 200);
        SPAWNER_AREA_BUDGET = b.comment("Total number of mobs all spawners together may ever create in one 64x64 area (0 = unlimited). Killed mobs are not replaced once this is used up.")
                .defineInRange("spawnerAreaBudget", 40, 0, 10000);
        b.pop();

        b.push("hostility");
        ATTACK_VILLAGERS_AND_RECRUITS = b.comment("Faction mobs attack villagers and Recruits units (including Recruits Living Villages settlements).")
                .define("attackVillagersAndRecruits", true);
        EXTRA_ENEMY_NAMESPACES = b.comment("Mods whose mobs are ALWAYS fought by barbarians (and fight them back), quickly and actively. Add mod ids here.")
                .defineList("extraEnemyNamespaces", java.util.List.of("flesh_that_hates", "fromanotherworld"), o -> o instanceof String);
        RECRUITS_ATTACK_FACTIONS = b.comment("Recruits (village and hired, except trading nobles) actively attack barbarians they see.")
                .define("recruitsAttackFactions", true);
        FIGHT_MONSTERS = b.comment("Faction mobs attack monsters that are hostile to the player (zombies, skeletons...).")
                .define("fightMonsters", true);
        MONSTERS_ATTACK_FACTIONS = b.comment("Ordinary hostile mobs attack faction mobs too.")
                .define("monstersAttackFactions", true);
        b.pop();

        b.push("boss");
        BOSS_HEALTH_MULTIPLIER = b.comment("Max health multiplier of a structure boss.")
                .defineInRange("bossHealthMultiplier", 3.0D, 1.0D, 50.0D);
        BOSS_MERGE_RADIUS = b.comment("Structures closer than this many blocks to an existing hideout share its boss instead of getting another one.")
                .defineInRange("bossMergeRadius", 80, 0, 400);
        BOSS_LEASH_RADIUS = b.comment("The boss stays within this many blocks of his hideout.")
                .defineInRange("bossLeashRadius", 24, 4, 128);
        b.pop();

        b.push("raids");
        RAID_INTERVAL_MIN_MINUTES = b.comment("Minimum minutes between two raids sent by the same boss.")
                .defineInRange("intervalMinMinutes", 12, 1, 600);
        RAID_INTERVAL_MAX_MINUTES = b.comment("Maximum minutes between two raids sent by the same boss.")
                .defineInRange("intervalMaxMinutes", 30, 1, 600);
        RAID_MIN_SOLDIERS = b.comment("Minimum soldiers in a raiding party (raid is cancelled if fewer are alive near the boss).")
                .defineInRange("minSoldiers", 5, 1, 50);
        RAID_MAX_SOLDIERS = b.comment("Maximum soldiers in a raiding party.")
                .defineInRange("maxSoldiers", 10, 1, 50);
        RAID_DURATION_MINUTES = b.comment("A raid ends (soldiers return home) after this many minutes.")
                .defineInRange("durationMinutes", 8, 1, 60);
        TARGET_RADIUS = b.comment("Raids only target settlements/rails that are loaded within this many blocks of the boss.")
                .defineInRange("targetRadius", 96, 16, 256);
        COALITION_RADIUS = b.comment("Other hideouts whose home is within this many blocks of the target join the raid too.")
                .defineInRange("coalitionRadius", 160, 0, 512);
        TRACK_RAID_CHANCE = b.comment("Chance (0..1) that a raid goes for Create tracks instead of a settlement (if tracks are near).")
                .defineInRange("trackRaidChance", 0.3D, 0.0D, 1.0D);
        TRACK_BLOCKS_PER_RAIDER = b.comment("Max Create track blocks one raider destroys per raid.")
                .defineInRange("trackBlocksPerRaider", 6, 1, 64);
        RAID_BOSS_HEALTH_MULTIPLIER = b.comment("Max health multiplier of the 'Raid Boss' leading a raiding party (drops the hideout book).")
                .defineInRange("raidBossHealthMultiplier", 2.0D, 1.0D, 50.0D);
        ANNOUNCE_RAIDS = b.comment("Warn players within 128 blocks of the target.")
                .define("announceRaids", true);
        b.pop();

        b.push("rails");
        HUNT_RAILS = b.comment("Any barbarian (also without a raid, also wild ones) walks to Create tracks it notices nearby and breaks them.")
                .define("huntRails", true);
        RAIL_SENSE_RADIUS = b.comment("How far (blocks) a barbarian notices Create tracks.")
                .defineInRange("railSenseRadius", 32, 4, 96);
        WANDER_TRACK_BLOCKS = b.comment("Max track blocks one barbarian breaks outside raids during its life.")
                .defineInRange("wanderTrackBlocks", 8, 1, 256);
        b.pop();

        SPEC = b.build();
    }
}
