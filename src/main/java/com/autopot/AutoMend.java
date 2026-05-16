package com.autopot;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class AutoMend {

    public enum BalanceMode { EQUAL_PERCENT, EQUAL_RAW }

    private record SlotMove(int fromSlotId, int toSlotId, long createdTick, String reason) {}
    private record ArmorState(int slotId, EquipmentSlot eqSlot, double ratio, int remainingRaw, boolean binding, int score) {}
    private record EmptyArmorSlot(Slot slot, EquipmentSlot eqSlot) {}

    private final Deque<SlotMove> moveQueue = new ArrayDeque<>();
    private final Set<Integer> reservedDestinations = new HashSet<>();
    private final Random random = new Random();

    private boolean enabled = false;
    private KeyBinding toggleKey;

    // settings
    private BalanceMode balanceMode = BalanceMode.EQUAL_PERCENT;
    private double balanceTolerancePercent = 8.0d;
    private int actionDelayTicksSetting = 3;
    private int delayJitterTicks = 4;
    private int postActionCooldownTicks = 10;
    private boolean pauseWhileMoving = true;
    private boolean pauseNearEnemies = false;
    private boolean safeInventoryMode = true;
    private boolean debugMode = false;
    private boolean xpOnlyActivation = false;
    private boolean weightedChestplatePriority = true;
    private int unequipTargetRemainingRaw = 405;
    private int equalizeGapRaw = 8;
    private int reEquipToleranceRaw = 2;
    private int phaseTargetRaw = 400;
    private int lowBottleCountThreshold = 40;
    private int phaseTargetHysteresisRaw = 0;

    private int actionDelayTicks = 0;
    private int swapCooldownTicks = 0;
    private int pendingConfirmTicks = 0;
    private int lastKnownRevision = -1;
    private int cacheArmorRevision = -1;
    private int sameTargetStreak = 0;
    private int lastActionFrom = -1;
    private int lastActionTo = -1;
    private long worldTick = 0;
    private SlotMove inFlightMove = null;
    private boolean inFlightPickedUp = false;
    private boolean openedInventoryForMove = false;
    private long lastDecisionTick = -100;
    private long lastBalanceCalcTick = -100;
    private long lastReverseTick = -200;
    private boolean lastDirectionUnequip = false;
    private Vec3d lastPos = Vec3d.ZERO;
    private List<ArmorState> cachedArmor = List.of();

    private static final int CONFIRM_TIMEOUT = 24;
    private static final int RECALC_INTERVAL = 4;
    private static final int DECISION_INTERVAL = 3;
    private static final int MIN_REVERSE_TICKS = 30;
    private static final double REEQUIP_DIFF_PERCENT = 8.0d;
    private static final boolean FORCE_SIMPLE_HELMET_TEST = false;

    // PlayerInventory slot indexes (Fabric/Yarn 1.21.11):
    // boots=36, leggings=37, chestplate=38, helmet=39
    private final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("autopot-automend.properties");

    public void setToggleKey(KeyBinding toggleKey) {
        this.toggleKey = toggleKey;
    }

    public void onEnable() {
        enabled = true;
        resetRuntimeState();
        debug("enabled");
    }

    public void onDisable() {
        enabled = false;
        resetRuntimeState();
        debug("disabled");
    }

    public void handleToggle(MinecraftClient client) {
        enabled = !enabled;
        if (enabled) onEnable(); else onDisable();
        if (client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.literal("§bAutoMend §r" + (enabled ? "§aEnabled" : "§cDisabled")), true);
        }
    }

    public void tick(MinecraftClient client) {
        worldTick++;
        if (toggleKey != null) {
            while (toggleKey.wasPressed()) handleToggle(client);
        }

        if (!enabled || client.player == null || client.interactionManager == null) return;
        if (client.world == null) { resetRuntimeState(); return; }
        if (client.currentScreen != null && !openedInventoryForMove) { resetQueue(); return; }
        boolean holdingXp = isHoldingXpBottle(client);

        if (debugMode && worldTick % 20 == 0 && client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.literal("§7[AutoMend] q=" + moveQueue.size() + " pend=" + pendingConfirmTicks + " cd=" + swapCooldownTicks + " d=" + actionDelayTicks + " xp=" + holdingXp), true);
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        if (!(handler instanceof PlayerScreenHandler)) return;

        if (FORCE_SIMPLE_HELMET_TEST) {
            runSimpleHelmetUnequipTest(handler, client);
            return;
        }

        // Continue in-flight pickup/place even if revision ack is pending.
        if (inFlightMove != null) {
            if (actionDelayTicks > 0) { actionDelayTicks--; return; }
            executeNext(handler, client);
            return;
        }

        if (!syncGate(handler)) return;
        if (swapCooldownTicks > 0) { swapCooldownTicks--; return; }
        if (actionDelayTicks > 0) { actionDelayTicks--; return; }

        if (!moveQueue.isEmpty()) {
            executeNext(handler, client);
            return;
        }

        if ((worldTick - lastDecisionTick) < DECISION_INTERVAL) return;
        lastDecisionTick = worldTick;

        queueSmartMove(handler, client);
        if (!moveQueue.isEmpty()) executeNext(handler, client);
    }

    private void runSimpleHelmetUnequipTest(ScreenHandler handler, MinecraftClient client) {
        if (worldTick % 40 != 0) return;
        if (client.player == null || client.interactionManager == null) return;

        Slot helmetSlot = findArmorSlotByInventoryIndex(handler, 39);
        if (helmetSlot == null) {
            debug("simple-test: helmet slot not found syncId=" + handler.syncId + " size=" + handler.slots.size() + " handler=" + handler.getClass().getSimpleName());
            return;
        }
        if (!helmetSlot.hasStack()) {
            debug("simple-test: helmet already empty slotId=" + helmetSlot.id + " invIdx=" + helmetSlot.getIndex());
            return;
        }

        Slot destination = findFirstOpenStorageSlot(handler);
        if (destination == null) {
            debug("simple-test: no destination slot for helmet");
            return;
        }

        debug("simple-test: move helmet syncId=" + handler.syncId + " handler=" + handler.getClass().getSimpleName()
                + " from(slotId=" + helmetSlot.id + ",invIdx=" + helmetSlot.getIndex() + ")"
                + " to(slotId=" + destination.id + ",invIdx=" + destination.getIndex() + ")"
                + " cursorBefore=" + handler.getCursorStack());

        click(handler.syncId, helmetSlot.id, client);
        debug("simple-test: after pickup cursor=" + handler.getCursorStack() + " fromHas=" + helmetSlot.hasStack());

        click(handler.syncId, destination.id, client);
        debug("simple-test: after place cursor=" + handler.getCursorStack() + " dstHas=" + destination.hasStack());

        if (!handler.getCursorStack().isEmpty()) {
            debug("simple-test: cursor not empty, reverting to source slotId=" + helmetSlot.id);
            click(handler.syncId, helmetSlot.id, client);
        }
    }

    private void queueSmartMove(ScreenHandler handler, MinecraftClient client) {
        List<ArmorState> equipped = getArmorStates(handler);
        if (equipped.isEmpty()) return;

        int bottleCount = countXpBottles(client);
        boolean holdingXp = isHoldingXpBottle(client);
        int target = phaseTargetRaw + phaseTargetHysteresisRaw;

        // Never unequip armor unless actively holding XP bottles.
        if (holdingXp) {
            ArmorState aboveTarget = equipped.stream()
                    .filter(a -> !a.binding)
                    .filter(a -> a.remainingRaw >= target)
                    .max(Comparator.comparingInt(ArmorState::remainingRaw))
                    .orElse(null);
            if (aboveTarget != null && allowDirectionChange(true)) {
                Slot destination = findFirstOpenStorageSlot(handler);
                if (destination != null) {
                    reservedDestinations.add(destination.id);
                    moveQueue.addLast(new SlotMove(aboveTarget.slotId, destination.id, worldTick, "unequip_at_target"));
                    lastDirectionUnequip = true;
                    lastReverseTick = worldTick;
                    debug("queued target-cap unequip " + aboveTarget.slotId + "->" + destination.id + " target=" + target + " bottles=" + bottleCount);
                    return;
                }
            }
        }

        EmptyArmorSlot emptyArmor = findEmptyArmorSlotWithType(handler);
        if (emptyArmor == null) return;

        // Only re-equip when full 400-set phase is finished OR player is no longer holding XP.
        boolean allEquippedAtTarget = equipped.stream().allMatch(a -> a.remainingRaw >= phaseTargetRaw);
        boolean shouldReequip = !holdingXp || allEquippedAtTarget;
        if (!shouldReequip || !allowDirectionChange(false)) return;

        ArmorState bestStorage = findBestStorageArmorForSlot(handler, emptyArmor.eqSlot);
        if (bestStorage != null && !reservedDestinations.contains(emptyArmor.slot.id)) {
            int targetRaw = getEqualizeTargetRaw(handler, equipped, emptyArmor.eqSlot);
            int diff = Math.abs(bestStorage.remainingRaw - targetRaw);
            if (!holdingXp || diff <= reEquipToleranceRaw || bestStorage.remainingRaw >= phaseTargetRaw) {
                moveQueue.addLast(new SlotMove(bestStorage.slotId, emptyArmor.slot.id, worldTick, "reequip_equalized"));
                lastDirectionUnequip = false;
                lastReverseTick = worldTick;
                debug("queued final equalized re-equip " + bestStorage.slotId + "->" + emptyArmor.slot.id + " target=" + targetRaw + " diff=" + diff + " bottles=" + bottleCount + " allAtTarget=" + allEquippedAtTarget);
            }
        }
    }

    private void executeNext(ScreenHandler handler, MinecraftClient client) {
        if (inFlightMove == null) {
            inFlightMove = moveQueue.pollFirst();
            inFlightPickedUp = false;
            openedInventoryForMove = false;
            if (inFlightMove == null) return;
        }

        if (!openedInventoryForMove && client.currentScreen == null && client.player != null) {
            client.setScreen(new InventoryScreen(client.player));
            openedInventoryForMove = true;
            actionDelayTicks = 1;
            return;
        }

        SlotMove move = inFlightMove;
        Slot from = getSlotById(handler, move.fromSlotId);
        Slot to = getSlotById(handler, move.toSlotId);

        if (from == null || to == null) { resetQueue(); return; }

        if (!inFlightPickedUp) {
            if (!from.hasStack()) { inFlightMove = null; return; }
            ItemStack source = from.getStack();
            if (safeInventoryMode && (!to.canInsert(source) || source.isEmpty())) { resetQueue(); return; }

            debug("click pickup " + move.reason + " sync=" + handler.syncId + " from=" + move.fromSlotId + " to=" + move.toSlotId + " cursor=" + handler.getCursorStack());
            click(handler.syncId, move.fromSlotId, client);
            inFlightPickedUp = true;
            pendingConfirmTicks = 1;
            actionDelayTicks = 1;
            return;
        }

        // second stage: place on a later tick after revision/cursor update
        if (handler.getCursorStack().isEmpty()) {
            debug("pickup lost before place; aborting move from=" + move.fromSlotId + " to=" + move.toSlotId);
            inFlightMove = null;
            inFlightPickedUp = false;
            if (openedInventoryForMove) {
                client.setScreen(null);
                openedInventoryForMove = false;
            }
            return;
        }

        debug("click place " + move.reason + " sync=" + handler.syncId + " from=" + move.fromSlotId + " to=" + move.toSlotId + " cursor=" + handler.getCursorStack());
        click(handler.syncId, move.toSlotId, client);

        if (move.fromSlotId == lastActionFrom || move.toSlotId == lastActionTo) sameTargetStreak++;
        else sameTargetStreak = 0;
        lastActionFrom = move.fromSlotId;
        lastActionTo = move.toSlotId;

        actionDelayTicks = actionDelayTicksSetting + random.nextInt(Math.max(1, delayJitterTicks + 1));
        if (random.nextInt(7) == 0) actionDelayTicks += 2;
        swapCooldownTicks = postActionCooldownTicks + random.nextInt(5);
        pendingConfirmTicks = 1;
        reservedDestinations.remove(move.toSlotId);

        inFlightMove = null;
        inFlightPickedUp = false;
        if (openedInventoryForMove) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) mc.setScreen(null);
            openedInventoryForMove = false;
        }
    }

    private List<ArmorState> getArmorStates(ScreenHandler handler) {
        if ((worldTick - lastBalanceCalcTick) < RECALC_INTERVAL && handler.getRevision() == cacheArmorRevision) {
            return cachedArmor;
        }
        lastBalanceCalcTick = worldTick;
        cacheArmorRevision = handler.getRevision();

        List<ArmorState> armor = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (!isArmorInventorySlot(slot) || !slot.hasStack()) continue;
            ItemStack stack = slot.getStack();
            EquipmentSlot eq = getEquipmentSlot(stack);
            if (eq == null) continue;
            int remaining = getRemainingDurability(stack);
            double ratio = durabilityRatio(stack);
            armor.add(new ArmorState(slot.id, eq, ratio, remaining, hasBindingCurse(stack), stack.getEnchantments().getSize()));
            debug("armor slotId=" + slot.id + " invIdx=" + slot.getIndex() + " eq=" + eq + " rem=" + remaining + "/" + stack.getMaxDamage() + " ratio=" + String.format(java.util.Locale.ROOT, "%.2f", ratio * 100));
        }
        cachedArmor = armor;
        return armor;
    }

    private boolean syncGate(ScreenHandler handler) {
        int revision = handler.getRevision();
        if (revision != lastKnownRevision) {
            lastKnownRevision = revision;
            pendingConfirmTicks = 0;
            return true;
        }
        if (pendingConfirmTicks > 0) {
            pendingConfirmTicks++;
            if (pendingConfirmTicks > CONFIRM_TIMEOUT) {
                debug("pending confirm timeout; resetting queue");
                resetQueue();
                actionDelayTicks = 2 + random.nextInt(4);
            }
            return false;
        }
        return true;
    }

    private boolean allowDirectionChange(boolean newUnequipDirection) {
        if (lastDirectionUnequip == newUnequipDirection) return true;
        return (worldTick - lastReverseTick) >= MIN_REVERSE_TICKS;
    }

    private double weightedMetricForUnequip(ArmorState state) {
        double base = balanceMode == BalanceMode.EQUAL_RAW ? state.remainingRaw : state.ratio;
        if (weightedChestplatePriority && state.eqSlot == EquipmentSlot.CHEST) base *= 1.35d;
        return base + (state.score * 0.0025d);
    }

    private ArmorState findBestStorageArmor(ScreenHandler handler) {
        return handler.slots.stream()
                .filter(this::isStorageInventorySlot)
                .filter(Slot::hasStack)
                .map(slot -> {
                    ItemStack stack = slot.getStack();
                    EquipmentSlot eq = getEquipmentSlot(stack);
                    if (eq == null) return null;
                    return new ArmorState(slot.id, eq, durabilityRatio(stack), getRemainingDurability(stack), hasBindingCurse(stack), stack.getEnchantments().getSize());
                })
                .filter(Objects::nonNull)
                .max(Comparator.comparingDouble(this::weightedMetricForUnequip))
                .orElse(null);
    }

    private ArmorState findBestStorageArmorForSlot(ScreenHandler handler, EquipmentSlot eqSlot) {
        return handler.slots.stream()
                .filter(this::isStorageInventorySlot)
                .filter(Slot::hasStack)
                .map(slot -> {
                    ItemStack stack = slot.getStack();
                    EquipmentSlot eq = getEquipmentSlot(stack);
                    if (eq == null || eq != eqSlot) return null;
                    return new ArmorState(slot.id, eq, durabilityRatio(stack), getRemainingDurability(stack), hasBindingCurse(stack), stack.getEnchantments().getSize());
                })
                .filter(Objects::nonNull)
                .max(Comparator.comparingInt(ArmorState::remainingRaw))
                .orElse(null);
    }

    private int getTargetRawForSlot(List<ArmorState> equippedArmor, EquipmentSlot slot) {
        return equippedArmor.stream()
                .filter(a -> a.eqSlot != slot)
                .mapToInt(ArmorState::remainingRaw)
                .max()
                .orElse(0);
    }

    private ArmorState findStorageArmorForSlotUnderTarget(ScreenHandler handler, EquipmentSlot eqSlot, int targetRaw) {
        return handler.slots.stream()
                .filter(this::isStorageInventorySlot)
                .filter(Slot::hasStack)
                .map(slot -> {
                    ItemStack stack = slot.getStack();
                    EquipmentSlot eq = getEquipmentSlot(stack);
                    if (eq == null || eq != eqSlot) return null;
                    ArmorState st = new ArmorState(slot.id, eq, durabilityRatio(stack), getRemainingDurability(stack), hasBindingCurse(stack), stack.getEnchantments().getSize());
                    return st.remainingRaw < targetRaw ? st : null;
                })
                .filter(Objects::nonNull)
                .min(Comparator.comparingInt(ArmorState::remainingRaw))
                .orElse(null);
    }

    private int getEqualizeTargetRaw(ScreenHandler handler, List<ArmorState> equippedArmor, EquipmentSlot missingSlot) {
        List<Integer> values = new ArrayList<>();
        for (ArmorState a : equippedArmor) values.add(a.remainingRaw);
        ArmorState storageCandidate = findBestStorageArmorForSlot(handler, missingSlot);
        if (storageCandidate != null) values.add(storageCandidate.remainingRaw);
        return values.stream().min(Integer::compareTo).orElse(0);
    }

    private int countXpBottles(MinecraftClient client) {
        int total = 0;
        PlayerInventory inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isOf(net.minecraft.item.Items.EXPERIENCE_BOTTLE)) total += stack.getCount();
        }
        return total;
    }

    private Slot findFirstOpenStorageSlot(ScreenHandler handler) {
        for (Slot slot : handler.slots) {
            if (!isStorageInventorySlot(slot) || reservedDestinations.contains(slot.id)) continue;
            if (!slot.hasStack()) return slot;
        }
        return null;
    }

    private EmptyArmorSlot findEmptyArmorSlotWithType(ScreenHandler handler) {
        for (Slot slot : handler.slots) {
            if (!isArmorInventorySlot(slot) || slot.hasStack()) continue;
            EquipmentSlot eq = equipmentSlotFromArmorInventoryIndex(slot.getIndex());
            if (eq != null) return new EmptyArmorSlot(slot, eq);
        }
        return null;
    }

    private boolean isMoving(MinecraftClient client) {
        Vec3d pos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
        boolean moving = pos.squaredDistanceTo(lastPos) > 0.0009;
        lastPos = pos;
        return moving;
    }

    private boolean hasNearbyThreat(MinecraftClient client) {
        return !client.world.getOtherEntities(client.player, client.player.getBoundingBox().expand(6.0), e -> e.isAlive()).isEmpty();
    }

    private boolean isHoldingXpBottle(MinecraftClient client) {
        return client.player.getMainHandStack().isOf(net.minecraft.item.Items.EXPERIENCE_BOTTLE)
                || client.player.getOffHandStack().isOf(net.minecraft.item.Items.EXPERIENCE_BOTTLE);
    }

    private boolean isLowXp(MinecraftClient client) {
        return client.player.experienceLevel <= 1 && client.player.experienceProgress < 0.15f;
    }

    private boolean isArmorInventorySlot(Slot slot) {
        if (!(slot.inventory instanceof PlayerInventory)) return false;
        int idx = slot.getIndex();
        return idx >= 36 && idx <= 39;
    }

    private EquipmentSlot equipmentSlotFromArmorInventoryIndex(int idx) {
        return switch (idx) {
            case 36 -> EquipmentSlot.FEET;
            case 37 -> EquipmentSlot.LEGS;
            case 38 -> EquipmentSlot.CHEST;
            case 39 -> EquipmentSlot.HEAD;
            default -> null;
        };
    }

    private Slot findArmorSlotByInventoryIndex(ScreenHandler handler, int inventoryIndex) {
        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory)) continue;
            if (slot.getIndex() == inventoryIndex) return slot;
        }
        return null;
    }

    private boolean isStorageInventorySlot(Slot slot) {
        if (!(slot.inventory instanceof PlayerInventory)) return false;
        int idx = slot.getIndex();
        return idx >= 0 && idx <= 35;
    }

    private Slot getSlotById(ScreenHandler handler, int slotId) {
        if (slotId < 0 || slotId >= handler.slots.size()) return null;
        return handler.slots.get(slotId);
    }

    private EquipmentSlot getEquipmentSlot(ItemStack stack) {
        EquippableComponent eq = stack.get(DataComponentTypes.EQUIPPABLE);
        return eq == null ? null : eq.slot();
    }

    private boolean hasBindingCurse(ItemStack stack) {
        return stack.getEnchantments().toString().contains("binding");
    }

    private static int getRemainingDurability(ItemStack stack) {
        return stack.isDamageable() ? stack.getMaxDamage() - stack.getDamage() : Integer.MAX_VALUE;
    }

    private static double durabilityRatio(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageable()) return 1.0d;
        int max = stack.getMaxDamage();
        if (max <= 0) return 1.0d;
        return Math.max(0.0d, Math.min(1.0d, (max - stack.getDamage()) / (double) max));
    }

    private void click(int syncId, int slotId, MinecraftClient client) {
        client.interactionManager.clickSlot(syncId, slotId, 0, SlotActionType.PICKUP, client.player);
    }

    public void resetQueue() {
        moveQueue.clear();
        reservedDestinations.clear();
        pendingConfirmTicks = 0;
        cacheArmorRevision = -1;
        inFlightMove = null;
        inFlightPickedUp = false;
        if (openedInventoryForMove) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) mc.setScreen(null);
            openedInventoryForMove = false;
        }
    }

    private void resetRuntimeState() {
        resetQueue();
        actionDelayTicks = 0;
        swapCooldownTicks = 0;
        sameTargetStreak = 0;
        lastActionFrom = -1;
        lastActionTo = -1;
        lastKnownRevision = -1;
    }

    public void loadConfig() {
        try {
            if (!Files.exists(configPath)) return;
            Properties p = new Properties();
            try (var in = Files.newInputStream(configPath)) { p.load(in); }
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "false"));
            balanceMode = BalanceMode.valueOf(p.getProperty("balanceMode", BalanceMode.EQUAL_PERCENT.name()));
            balanceTolerancePercent = Double.parseDouble(p.getProperty("balanceTolerancePercent", "12.0"));
            actionDelayTicksSetting = Integer.parseInt(p.getProperty("actionDelayTicks", "3"));
            delayJitterTicks = Integer.parseInt(p.getProperty("delayJitterTicks", "4"));
            postActionCooldownTicks = Integer.parseInt(p.getProperty("postActionCooldownTicks", "10"));
            pauseWhileMoving = Boolean.parseBoolean(p.getProperty("pauseWhileMoving", "true"));
            pauseNearEnemies = Boolean.parseBoolean(p.getProperty("pauseNearEnemies", "false"));
            safeInventoryMode = Boolean.parseBoolean(p.getProperty("safeInventoryMode", "true"));
            debugMode = Boolean.parseBoolean(p.getProperty("debugMode", "false"));
            xpOnlyActivation = Boolean.parseBoolean(p.getProperty("xpOnlyActivation", "false"));
            weightedChestplatePriority = Boolean.parseBoolean(p.getProperty("weightedChestplatePriority", "true"));
            unequipTargetRemainingRaw = Integer.parseInt(p.getProperty("unequipTargetRemainingRaw", "405"));
            equalizeGapRaw = Integer.parseInt(p.getProperty("equalizeGapRaw", "8"));
            reEquipToleranceRaw = Integer.parseInt(p.getProperty("reEquipToleranceRaw", "2"));
            phaseTargetRaw = Integer.parseInt(p.getProperty("phaseTargetRaw", "400"));
            lowBottleCountThreshold = Integer.parseInt(p.getProperty("lowBottleCountThreshold", "40"));
            phaseTargetHysteresisRaw = Integer.parseInt(p.getProperty("phaseTargetHysteresisRaw", "0"));
        } catch (Exception ignored) {}
    }

    public void saveConfig() {
        try {
            Properties p = new Properties();
            p.setProperty("enabled", Boolean.toString(enabled));
            p.setProperty("balanceMode", balanceMode.name());
            p.setProperty("balanceTolerancePercent", Double.toString(balanceTolerancePercent));
            p.setProperty("actionDelayTicks", Integer.toString(actionDelayTicksSetting));
            p.setProperty("delayJitterTicks", Integer.toString(delayJitterTicks));
            p.setProperty("postActionCooldownTicks", Integer.toString(postActionCooldownTicks));
            p.setProperty("pauseWhileMoving", Boolean.toString(pauseWhileMoving));
            p.setProperty("pauseNearEnemies", Boolean.toString(pauseNearEnemies));
            p.setProperty("safeInventoryMode", Boolean.toString(safeInventoryMode));
            p.setProperty("debugMode", Boolean.toString(debugMode));
            p.setProperty("xpOnlyActivation", Boolean.toString(xpOnlyActivation));
            p.setProperty("weightedChestplatePriority", Boolean.toString(weightedChestplatePriority));
            p.setProperty("unequipTargetRemainingRaw", Integer.toString(unequipTargetRemainingRaw));
            p.setProperty("equalizeGapRaw", Integer.toString(equalizeGapRaw));
            p.setProperty("reEquipToleranceRaw", Integer.toString(reEquipToleranceRaw));
            p.setProperty("phaseTargetRaw", Integer.toString(phaseTargetRaw));
            p.setProperty("lowBottleCountThreshold", Integer.toString(lowBottleCountThreshold));
            p.setProperty("phaseTargetHysteresisRaw", Integer.toString(phaseTargetHysteresisRaw));
            Files.createDirectories(configPath.getParent());
            try (var out = Files.newOutputStream(configPath)) { p.store(out, "AutoMend settings"); }
        } catch (Exception ignored) {}
    }

    public int getDefaultKey() {
        return GLFW.GLFW_KEY_UNKNOWN;
    }

    private void debug(String msg) {
        if (debugMode) System.out.println("[AutoMend] " + msg);
    }
}
