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
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

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
    private final SettingGroup sgPacketCheck = settings.createGroup("Packet Checking");

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

    private final Setting<Boolean> packetCheckEnabled = sgPacketCheck.add(new BoolSetting.Builder()
        .name("packet-check")
        .description("Enables seed-based dungeon prediction and packet probing to find spawners beyond render distance.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> worldSeed = sgPacketCheck.add(new StringSetting.Builder()
        .name("world-seed")
        .description("The world seed used to calculate dungeon positions. Required for packet checking.")
        .defaultValue("")
        .visible(packetCheckEnabled::get)
        .build()
    );

    private final Setting<Integer> packetCheckRadius = sgPacketCheck.add(new IntSetting.Builder()
        .name("packet-check-radius")
        .description("Chunk radius around the player to scan for predicted dungeons.")
        .defaultValue(32)
        .min(1)
        .sliderRange(1, 64)
        .visible(packetCheckEnabled::get)
        .build()
    );

    private final Setting<Integer> packetProbeDelay = sgPacketCheck.add(new IntSetting.Builder()
        .name("probe-delay")
        .description("Ticks between sending position probe packets to avoid anti-cheat detection.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 40)
        .visible(packetCheckEnabled::get)
        .build()
    );

    private final Setting<Boolean> packetCheckNotify = sgPacketCheck.add(new BoolSetting.Builder()
        .name("probe-notify")
        .description("Send chat messages when probing or detecting spawners via packets.")
        .defaultValue(true)
        .visible(packetCheckEnabled::get)
        .build()
    );

    private final List<BlockPos> matchingSpawners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<BlockPos, String> fallbackEntityIdCache = new HashMap<>();

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

    // === Packet Checking State ===
    private long parsedSeed;
    private boolean seedValid;
    private final List<BlockPos> predictedDungeons = new ArrayList<>();
    private final Set<BlockPos> probedPositions = new HashSet<>();
    private final Set<BlockPos> confirmedPacketSpawners = new HashSet<>();
    private final List<BlockPos> probeQueue = new ArrayList<>();
    private int probeTickCounter;
    private int lastPlayerChunkX = Integer.MIN_VALUE;
    private int lastPlayerChunkZ = Integer.MIN_VALUE;
    private boolean packetCheckRecalcNeeded = true;

    // Pre-calculated population seed constants (derived from world seed)
    private long populationSeedA;
    private long populationSeedB;


    public SpawnerHunt() {
        super(Categories.Misc, "SpawnerHunt", "Routes to and mines mob spawners filtered by mob type.");
    }

    @Override
    public void onActivate() {
        initializeSeed();
        packetCheckRecalcNeeded = true;
    }

    @Override
    public void onDeactivate() {
        matchingSpawners.clear();
        fallbackEntityIdCache.clear();
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

        // Packet checking cleanup
        predictedDungeons.clear();
        probedPositions.clear();
        confirmedPacketSpawners.clear();
        probeQueue.clear();
        probeTickCounter = 0;
        seedValid = false;
        lastPlayerChunkX = Integer.MIN_VALUE;
        lastPlayerChunkZ = Integer.MIN_VALUE;
        packetCheckRecalcNeeded = true;
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

        // Packet-based spawner detection
        tickPacketCheck();

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
                    } else {
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
        if (mc.level == null || mc.player == null || RenderUtils.center == null) return;

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

        // Render packet-check confirmed spawners with a distinct color
        if (packetCheckEnabled.get()) {
            for (BlockPos pos : confirmedPacketSpawners) {
                if (!matchingSpawners.contains(pos)) {
                    // Render unfiltered packet-confirmed spawners in yellow
                    if (box.get()) {
                        event.renderer.box(pos,
                            new SettingColor(255, 255, 0, 75),
                            new SettingColor(255, 255, 0, 75),
                            ShapeMode.Both, 0);
                    }

                    if (tracers.get()) {
                        double x = pos.getX() + 0.5;
                        double y = pos.getY() + 0.5;
                        double z = pos.getZ() + 0.5;
                        event.renderer.line(
                            RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                            x, y, z,
                            new SettingColor(255, 255, 0, 255)
                        );
                    }
                }
            }
        }
    }

    // ========================
    // DUNGEON POSITION PREDICTION
    // ========================

    /**
     * Parses and validates the world seed from the setting.
     */
    private void initializeSeed() {
        String seedStr = worldSeed.get().trim();
        if (seedStr.isEmpty()) {
            seedValid = false;
            return;
        }

        try {
            parsedSeed = Long.parseLong(seedStr);
        } catch (NumberFormatException e) {
            // Non-numeric seed: hash it like Minecraft does
            parsedSeed = seedStr.hashCode();
        }

        seedValid = true;

        // Pre-calculate the population seed constants from the world seed
        java.util.Random seedRng = new java.util.Random(parsedSeed);
        populationSeedA = seedRng.nextLong() | 1L;
        populationSeedB = seedRng.nextLong() | 1L;
    }

    /**
     * Calculates the population seed for a given chunk, matching Minecraft's
     * WorldgenRandom.setPopulationSeed() logic.
     */
    private long getPopulationSeed(int chunkX, int chunkZ) {
        long blockX = (long) chunkX * 16;
        long blockZ = (long) chunkZ * 16;
        return (blockX * populationSeedA + blockZ * populationSeedB) ^ parsedSeed;
    }

    /**
     * Calculates the feature seed for a specific feature placement.
     * step = GenerationStep.Decoration ordinal (3 for UNDERGROUND_STRUCTURES)
     * index = feature index within that step's feature list
     */
    private long getFeatureSeed(long populationSeed, int index, int step) {
        return populationSeed + index + 10000L * step;
    }

    /**
     * Predicts dungeon candidate positions for a given chunk using the world seed.
     * Returns a list of BlockPos where dungeons MAY generate (before terrain validation).
     *
     * The algorithm mirrors Minecraft's dungeon placement pipeline:
     *   1. count(10) - 10 attempts for normal dungeons
     *   2. in_square - random X/Z spread within chunk
     *   3. height_range(uniform, 0 to 256) - random Y
     */
    private List<BlockPos> predictDungeonPositions(int chunkX, int chunkZ) {
        List<BlockPos> candidates = new ArrayList<>();

        // UNDERGROUND_STRUCTURES step = 3
        // Feature index within that step varies by biome, but typically 0 or low
        // We try indices 0-2 to cover most biomes
        for (int featureIndex = 0; featureIndex <= 2; featureIndex++) {
            long popSeed = getPopulationSeed(chunkX, chunkZ);
            long featSeed = getFeatureSeed(popSeed, featureIndex, 3);
            java.util.Random rng = new java.util.Random(featSeed);

            // count modifier: 10 attempts for normal dungeons
            int attempts = 10;

            for (int attempt = 0; attempt < attempts; attempt++) {
                // in_square placement modifier: random position within chunk
                int x = chunkX * 16 + rng.nextInt(16);
                int z = chunkZ * 16 + rng.nextInt(16);

                // height_range: uniform between 0 and world height (256 for overworld)
                int y = rng.nextInt(256);

                candidates.add(new BlockPos(x, y, z));

                // Consume the RNG calls that place() would make even on failure
                // halfWidth1 = nextInt(2) + 2
                rng.nextInt(2);
                // halfWidth2 = nextInt(2) + 2
                rng.nextInt(2);
            }
        }

        // Also handle deep dungeons (monster_room_deep) - 4 attempts, negative Y
        for (int featureIndex = 0; featureIndex <= 2; featureIndex++) {
            long popSeed = getPopulationSeed(chunkX, chunkZ);
            // Deep dungeons likely have a different feature index; try offset
            long featSeed = getFeatureSeed(popSeed, featureIndex + 3, 3);
            java.util.Random rng = new java.util.Random(featSeed);

            int attempts = 4;

            for (int attempt = 0; attempt < attempts; attempt++) {
                int x = chunkX * 16 + rng.nextInt(16);
                int z = chunkZ * 16 + rng.nextInt(16);
                int y = -64 + rng.nextInt(64); // -64 to 0 for deep dungeons

                candidates.add(new BlockPos(x, y, z));
                rng.nextInt(2);
                rng.nextInt(2);
            }
        }

        return candidates;
    }

    // ========================
    // PACKET PROBING
    // ========================

    /**
     * Recalculates predicted dungeon positions for chunks within the scan radius.
     */
    private void recalculatePredictedDungeons() {
        if (mc.player == null || !seedValid) return;

        int playerChunkX = mc.player.getBlockX() >> 4;
        int playerChunkZ = mc.player.getBlockZ() >> 4;
        int radius = packetCheckRadius.get();

        predictedDungeons.clear();
        probeQueue.clear();

        for (int cx = playerChunkX - radius; cx <= playerChunkX + radius; cx++) {
            for (int cz = playerChunkZ - radius; cz <= playerChunkZ + radius; cz++) {
                List<BlockPos> candidates = predictDungeonPositions(cx, cz);
                for (BlockPos pos : candidates) {
                    if (!probedPositions.contains(pos) && !confirmedPacketSpawners.contains(pos)) {
                        predictedDungeons.add(pos);
                    }
                }
            }
        }

        // Sort by distance to player - probe closest first
        predictedDungeons.sort((a, b) -> {
            double distA = mc.player.blockPosition().distSqr(a);
            double distB = mc.player.blockPosition().distSqr(b);
            return Double.compare(distA, distB);
        });

        probeQueue.addAll(predictedDungeons);

        lastPlayerChunkX = playerChunkX;
        lastPlayerChunkZ = playerChunkZ;
        packetCheckRecalcNeeded = false;
    }

    /**
     * Sends a position spoof packet to the server to force chunk loading around a target.
     * Uses ServerboundMovePlayerPacket.Pos with x, y, z coordinates.
     */
    private void sendProbePacket(BlockPos target) {
        if (mc.player == null || mc.player.connection == null) return;

        double x = target.getX() + 0.5;
        double y = target.getY() + 0.5;
        double z = target.getZ() + 0.5;

        // Send position packet to force server to load chunks around the target
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            x, y, z, mc.player.onGround(), false
        ));

        // Immediately send real position back to minimize desync
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.onGround(), mc.player.horizontalCollision
        ));
    }

    /**
     * Checks if a spawner exists at the given position by examining loaded block entities.
     */
    private boolean checkForSpawnerAt(BlockPos pos) {
        if (mc.level == null) return false;

        // Check if the chunk containing this position is loaded
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        LevelChunk chunk = mc.level.getChunkSource().getChunkNow(chunkX, chunkZ);

        if (chunk == null) return false;

        // Check if there's a spawner block at the position
        if (mc.level.getBlockState(pos).is(Blocks.SPAWNER)) {
            BlockEntity be = mc.level.getBlockEntity(pos);
            return be instanceof SpawnerBlockEntity;
        }

        return false;
    }

    /**
     * Main tick handler for the packet checking system.
     * Called from onTick.
     */
    private void tickPacketCheck() {
        if (!packetCheckEnabled.get() || mc.player == null || mc.level == null) return;

        // Re-parse seed each time (cheap operation, ensures setting changes are picked up)
        initializeSeed();
        if (!seedValid) return;

        // Check if player has moved to a new chunk - recalculate predictions
        int playerChunkX = mc.player.getBlockX() >> 4;
        int playerChunkZ = mc.player.getBlockZ() >> 4;

        if (playerChunkX != lastPlayerChunkX || playerChunkZ != lastPlayerChunkZ || packetCheckRecalcNeeded) {
            recalculatePredictedDungeons();
        }

        // Check previously probed positions for spawners that may now be loaded
        List<BlockPos> toRemove = new ArrayList<>();
        for (BlockPos probed : probedPositions) {
            if (checkForSpawnerAt(probed)) {
                if (confirmedPacketSpawners.add(probed)) {
                    if (packetCheckNotify.get()) {
                        info("\u00a7a[Packet Check] \u00a7fSpawner confirmed at \u00a7e" +
                            probed.getX() + " " + probed.getY() + " " + probed.getZ());
                    }

                    // Also add to the main matching spawners list if it matches the mob filter
                    BlockEntity be = mc.level.getBlockEntity(probed);
                    if (be instanceof SpawnerBlockEntity spawner) {
                        String entityId = resolveEntityId(spawner, probed);
                        if (entityId != null && mobFilter.get().contains(entityId)) {
                            if (!matchingSpawners.contains(probed)) {
                                matchingSpawners.add(probed);
                            }
                        }
                    }
                }
                toRemove.add(probed);
            }
        }
        probedPositions.removeAll(toRemove);

        // Send probe packets on delay
        probeTickCounter++;
        if (probeTickCounter >= packetProbeDelay.get() && !probeQueue.isEmpty()) {
            probeTickCounter = 0;

            BlockPos target = probeQueue.remove(0);
            probedPositions.add(target);
            sendProbePacket(target);

            if (packetCheckNotify.get()) {
                info("\u00a77[Packet Check] \u00a7fProbing \u00a7e" +
                    target.getX() + " " + target.getY() + " " + target.getZ());
            }
        }
    }
}
