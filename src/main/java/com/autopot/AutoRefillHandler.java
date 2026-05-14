package com.autopot;

import com.autopot.mixin.HandledScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.List;

public class AutoRefillHandler {

    private static final List<RegistryEntry<StatusEffect>> TRACKED_EFFECTS = List.of(
            StatusEffects.INSTANT_HEALTH,
            StatusEffects.SPEED,
            StatusEffects.STRENGTH,
            StatusEffects.FIRE_RESISTANCE,
            StatusEffects.REGENERATION
    );

    private Slot lastMovedSlot = null;

    public void tick(MinecraftClient client) {
        if (!AutoPotMod.enabled) return;
        if (client.player == null) return;

        if (!(client.currentScreen instanceof ShulkerBoxScreen) &&
            !(client.currentScreen instanceof InventoryScreen)) {
            lastMovedSlot = null;
            return;
        }

        HandledScreen<?> screen = (HandledScreen<?>) client.currentScreen;
        Slot hovered = ((HandledScreenAccessor) screen).getFocusedSlot();

        if (hovered == null || !hovered.hasStack()) {
            lastMovedSlot = null;
            return;
        }

        // Don't re-trigger on the same slot we just moved
        if (hovered == lastMovedSlot) return;

        ItemStack stack = hovered.getStack();

        if (stack.getItem() != Items.SPLASH_POTION || !isTrackedPotion(stack)) {
            lastMovedSlot = null;
            return;
        }

        // Skip hotbar slots (0-8) — don't move pots already in hotbar
        if (isHotbarSlot(hovered, client)) {
            lastMovedSlot = null;
            return;
        }

        // Instant move — no delay
        lastMovedSlot = hovered;
        client.interactionManager.clickSlot(
                screen.getScreenHandler().syncId,
                hovered.id,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );
    }

    private boolean isHotbarSlot(Slot slot, MinecraftClient client) {
        if (slot.inventory == client.player.getInventory()) {
            return slot.getIndex() < 9;
        }
        return false;
    }

    private boolean isTrackedPotion(ItemStack stack) {
        PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (contents == null) return false;

        for (StatusEffectInstance effect : contents.getEffects()) {
            for (RegistryEntry<StatusEffect> tracked : TRACKED_EFFECTS) {
                if (effect.getEffectType().equals(tracked)) return true;
            }
        }
        return false;
    }
}

