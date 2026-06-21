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

    private final List<BlockPos> matchingSpawners = new ArrayList<>();
    private final Map<BlockPos, String> fallbackEntityIdCache = new HashMap<>();

    private BlockPos currentTarget;
    private BlockPos explorationTarget;
    private BlockPos pendingPickupTarget;
    private boolean pathOwnedByModule;
    private boolean warnedBaritoneUnavailable;
    private int expectedSpawnerItemCount;
    private int exploreTicks;
    private int pickupTicks;
    private int pickupPathRefreshTicks;
    private int ticksSincePathRefresh;
    private int silkWarningCooldown;
    private boolean isMoving;

    public SpawnerHunt() {
        super(Categories.Misc, "SpawnerHunt", "Routes to and mines mob spawners filtered by mob type.");
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

        if (PathManagers.get().isPathing()) {
            handleStuckDetection();
        } else {
            resetStuckDetection();
        }

        if (playerIsStuck) {
            doRecovery();
        }

        if (waitingForTeleport && rtpStartPos != null) {
            double dist = mc.player.position().distanceTo(rtpStartPos);

            if (dist > 50) {
                waitingForTeleport = false;
                info("RTP completed.");
            }
        }

        if (silkWarningCooldown > 0) silkWarningCooldown--;

        if (!verifySpawnerPickup.get() && pendingPickupTarget != null) {
            clearPickupVerification();
            stopOwnedPathing();
        }

        if (verifySpawnerPickup.get() && handlePickupVerification()) return;

        updateMatchingSpawners();

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

        if (matchingSpawners.isEmpty()) {
            currentTarget = null;

            switch (explorationMode.get()) {
                case None:
                    clearExploration();
                    stopOwnedPathing();
                    break;
                case RTP:
                    clearExploration();
                    stopOwnedPathing();
                    rtpPlayer();
                    break;
                case TargetCoordinates:
                    handleTargetExploration();
                    break;
            }

            return;
        }

        clearExploration();

        BlockPos nearest = findNearestSpawner();
        if (nearest == null) {
            currentTarget = null;
            stopOwnedPathing();
            return;
        }

        if (currentTarget == null || !matchingSpawners.contains(currentTarget)) {
            currentTarget = nearest;
            pathToCurrentTarget();
        } else if (dynamicReroute.get() && shouldReroute(nearest)) {
            currentTarget = nearest;
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
                    if (verifySpawnerPickup.get()) beginPickupVerification(currentTarget, beforeMineSpawnerCount);
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
            return false;
        }

        pickupTicks++;

        if (pickupTicks >= PICKUP_TIMEOUT_TICKS) {
            MeteorClient.LOG.warn("[SpawnerHunt] Timed out trying to confirm pickup for mined spawner at {}.", pendingPickupTarget.toShortString());
            clearPickupVerification();
            stopOwnedPathing();
            return false;
        }

        ItemEntity drop = findNearestSpawnerDrop();
        if (drop == null) {
            return true;
        }

        if (BaritoneUtils.IS_AVAILABLE) {
            pickupPathRefreshTicks++;

            if (!PathManagers.get().isPathing() || pickupPathRefreshTicks >= repathDelay.get()) {
                PathManagers.get().moveTo(drop.blockPosition(), false);
                pathOwnedByModule = true;
                pickupPathRefreshTicks = 0;
            }
        }

        return true;
    }

    private void handleTargetExploration() {
        if (mc.player == null || !BaritoneUtils.IS_AVAILABLE) return;

        // Create a goal using our target settings. The Y value doesn't matter because we will tell Baritone to ignore it.
        BlockPos goal = new BlockPos(targetX.get(), mc.player.getBlockY(), targetZ.get());

        // Only issue a new Baritone command if we aren't already pathing to this exact target,
        // or if Baritone was stopped (e.g., after mining a spawner).
        if (explorationTarget == null || !explorationTarget.equals(goal) || !PathManagers.get().isPathing()) {
            explorationTarget = goal;

            // The 'true' argument here is critical. It creates a GoalXZ, ignoring elevation.
            PathManagers.get().moveTo(explorationTarget, true);
            pathOwnedByModule = true;
        }
    }


    private static final int STUCK_CHECK_INTERVAL = 200;      // 10 seconds
    private static final int STUCK_CONFIRM_INTERVAL = 100;    // 5 seconds

    private BlockPos firstStuckCheckPos;
    private int stuckTimer;
    private boolean recoveryMineRangeActive;
    private boolean waitingForConfirmation;
    private boolean playerIsStuck;

    private void handleStuckDetection() {
        if (mc.player == null) return;

        BlockPos currentPos = mc.player.blockPosition();

        stuckTimer++;

        // First check after 10 seconds
        if (!waitingForConfirmation) {
            if (stuckTimer >= STUCK_CHECK_INTERVAL) {
                if (firstStuckCheckPos == null) {
                    firstStuckCheckPos = currentPos.immutable();
                } else {
                    if (currentPos.equals(firstStuckCheckPos)) {
                        // Same position after 10 seconds
                        waitingForConfirmation = true;
                        stuckTimer = 0;
                    } else {

                        firstStuckCheckPos = currentPos.immutable();
                        stuckTimer = 0;

                        disableRecovery();
                    }
                }
            }
        }

        // Confirmation check after another 5 seconds
        else {
            if (stuckTimer >= STUCK_CONFIRM_INTERVAL) {
                if (currentPos.equals(firstStuckCheckPos)) {
                    playerIsStuck = true;

                    MeteorClient.LOG.info("[SpawnerHunt] Player is stuck.");

                    doRecovery();
                }

                waitingForConfirmation = false;
                stuckTimer = 0;
                firstStuckCheckPos = currentPos.immutable();
            }
        }
    }


    private void doRecovery() {
        if (recoveryMineRangeActive) return;

        recoveryMineRangeActive = true;

        MeteorClient.LOG.info("[SpawnerHunt] Recovery activated. Mine range temporarily increased to 4 blocks.");
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

    private int chestSlot;

    private void handleRtpGui() {
        if (mc.player == null) return;
        if (mc.player.containerMenu == null) return;

        // 1. Wait until the server actually opens the new GUI
        if (mc.player.containerMenu == mc.player.inventoryMenu) return;

        // 2. Ensure the GUI has enough slots
        if (mc.player.containerMenu.slots.size() <= 27) return;

        // 3. The GUI is open! Increment the tick counter.
        rtpGuiWaitTicks++;

        chestSlot = rtpChestSlot.get();

        // 4. If we haven't waited 10 ticks yet, return and wait for the next tick.
        if (rtpGuiWaitTicks < 10) return;

        // 5. 10 ticks have passed, execute the click!
        InvUtils.click().slotId(chestSlot);

        // 6. Reset the states for the next time you RTP
        waitingForRtpGui = false;
        waitingForTeleport = true;
        rtpGuiWaitTicks = 250;
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
        exploreTicks = 0;
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
        if (mc.level == null || mc.player == null || pendingPickupTarget == null) return null;

        AABB searchBox = new AABB(pendingPickupTarget).inflate(PICKUP_SEARCH_RANGE);
        List<ItemEntity> drops = mc.level.getEntitiesOfClass(ItemEntity.class, searchBox, item -> isSpawnerItem(item.getItem()));

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

            // Check if our list of targeted mobs contains the spawner's entity ID
            if (entityId != null && filters.contains(entityId)) {
                matchingSpawners.add(pos);
            }
        }

        fallbackEntityIdCache.keySet().removeIf(pos -> !seenSpawners.contains(pos));
    }

    private BlockPos findNearestSpawner() {
        if (mc.player == null || matchingSpawners.isEmpty()) return null;

        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (BlockPos pos : matchingSpawners) {
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
