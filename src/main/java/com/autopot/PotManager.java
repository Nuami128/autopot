package com.autopot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;

import java.util.ArrayList;
import java.util.List;

public class PotManager {

    private record Step(RegistryEntry<StatusEffect> effect) {}

    private enum State {
        IDLE,
        SWITCHING,
        PRESSING,
        RELEASING,
        DONE
    }

    private final List<Step> steps = new ArrayList<>();
    private State state = State.IDLE;
    private int stepIndex = 0;
    private int prevSlot = 0;
    private int waitTick = 0;
    private int stackCountBefore = -1;
    private int timeoutTicks = 0;
    private int currentSlot = -1;

    private static final int SWITCH_WAIT = 1;
    private static final int EMPTY_SLOT_WAIT = 1;
    private static final int DONE_WAIT = 2;
    private static final int TIMEOUT = 40;

    private static final List<RegistryEntry<StatusEffect>> BUFF_EFFECTS = List.of(
            StatusEffects.SPEED,
            StatusEffects.STRENGTH,
            StatusEffects.FIRE_RESISTANCE,
            StatusEffects.REGENERATION
    );

    public void startSequence(MinecraftClient client) {
        if (state != State.IDLE) return;

        steps.clear();
        stepIndex = 0;
        prevSlot = client.player.getInventory().getSelectedSlot();
        currentSlot = prevSlot;

        float health = client.player.getHealth();
        boolean emergencyRebuff = health <= 6.0f;

        // ── HEALING ───────────────────────────────────────────────────────────
        if (findPotionSlot(client, StatusEffects.INSTANT_HEALTH) != -1) {
            if (health < 10.0f) {
                steps.add(new Step(StatusEffects.INSTANT_HEALTH));
                steps.add(new Step(StatusEffects.INSTANT_HEALTH));
            } else if (health < 16.0f) {
                steps.add(new Step(StatusEffects.INSTANT_HEALTH));
            }
        }

        // ── BUFFS ─────────────────────────────────────────────────────────────
        // At critical health, only reapply completely missing buffs.
        // Above critical health, keep normal missing/expiring rebuff behavior.
        boolean criticalHealth = health <= 6.0f;
        for (RegistryEntry<StatusEffect> buff : BUFF_EFFECTS) {
            StatusEffectInstance instance = client.player.getStatusEffect(buff);
            boolean expiring = instance != null && instance.getDuration() <= 200;
            boolean missing = instance == null;
            boolean shouldBuff = criticalHealth ? missing : (missing || expiring);
            if (shouldBuff && findPotionSlot(client, buff) != -1) {
                steps.add(new Step(buff));
            }
        }

        if (steps.isEmpty()) return;

        int firstSlot = findPotionSlot(client, steps.get(0).effect());
        if (firstSlot == -1) {
            reset();
            return;
        }

        doSwitch(client, firstSlot);
        state = State.SWITCHING;
        waitTick = 0;
        timeoutTicks = 0;
    }

    public void tick(MinecraftClient client) {
        if (state == State.IDLE || client.player == null) return;

        timeoutTicks++;
        if (timeoutTicks > TIMEOUT * (steps.size() + 1)) {
            client.options.useKey.setPressed(false);
            reset();
            return;
        }

        switch (state) {
            case SWITCHING -> {
                waitTick++;
                if (waitTick >= SWITCH_WAIT) {
                    stackCountBefore = getStackCount(client, currentSlot);
                    client.options.useKey.setPressed(true);
                    waitTick = 0;
                    state = State.PRESSING;
                }
            }

            case PRESSING -> {
                client.options.useKey.setPressed(false);
                state = State.RELEASING;
                waitTick = 0;
            }

            case RELEASING -> {
                waitTick++;

                int currentCount = getStackCount(client, currentSlot);
                boolean consumed = stackCountBefore > 0 && currentCount < stackCountBefore;
                boolean emptySlotDone = stackCountBefore == 0 && waitTick >= EMPTY_SLOT_WAIT;
                boolean timedOut = waitTick >= TIMEOUT;

                if (consumed || emptySlotDone || timedOut) {
                    stepIndex++;

                    while (stepIndex < steps.size()) {
                        int nextSlot = findPotionSlot(client, steps.get(stepIndex).effect());
                        if (nextSlot == -1) {
                            stepIndex++;
                            continue;
                        }

                        if (nextSlot != currentSlot) {
                            doSwitch(client, nextSlot);
                            waitTick = 0;
                            state = State.SWITCHING;
                        } else {
                            stackCountBefore = getStackCount(client, nextSlot);
                            client.options.useKey.setPressed(true);
                            waitTick = 0;
                            state = State.PRESSING;
                        }
                        return;
                    }

                    waitTick = 0;
                    state = State.DONE;
                }
            }

            case DONE -> {
                // Wait 2 ticks then switch back — let game send slot packet naturally
                waitTick++;
                if (waitTick >= DONE_WAIT) {
                    doSwitch(client, 0);
                    reset();
                }
            }

            default -> {}
        }
    }

    private void doSwitch(MinecraftClient client, int slot) {
        currentSlot = slot;
        // Just set the slot — let Minecraft's own tick loop send the packet
        // naturally, exactly like a real player pressing a number key
        client.player.getInventory().setSelectedSlot(slot);
    }

    private int getStackCount(MinecraftClient client, int slot) {
        ItemStack stack = client.player.getInventory().getStack(slot);
        return stack.isEmpty() ? 0 : stack.getCount();
    }

    private int findPotionSlot(MinecraftClient client, RegistryEntry<StatusEffect> targetEffect) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.getItem() != Items.SPLASH_POTION) continue;

            PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
            if (contents == null) continue;

            for (StatusEffectInstance effect : contents.getEffects()) {
                if (effect.getEffectType().equals(targetEffect)) return i;
            }
        }
        return -1;
    }

    private void reset() {
        state = State.IDLE;
        stepIndex = 0;
        waitTick = 0;
        timeoutTicks = 0;
        stackCountBefore = -1;
        currentSlot = -1;
        steps.clear();
    }
}

