package com.autopot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class AutoPotMod implements ClientModInitializer {

    public static KeyBinding toggleKey;
    public static KeyBinding potKey;
    public static KeyBinding mendToggleKey;
    public static boolean enabled = false;

    private final PotManager potManager = new PotManager();
    private final AutoRefillHandler refillHandler = new AutoRefillHandler();
    private final AutoMend mendModule = new AutoMend();

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("autopot", "main"));

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autopot.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                CATEGORY
        ));

        potKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autopot.pot",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                CATEGORY
        ));

        mendToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autopot.mend_toggle",
                InputUtil.Type.KEYSYM,
                mendModule.getDefaultKey(),
                CATEGORY
        ));
        mendModule.setToggleKey(mendToggleKey);
        mendModule.loadConfig();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                client.player.sendMessage(
                        Text.literal("§6AutoPot §r" + (enabled ? "§aEnabled" : "§cDisabled")),
                        true
                );
                mendModule.saveConfig();
            }

            while (potKey.wasPressed()) {
                if (enabled) potManager.startSequence(client);
            }

            potManager.tick(client);
            refillHandler.tick(client);
            mendModule.tick(client);
        });
    }
}
