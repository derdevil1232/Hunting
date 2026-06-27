package com.spawner.hunt.modules;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SpawnerHunt extends Module {
    private static final int PICKUP_TIMEOUT_TICKS = 200;
    private static final double PICKUP_SEARCH_RANGE = 8.0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAutomation = settings.createGroup("Automation");
    private final SettingGroup sgStealth = settings.createGroup("Stealth");
    private final SettingGroup sgRender = settings.createGroup("Render");

    public enum ExplorationMode {
        None,
        RTP,
        TargetCoordinates
    }

    private final Setting<List<String>> mobFilter = sgGeneral.add(new StringListSetting.Builder()
        .name("mob-filter")
        .description("Only targets spawners whose mob id exactly matches this value. Must be in format minecraft:mob-id")
        .defaultValue("minecraft:skeleton")
        .build()
    );

    private final Setting<Boolean> useBaritone = sgAutomation.add(new BoolSetting.Builder()
        .name("use-baritone")
        .description("Uses Meteor's path manager (Baritone when available) to route to the nearest matching spawner.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreY = sgAutomation.add(new BoolSetting.Builder()
        .name("ignore-y")
        .description("When enabled, pathing only targets X/Z and ignores Y.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> dynamicReroute = sgAutomation.add(new BoolSetting.Builder()
        .name("dynamic-reroute")
        .description("Automatically reroutes when a newly detected spawner is meaningfully closer.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> rerouteAdvantage = sgAutomation.add(new DoubleSetting.Builder()
        .name("reroute-advantage")
        .description("How many blocks closer a new spawner must be before rerouting.")
        .defaultValue(5.0)
        .min(0)
        .sliderMax(32)
        .build()
    );

    private final Setting<Integer> repathDelay = sgAutomation.add(new IntSetting.Builder()
        .name("repath-delay")
        .description("Ticks between path refreshes while traveling.")
        .defaultValue(20)
        .min(1)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Boolean> autoMine = sgAutomation.add(new BoolSetting.Builder()
        .name("auto-mine")
        .description("Automatically starts mining the target spawner when in range.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> requireSilkTouch = sgAutomation.add(new BoolSetting.Builder()
        .name("require-silk-touch")
        .description("Only mines using a Silk Touch pickaxe unless disabled.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> verifySpawnerPickup = sgAutomation.add(new BoolSetting.Builder()
        .name("verify-spawner-pickup")
        .description("Verifies that mined spawners were picked up and attempts to collect dropped spawner items if needed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> mineRange = sgAutomation.add(new DoubleSetting.Builder()
        .name("mine-range")
        .description("Distance in blocks at which the module starts mining the current target.")
        .defaultValue(1.5)
        .min(1)
        .sliderMax(6)
        .build()
    );

    private final Setting<ExplorationMode> explorationMode = sgAutomation.add(new EnumSetting.Builder<ExplorationMode>()
        .name("exploration-mode")
        .description("What to do when no matching spawners are currently detected.")
        .defaultValue(ExplorationMode.None)
        .build()
    );

    private final Setting<Integer> rtpChestSlot = sgAutomation.add(new IntSetting.Builder()
        .name("rtp-chest-slot")
        .description("The slot index of the chest in the RTP GUI to click. 0-indexed.")
        .defaultValue(11)
        .min(0)
        .sliderMax(26)
        .visible(() -> explorationMode.get() == ExplorationMode.RTP)
        .build()
    );

    private final Setting<Integer> targetX = sgAutomation.add(new IntSetting.Builder()
        .name("target-x")
        .description("The X coordinate to travel towards when searching.")
        .defaultValue(0)
        .sliderRange(-30000000, 30000000)
        .visible(() -> explorationMode.get() == ExplorationMode.TargetCoordinates)
        .build()
    );

    private final Setting<Integer> targetZ = sgAutomation.add(new IntSetting.Builder()
        .name("target-z")
        .description("The Z coordinate to travel towards when searching.")
        .defaultValue(0)
        .sliderRange(-30000000, 30000000)
        .visible(() -> explorationMode.get() == ExplorationMode.TargetCoordinates)
        .build()
    );


    private final Setting<Boolean> stayBelowMaxY = sgStealth.add(new BoolSetting.Builder()
        .name("stay-below-max-y")
        .description("Returns to below a specified Y level after mining a spawner above it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxYLevel = sgStealth.add(new IntSetting.Builder()
        .name("max-y-level")
        .description("The maximum Y level to stay below.")
        .defaultValue(32)
        .sliderRange(-64, 319)
        .visible(stayBelowMaxY::get)
        .build()
    );

    private final Setting<Boolean> digDownBeforeRtp = sgStealth.add(new BoolSetting.Builder()
        .name("dig-down-before-rtp")
        .description("Digs down below the max Y level and waits 5 seconds before RTPing if no spawners are found.")
        .defaultValue(true)
        .visible(() -> explorationMode.get() == ExplorationMode.RTP && stayBelowMaxY.get())
        .build()
    );

    private final Setting<Boolean> DungeonPacketmethod = sgStealth.add(new BoolSetting.Builder()
        .name("dungeon-packet-method")
        .description("Uses an anticheat bypass method that prevents anti esp plugins on servers.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draws a tracer from your eye position to each matching spawner.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> box = sgRender.add(new BoolSetting.Builder()
        .name("box")
        .description("Draws a box around each matching spawner.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Tracer color.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> boxColor = sgRender.add(new ColorSetting.Builder()
        .name("box-color")
        .description("Box color.")
        .defaultValue(new SettingColor(255, 80, 80, 75))
        .build()
    );

    private final List<BlockPos> matchingSpawners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<BlockPos, String> fallbackEntityIdCache = new HashMap<>();
    private final Set<BlockPos> packetedDungeons = new HashSet<>(); // NEW: Prevents packet spam

    private BlockPos currentTarget;
    private BlockPos explorationTarget;
    private BlockPos pendingPickupTarget;
    private boolean pathOwnedByModule;
    private boolean warnedBaritoneUnavailable;
    private int expectedSpawnerItemCount;
    private int pickupTicks;
    private int pickupPathRefreshTicks;
    private int ticksSincePathRefresh;
    private int silkWarningCooldown;
    private int spawnerScanCooldown;
    private int waitingForTeleportTicks;


    private boolean returningBelowMaxY;
    private boolean diggingDownForRtp;
    private int ticksBelowMaxY;


    public SpawnerHunt() {
        super(Categories.Misc, "SpawnerHunt", "Routes to and mines mob spawners filtered by mob type.");
    }

    @Override
    public void onDeactivate() {
        matchingSpawners.clear();
        fallbackEntityIdCache.clear();
        packetedDungeons.clear();
        currentTarget = null;
        clearExploration();
        clearPickupVerification();
        warnedBaritoneUnavailable = false;
        ticksSincePathRefresh = 0;
        silkWarningCooldown = 0;
        stopOwnedPathing();
        firstStuckCheckPos = null;
        stuckTimer = 0;
        waitingForConfirmation = false;
        playerIsStuck = false;
        waitingForTeleport = false;
        waitingForRtpGui = false;
        rtpGuiWaitTicks = 0;
        rtpCooldown = 0;
        rtpStartPos = null;
        recoveryMineRangeActive = false;
        unreachableSpawners.clear();
        recoveryAttemptsForTarget = 0;
        spawnerScanCooldown = 0;
        waitingForTeleportTicks = 0;

        returningBelowMaxY = false;
        diggingDownForRtp = false;
        ticksBelowMaxY = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null || mc.player == null) {
            matchingSpawners.clear();
            fallbackEntityIdCache.clear();
            currentTarget = null;
            clearExploration();
            clearPickupVerification();
            stopOwnedPathing();
            return;
        }

        if (DungeonPacketmethod.get() && mc.player.tickCount % 20 == 0) {
            ScanDungeonAttempts();
        }

        if (rtpCooldown > 0) rtpCooldown--;

        if (waitingForRtpGui) {
            handleRtpGui();
        }

        if (PathManagers.get().isPathing() && pathOwnedByModule) {
            handleStuckDetection();
        } else {
            resetStuckDetection();
        }

        if (playerIsStuck) {
            doRecovery();
        }

        spawnerScanCooldown++;

        if (spawnerScanCooldown >= 10) {
            spawnerScanCooldown = 0;
            updateMatchingSpawners();
        }

        if (waitingForTeleport && rtpStartPos != null) {
            waitingForTeleportTicks++;
            double dist = mc.player.position().distanceTo(rtpStartPos);

            if (dist > 50 || waitingForTeleportTicks > 400) {
                waitingForTeleport = false;
                waitingForTeleportTicks = 0;
                unreachableSpawners.clear();
                info("RTP completed.");
            }
        }

        if (silkWarningCooldown > 0) silkWarningCooldown--;

        if (!verifySpawnerPickup.get() && pendingPickupTarget != null) {
            clearPickupVerification();
            stopOwnedPathing();
            checkAndSetReturningBelowMaxY();
        }

        if (verifySpawnerPickup.get() && handlePickupVerification()) return;

        if (!useBaritone.get()) {
            currentTarget = null;
            stopOwnedPathing();
            return;
        }

        if (!BaritoneUtils.IS_AVAILABLE) {
            currentTarget = null;
            stopOwnedPathing();

            if (!warnedBaritoneUnavailable) {
                MeteorClient.LOG.warn("[SpawnerHunt] Baritone path manager is not available.");
                warnedBaritoneUnavailable = true;
            }

            return;
        }

        warnedBaritoneUnavailable = false;

        if (matchingSpawners.isEmpty() || findNearestSpawner() == null) {
            currentTarget = null;

            if (returningBelowMaxY) {
                handleReturningBelowMaxY();
                return;
            }

            switch (explorationMode.get()) {
                case None:
                    clearExploration();
                    stopOwnedPathing();
                    break;
                case RTP:
                    clearExploration();
                    if (waitingForRtpGui || waitingForTeleport) {
                        stopOwnedPathing();
                        break;
                    }
                    if (stayBelowMaxY.get() && digDownBeforeRtp.get()) {
                        handleRtpDigDown();
                    }else {
                        stopOwnedPathing();
                        rtpPlayer();
                    }
                    break;
                case TargetCoordinates:
                    handleTargetExploration();
                    break;
            }

            return;
        }

        returningBelowMaxY = false;
        diggingDownForRtp = false;
        ticksBelowMaxY = 0;

        clearExploration();

        BlockPos nearest = findNearestSpawner();

        if (currentTarget == null
            || !matchingSpawners.contains(currentTarget)
            || !mc.level.getWorldBorder().isWithinBounds(currentTarget)) {
            setCurrentTarget(nearest);
            pathToCurrentTarget();
        } else if (dynamicReroute.get() && shouldReroute(nearest)) {
            setCurrentTarget(nearest);
            pathToCurrentTarget();
        } else if (!isWithinMineRange(currentTarget)) {
            ticksSincePathRefresh++;
            if (!PathManagers.get().isPathing() || ticksSincePathRefresh >= repathDelay.get()) {
                pathToCurrentTarget();
            }
        }

        if (currentTarget != null && isWithinMineRange(currentTarget)) {
            stopOwnedPathing();

            if (autoMine.get()) {
                int beforeMineSpawnerCount = verifySpawnerPickup.get() ? countSpawnerItemsInInventory() : -1;
                mineTargetSpawner(currentTarget);

                if (!mc.level.getBlockState(currentTarget).is(Blocks.SPAWNER)) {
                    if (verifySpawnerPickup.get()) {
                        beginPickupVerification(currentTarget, beforeMineSpawnerCount);
                    } else {
                        checkAndSetReturningBelowMaxY();
                    }
                    currentTarget = null;
                }
            }
        }
    }

    private boolean handlePickupVerification() {
        if (pendingPickupTarget == null) return false;

        if (mc.level == null || mc.player == null) {
            clearPickupVerification();
            stopOwnedPathing();
            return false;
        }

        if (countSpawnerItemsInInventory() > expectedSpawnerItemCount) {
            clearPickupVerification();
            stopOwnedPathing();
            checkAndSetReturningBelowMaxY();
            return false;
        }

        pickupTicks++;

        if (pickupTicks >= PICKUP_TIMEOUT_TICKS) {
            MeteorClient.LOG.warn("[SpawnerHunt] Timed out trying to confirm pickup for mined spawner at {}.", pendingPickupTarget.toShortString());
            clearPickupVerification();
            stopOwnedPathing();
            checkAndSetReturningBelowMaxY();
            return false;
        }

        ItemEntity drop = findNearestSpawnerDrop();
        if (drop == null) {
            return true;
        }

        double dropDistSq = mc.player.distanceToSqr(drop.getX(), drop.getY(), drop.getZ());
        if (dropDistSq <= 4.0) {
            return true;
        }

        if (BaritoneUtils.IS_AVAILABLE) {
            pickupPathRefreshTicks++;

            if (!PathManagers.get().isPathing() || pickupPathRefreshTicks >= 5) {
                PathManagers.get().moveTo(drop.blockPosition(), false);
                pathOwnedByModule = true;
                pickupPathRefreshTicks = 0;
            }
        }

        return true;
    }

    private void checkAndSetReturningBelowMaxY() {
        if (stayBelowMaxY.get() && mc.player != null && mc.player.getBlockY() > maxYLevel.get()) {
            returningBelowMaxY = true;
        }
    }

    private void handleReturningBelowMaxY() {
        if (mc.player == null) return;

        if (mc.player.getBlockY() <= maxYLevel.get()) {
            returningBelowMaxY = false;
            stopOwnedPathing();
            return;
        }

        if (!PathManagers.get().isPathing() || !pathOwnedByModule) {
            BlockPos targetPos = new BlockPos(mc.player.getBlockX(), maxYLevel.get(), mc.player.getBlockZ());
            PathManagers.get().moveTo(targetPos, false);
            pathOwnedByModule = true;
        }
    }

    private final Setting<Integer> worldSeed = sgStealth.add(new IntSetting.Builder()
        .name("world-seed")
        .description("The known world seed used to calculate dungeon coordinates.")
        .defaultValue(0)
        .sliderRange(-30000000, 30000000)
        .build()
    );

    private void ScanDungeonAttempts() {
        if (mc.level == null || mc.player == null) return;

        var registry = mc.level.registryAccess().lookup(Registries.PLACED_FEATURE).orElse(null);
        if (registry == null) return;
        ChatUtils.Info("Registry passed");

        var keys = registry.listElementIds().toList();
        var monsterRoomKey = keys.stream()
            .filter(k -> k.toString().contains("monster_room"))
            .findFirst().orElse(null);
        if (monsterRoomKey == null) return;
        ChatUtils.Info("Monster Room found");
        int featureIdx = keys.indexOf(monsterRoomKey);
        if (featureIdx == -1) return;
        ChatUtils.Info("Feature Index found");
        int playerChunkX = mc.player.getBlockX() >> 4;
        int playerChunkZ = mc.player.getBlockZ() >> 4;
        int chunkRadius = 3;

        long seed = worldSeed.get();
        if (seed == 0) return; // Need a seed to predict
        ChatUtils.Info("Seed found");
        // Initialize Minecraft's native ChunkRandom with the modern Xoroshiro128++ engine
        net.minecraft.world.level.levelgen.XoroshiroRandomSource xoroshiroRandom = new net.minecraft.world.level.levelgen.XoroshiroRandomSource(0L);
        net.minecraft.world.level.levelgen.WorldgenRandom random = new net.minecraft.world.level.levelgen.WorldgenRandom(xoroshiroRandom);

        for (int chunkX = playerChunkX - chunkRadius; chunkX <= playerChunkX + chunkRadius; chunkX++) {
            for (int chunkZ = playerChunkZ - chunkRadius; chunkZ <= playerChunkZ + chunkRadius; chunkZ++) {

                if (!mc.level.hasChunk(chunkX, chunkZ)) continue;

                // 1. Initialize Modern Feature Seed
                random.setFeatureSeed(seed, featureIdx, net.minecraft.world.level.levelgen.GenerationStep.Decoration.UNDERGROUND_STRUCTURES.ordinal());

                // 2. Loop 8 Attempts
                // 2. Loop 8 Attempts
                for (int attempt = 0; attempt < 8; attempt++) {
                    // EXACT PRNG Call Order from wasm decompilation
                    int localX = random.nextInt(16);
                    int y = random.nextInt(114) - 64; // nextInt(114) - 64 covers Y=-64 to Y=49 (1.21 height bounds)
                    int localZ = random.nextInt(16);
                    int sizeX = random.nextInt(2) + 2;
                    int sizeZ = random.nextInt(2) + 2;

                    int absoluteX = (chunkX << 4) + localX;
                    int absoluteZ = (chunkZ << 4) + localZ;

                    BlockPos attemptPos = new BlockPos(absoluteX, y, absoluteZ);

                    // Check if we have already spoofed our location to this specific attempt
                    if (!packetedDungeons.contains(attemptPos)) {

                        // --- THE DISTANCE GATE (Restored) ---
                        // Calculate the squared distance from the player to the attempt
                        double distSq = mc.player.distanceToSqr(absoluteX + 0.5, y + 1.5, absoluteZ + 0.5);
                        double safeDistanceSq = 32.0 * 32.0; // 32 block trigger radius

                        // Only send the packet if the player is physically close enough
                        if (distSq <= safeDistanceSq) {
                            packetedDungeons.add(attemptPos);
                            SendDungeonPacket(absoluteX + 0.5, y + 1.5, absoluteZ + 0.5);
                        }
                    }
                }
            }
        }
    }

    private void SendDungeonPacket(double x, double y, double z) {
        if (mc.player == null) return;
        ServerboundMovePlayerPacket packet = new ServerboundMovePlayerPacket.Pos(x, y, z, mc.player.onGround(), true);
        mc.player.connection.send(packet);
    }



    private void handleRtpDigDown() {
        if (mc.player == null) return;

        if (mc.player.getBlockY() > maxYLevel.get()) {
            diggingDownForRtp = true;
            ticksBelowMaxY = 0;

            if (!PathManagers.get().isPathing() || !pathOwnedByModule) {
                BlockPos targetPos = new BlockPos(mc.player.getBlockX(), maxYLevel.get(), mc.player.getBlockZ());
                PathManagers.get().moveTo(targetPos, false);
                pathOwnedByModule = true;
            }
        } else {
            if (!diggingDownForRtp) {
                diggingDownForRtp = true;
                ticksBelowMaxY = 0;
            }

            stopOwnedPathing();
            ticksBelowMaxY++;

            if (ticksBelowMaxY >= 100) {
                if (rtpCooldown <= 0) {
                    diggingDownForRtp = false;
                    ticksBelowMaxY = 0;
                    rtpPlayer();
                }
            }
        }
    }

    private void handleTargetExploration() {
        if (mc.player == null || !BaritoneUtils.IS_AVAILABLE) return;

        int x = targetX.get();
        int z = targetZ.get();

        boolean alreadyTargeting = explorationTarget != null
            && explorationTarget.getX() == x
            && explorationTarget.getZ() == z;

        if (!alreadyTargeting || !PathManagers.get().isPathing()) {
            explorationTarget = new BlockPos(x, mc.player.getBlockY(), z);
            PathManagers.get().moveTo(explorationTarget, true);
            pathOwnedByModule = true;
        }
    }

    private static final int STUCK_CHECK_INTERVAL = 200;
    private static final int STUCK_CONFIRM_INTERVAL = 100;

    private BlockPos firstStuckCheckPos;
    private int stuckTimer;
    private boolean recoveryMineRangeActive;
    private boolean waitingForConfirmation;
    private boolean playerIsStuck;

    private void handleStuckDetection() {
        if (mc.player == null) return;

        BlockPos currentPos = mc.player.blockPosition();

        stuckTimer++;

        if (!waitingForConfirmation) {
            if (stuckTimer >= STUCK_CHECK_INTERVAL) {
                if (firstStuckCheckPos == null) {
                    firstStuckCheckPos = currentPos.immutable();
                    stuckTimer = 0;
                } else {
                    if (currentPos.equals(firstStuckCheckPos)) {
                        waitingForConfirmation = true;
                        stuckTimer = 0;
                    } else {
                        firstStuckCheckPos = currentPos.immutable();
                        stuckTimer = 0;
                        disableRecovery();
                    }
                }
            }
        } else {
            if (stuckTimer >= STUCK_CONFIRM_INTERVAL) {
                if (currentPos.equals(firstStuckCheckPos)) {
                    playerIsStuck = true;
                    MeteorClient.LOG.info("[SpawnerHunt] Player is stuck.");
                }

                waitingForConfirmation = false;
                stuckTimer = 0;
                firstStuckCheckPos = currentPos.immutable();
            }
        }
    }

    private final Set<BlockPos> unreachableSpawners = new HashSet<>();
    private int recoveryAttemptsForTarget;

    private void doRecovery() {
        if (recoveryMineRangeActive) return;
        if (currentTarget == null) {
            playerIsStuck = false;
            return;
        }

        recoveryAttemptsForTarget++;

        if (recoveryAttemptsForTarget >= 3 && currentTarget != null) {
            MeteorClient.LOG.warn("[SpawnerHunt] Giving up on spawner at {} after repeated stuck recovery.",
                currentTarget.toShortString());
            unreachableSpawners.add(currentTarget);
            currentTarget = null;
            playerIsStuck = false;
            stopOwnedPathing();
            recoveryAttemptsForTarget = 0;
            return;
        }

        recoveryMineRangeActive = true;
        playerIsStuck = false;
        MeteorClient.LOG.info("[SpawnerHunt] Recovery activated, forcing repath.");
        if (BaritoneUtils.IS_AVAILABLE) {
            PathManagers.get().stop();
            pathOwnedByModule = false;
        }
    }

    private void setCurrentTarget(BlockPos newTarget) {
        if (newTarget != null && !newTarget.equals(currentTarget)) {
            recoveryAttemptsForTarget = 0;
        }
        currentTarget = newTarget;
    }

    private void disableRecovery() {
        if (!recoveryMineRangeActive) return;

        recoveryMineRangeActive = false;
        playerIsStuck = false;

        MeteorClient.LOG.info("[SpawnerHunt] Recovery ended. Mine range restored.");
    }

    private void resetStuckDetection() {
        firstStuckCheckPos = null;
        stuckTimer = 0;
        waitingForConfirmation = false;
        playerIsStuck = false;

        disableRecovery();
    }

    private Vec3 rtpStartPos;
    private boolean waitingForTeleport;
    private boolean waitingForRtpGui;
    private int rtpGuiWaitTicks;
    private int rtpCooldown;

    private void handleRtpGui() {
        if (mc.player == null) return;
        if (mc.player.containerMenu == null) return;

        if (mc.player.containerMenu == mc.player.inventoryMenu) return;
        if (mc.player.containerMenu.slots.size() <= 27) return;

        rtpGuiWaitTicks++;
        int chestSlot = rtpChestSlot.get();

        if (rtpGuiWaitTicks < 10) return;

        InvUtils.click().slotId(chestSlot);

        waitingForRtpGui = false;
        waitingForTeleport = true;
        rtpGuiWaitTicks = 0;
    }

    private void rtpPlayer() {
        if (rtpCooldown > 0) return;
        if (waitingForRtpGui) return;
        if (waitingForTeleport) return;
        if (mc.player == null) return;

        rtpStartPos = mc.player.position();

        ChatUtils.sendPlayerMsg("/rtp");

        waitingForRtpGui = true;
        rtpCooldown = 250;
    }

    private void clearExploration() {
        explorationTarget = null;
    }

    private void beginPickupVerification(BlockPos target, int countBeforeMine) {
        pendingPickupTarget = target.immutable();
        expectedSpawnerItemCount = Math.max(0, countBeforeMine);
        pickupTicks = 0;
        pickupPathRefreshTicks = 0;
    }

    private void clearPickupVerification() {
        pendingPickupTarget = null;
        expectedSpawnerItemCount = 0;
        pickupTicks = 0;
        pickupPathRefreshTicks = 0;
    }

    private int countSpawnerItemsInInventory() {
        if (mc.player == null) return 0;

        int count = 0;

        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (isSpawnerItem(stack)) count += stack.getCount();
        }

        return count;
    }

    private ItemEntity findNearestSpawnerDrop() {
        if (mc.level == null || mc.player == null) return null;

        AABB searchBox = new AABB(mc.player.blockPosition()).inflate(PICKUP_SEARCH_RANGE);

        List<ItemEntity> drops = mc.level.getEntitiesOfClass(
            ItemEntity.class, searchBox, item -> isSpawnerItem(item.getItem())
        );

        ItemEntity nearest = null;
        double bestDistSq = Double.MAX_VALUE;

        for (ItemEntity drop : drops) {
            double distSq = mc.player.distanceToSqr(drop.getX(), drop.getY(), drop.getZ());
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearest = drop;
            }
        }

        return nearest;
    }

    private boolean isSpawnerItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem() == Blocks.SPAWNER.asItem();
    }

    private void updateMatchingSpawners() {
        List<String> filters = mobFilter.get();
        matchingSpawners.clear();

        if (filters.isEmpty()) return;

        Set<BlockPos> seenSpawners = new HashSet<>();

        for (BlockEntity blockEntity : Utils.blockEntities()) {
            if (!(blockEntity instanceof SpawnerBlockEntity spawner)) continue;

            BlockPos pos = spawner.getBlockPos().immutable();
            seenSpawners.add(pos);

            String entityId = resolveEntityId(spawner, pos);

            if (entityId != null && filters.contains(entityId)) {
                matchingSpawners.add(pos);
            }
        }

        fallbackEntityIdCache.keySet().removeIf(pos -> !seenSpawners.contains(pos));
    }

    private BlockPos findNearestSpawner() {
        if (mc.player == null || mc.level == null || matchingSpawners.isEmpty()) return null;

        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (BlockPos pos : matchingSpawners) {
            if (unreachableSpawners.contains(pos)) continue;
            if (!mc.level.getWorldBorder().isWithinBounds(pos)) continue;
            double distSq = squaredDistanceTo(pos);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = pos;
            }
        }

        return nearest;
    }

    private boolean shouldReroute(BlockPos candidate) {
        if (candidate == null || currentTarget == null || candidate.equals(currentTarget)) return false;

        double currentDistSq = squaredDistanceTo(currentTarget);
        double candidateDistSq = squaredDistanceTo(candidate);
        double advantageSq = rerouteAdvantage.get() * rerouteAdvantage.get();

        return candidateDistSq + advantageSq < currentDistSq;
    }

    private double squaredDistanceTo(BlockPos pos) {
        if (mc.player == null) return Double.MAX_VALUE;

        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;

        return mc.player.distanceToSqr(x, y, z);
    }

    private boolean isWithinMineRange(BlockPos pos) {
        double range = recoveryMineRangeActive ? 4.0 : mineRange.get();
        return squaredDistanceTo(pos) <= range * range;
    }

    private void pathToCurrentTarget() {
        if (currentTarget == null || !BaritoneUtils.IS_AVAILABLE) return;

        PathManagers.get().moveTo(currentTarget, ignoreY.get());
        pathOwnedByModule = true;
        ticksSincePathRefresh = 0;
    }

    private void stopOwnedPathing() {
        if (!pathOwnedByModule) return;
        if (BaritoneUtils.IS_AVAILABLE) PathManagers.get().stop();
        pathOwnedByModule = false;
    }

    private void mineTargetSpawner(BlockPos pos) {
        if (mc.level == null || !mc.level.getBlockState(pos).is(Blocks.SPAWNER)) return;

        FindItemResult tool = findMiningTool();

        if (requireSilkTouch.get() && !tool.found()) {
            if (silkWarningCooldown == 0) {
                MeteorClient.LOG.info("[SpawnerHunt] Reached spawner at {} but no Silk Touch pickaxe is in hotbar.", pos.toShortString());
                silkWarningCooldown = 40;
            }
            return;
        }

        if (tool.found() && !tool.isMainHand()) {
            InvUtils.swap(tool.slot(), false);
        }

        BlockUtils.breakBlock(pos, true);
    }

    private FindItemResult findMiningTool() {
        if (requireSilkTouch.get()) {
            return InvUtils.findInHotbar(this::isSilkTouchPickaxe);
        }

        return InvUtils.findInHotbar(stack -> stack.is(ItemTags.PICKAXES));
    }

    private boolean isSilkTouchPickaxe(ItemStack stack) {
        if (mc.level == null || !stack.is(ItemTags.PICKAXES)) return false;

        var enchantmentRegistry = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        return EnchantmentHelper.getItemEnchantmentLevel(enchantmentRegistry.getOrThrow(Enchantments.SILK_TOUCH), stack) > 0;
    }

    private String resolveEntityId(SpawnerBlockEntity spawner, BlockPos pos) {
        String fromSpawner = readEntityIdFromSpawner(spawner);
        if (fromSpawner != null) {
            fallbackEntityIdCache.put(pos, fromSpawner);
            return fromSpawner;
        }

        return fallbackEntityIdCache.get(pos);
    }

    private String readEntityIdFromSpawner(SpawnerBlockEntity spawner) {
        try {
            if (spawner.getSpawner().nextSpawnData == null) return null;
            CompoundTag entityTag = spawner.getSpawner().nextSpawnData.getEntityToSpawn();
            if (entityTag == null || !entityTag.contains("id")) return null;
            return entityTag.getString("id").orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @EventHandler
    private void onRender3d(Render3DEvent event) {
        if (mc.level == null || mc.player == null || matchingSpawners.isEmpty() || RenderUtils.center == null) return;

        for (BlockPos pos : matchingSpawners) {
            if (box.get()) {
                event.renderer.box(pos, boxColor.get(), boxColor.get(), ShapeMode.Both, 0);
            }

            if (tracers.get()) {
                double x = pos.getX() + 0.5;
                double y = pos.getY() + 0.5;
                double z = pos.getZ() + 0.5;

                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, x, y, z, tracerColor.get());
            }
        }
    }
}
