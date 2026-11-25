package net.example.killstick;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.example.killstick.screen.KillStickScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class KillStickClient implements ClientModInitializer {
    /** Набор команд, привязанный к одному предмету. */
    public static class Profile {
        public String trigger = "attack";                 // attack = ЛКМ по игроку, use = ПКМ (натянуть/использовать)
        public List<String> commands = new ArrayList<>();

        public boolean isUse() {
            return "use".equalsIgnoreCase(trigger);
        }
    }

    public static class Config {
        public long cooldownMs = 250;                      // пауза между срабатываниями
        public Map<String, Profile> profiles = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config config = new Config();
    private static Path configFile;
    private static long lastExecMs = 0;
    private static boolean pendingOpen = false;

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("killstick", "main"));
    private static KeyBinding openKey;

    @Override
    public void onInitializeClient() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve("killstick.json");
        loadConfig();

        // Клавиша K: настройки для предмета в руке
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.killstick.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                CATEGORY));

        // Команда /killstick тоже открывает настройки
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("killstick").executes(context -> {
                    pendingOpen = true; // откроем в следующем тике, когда закроется чат
                    return 1;
                })));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean pressed = false;
            while (openKey.wasPressed()) pressed = true;
            if (pressed && client.currentScreen == null) pendingOpen = true;
            if (pendingOpen && client.currentScreen == null) {
                pendingOpen = false;
                if (client.player != null) openScreen(client, heldItemId(client.player));
            }
        });
    }

    // ===== Помощники для миксина и экрана =====

    public static String heldItemId(ClientPlayerEntity player) {
        return Registries.ITEM.getId(player.getMainHandStack().getItem()).toString();
    }

    /** Профиль предмета, если для него заданы команды. */
    public static Profile getProfile(String itemId) {
        Profile profile = config.profiles.get(itemId);
        if (profile == null || profile.commands == null || profile.commands.isEmpty()) return null;
        return profile;
    }

    public static void openScreen(MinecraftClient client, String itemId) {
        if (client.player == null) return;
        if (itemId.equals("minecraft:air")) {
            client.player.sendMessage(Text.literal("[KillStick] Hold an item in your hand"), true);
            return;
        }
        Profile copy = new Profile();
        Profile existing = config.profiles.get(itemId);
        if (existing != null) {
            copy.trigger = existing.trigger;
            if (existing.commands != null) copy.commands = new ArrayList<>(existing.commands);
        } else if (itemId.equals("minecraft:trident")) {
            copy.trigger = "use"; // трезубец по умолчанию срабатывает при натягивании
        }
        client.setScreen(new KillStickScreen(itemId, copy));
    }

    /** Сохраняет профиль. Пустой список команд = предмет больше не привязан. */
    public static void saveProfile(String itemId, Profile profile) {
        if (profile.commands == null || profile.commands.isEmpty()) {
            config.profiles.remove(itemId);
        } else {
            config.profiles.put(itemId, profile);
        }
        saveConfig();
    }

    /** Ник игрока под прицелом или null. */
    public static String targetPlayerName(MinecraftClient client) {
        HitResult hit = client.crosshairTarget;
        if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
            Entity entity = ((EntityHitResult) hit).getEntity();
            if (entity instanceof PlayerEntity) return entity.getName().getString();
        }
        return null;
    }

    /**
     * Выполняет команды, подставляя ник цели вместо %player%.
     *  "/команда" - команда, "# текст" - сообщение только вам, "текст" - в общий чат.
     */
    public static void executeCommands(List<String> commands, String targetName) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastExecMs < config.cooldownMs) return;
        lastExecMs = nowMs;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        ClientPlayNetworkHandler handler = client.player.networkHandler;
        if (handler == null) return;

        for (String cmd : commands) {
            String line = cmd.trim().replace("%player%", targetName);
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                String message = line.substring(1).trim();
                if (!message.isEmpty()) client.player.sendMessage(Text.literal(message), false);
            } else if (line.startsWith("/")) {
                handler.sendChatCommand(line.substring(1));
            } else {
                handler.sendChatMessage(line);
            }
        }
    }

    // ===== Конфиг =====

    private static void loadConfig() {
        try {
            if (configFile != null && Files.exists(configFile)) {
                try (Reader reader = Files.newBufferedReader(configFile)) {
                    JsonElement element = JsonParser.parseReader(reader);
                    if (element.isJsonArray()) {
                        // Старый формат: просто список команд для палки
                        Type listType = new TypeToken<List<String>>() {}.getType();
                        List<String> legacy = GSON.fromJson(element, listType);
                        Profile stick = new Profile();
                        if (legacy != null) stick.commands = new ArrayList<>(legacy);
                        config.profiles.put("minecraft:stick", stick);
                        saveConfig();
                    } else if (element.isJsonObject()) {
                        Config loaded = GSON.fromJson(element, Config.class);
                        if (loaded != null) config = loaded;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (config.profiles == null) config.profiles = new LinkedHashMap<>();
    }

    private static void saveConfig() {
        try (Writer writer = Files.newBufferedWriter(configFile)) {
            GSON.toJson(config, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
