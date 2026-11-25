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
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class KillStickClient implements ClientModInitializer {
    /** Набор команд, привязанный к одному предмету. */
    public static class Profile {
        // attack = ЛКМ по игроку, use = ПКМ (смотрите на игрока), hit = брошенный предмет попал в игрока
        public String trigger = "attack";
        public List<String> commands = new ArrayList<>();

        public boolean isUse() {
            return "use".equalsIgnoreCase(trigger);
        }

        public boolean isHit() {
            return "hit".equalsIgnoreCase(trigger);
        }

        public boolean isAttack() {
            return !isUse() && !isHit();
        }
    }

    public static class Config {
        public long cooldownMs = 250;                      // пауза между срабатываниями
        public double useRange = 30;                       // на каком расстоянии ищется игрок для режима USE (блоков)
        public boolean showMessages = true;                // короткие подсказки над панелью предметов
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
            trackProjectiles(client);
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
            copy.trigger = "hit"; // трезубец по умолчанию срабатывает, когда попал в игрока
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

    // ===== Триггер "hit": брошенный предмет попал в игрока =====

    private static final Map<Integer, Vec3d> projectileLast = new HashMap<>();
    private static final Set<Integer> projectileMine = new HashSet<>();
    private static final Set<Integer> projectileFired = new HashSet<>();

    /** Какие предметы запускают этот снаряд. */
    private static List<String> itemsFor(String entityTypeId) {
        return switch (entityTypeId) {
            case "minecraft:trident" -> List.of("minecraft:trident");
            case "minecraft:arrow", "minecraft:spectral_arrow" -> List.of("minecraft:bow", "minecraft:crossbow");
            case "minecraft:snowball" -> List.of("minecraft:snowball");
            case "minecraft:egg" -> List.of("minecraft:egg");
            case "minecraft:ender_pearl" -> List.of("minecraft:ender_pearl");
            default -> List.of();
        };
    }

    private static Profile hitProfileFor(ClientPlayerEntity self, List<String> items) {
        String held = heldItemId(self);
        if (items.contains(held)) {
            Profile p = getProfile(held);
            if (p != null && p.isHit()) return p;
        }
        for (String item : items) {
            Profile p = getProfile(item);
            if (p != null && p.isHit()) return p;
        }
        return null;
    }

    /** Следит за вашими снарядами и срабатывает, когда снаряд попадает в игрока. */
    private static void trackProjectiles(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.world == null) {
            projectileLast.clear();
            projectileMine.clear();
            projectileFired.clear();
            return;
        }

        Set<Integer> seen = new HashSet<>();
        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof ProjectileEntity projectile)) continue;
            List<String> items = itemsFor(Registries.ENTITY_TYPE.getId(entity.getType()).toString());
            if (items.isEmpty()) continue;

            int id = entity.getId();
            boolean firstSight = !projectileLast.containsKey(id);
            if (firstSight) {
                Entity owner = projectile.getOwner();
                if (owner == self || (owner == null && self.distanceTo(entity) < 4.0F)) {
                    projectileMine.add(id);
                }
            }
            if (!projectileMine.contains(id)) continue;

            seen.add(id);
            Vec3d pos = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
            Vec3d prev = projectileLast.getOrDefault(id, pos);
            projectileLast.put(id, pos);
            if (projectileFired.contains(id)) continue;

            Profile profile = hitProfileFor(self, items);
            if (profile == null) continue;

            for (PlayerEntity other : client.world.getPlayers()) {
                if (other == self) continue;
                Box box = other.getBoundingBox().expand(0.3);
                // проверяем и путь за тик, чтобы быстрый снаряд не "проскочил" игрока
                if (box.contains(pos) || box.raycast(prev, pos).isPresent()) {
                    projectileFired.add(id);
                    executeCommands(profile.commands, other.getName().getString());
                    break;
                }
            }
        }
        projectileLast.keySet().retainAll(seen);
        projectileMine.retainAll(seen);
        projectileFired.retainAll(seen);
    }

    /** Игрок, на которого вы смотрите, в пределах range блоков (для режима USE). */
    public static String targetPlayerNameRanged(MinecraftClient client, double range) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.world == null) return null;
        Vec3d eye = self.getEyePos();
        Vec3d end = eye.add(self.getRotationVec(1.0F).multiply(range));
        PlayerEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (PlayerEntity other : client.world.getPlayers()) {
            if (other == self) continue;
            Optional<Vec3d> hit = other.getBoundingBox().expand(0.1).raycast(eye, end);
            if (hit.isPresent()) {
                double distance = eye.distanceTo(hit.get());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = other;
                }
            }
        }
        return best == null ? null : best.getName().getString();
    }

    public static double useRange() {
        return config.useRange;
    }

    /** Короткая подсказка над панелью предметов. */
    public static void hint(MinecraftClient client, String text) {
        if (config.showMessages && client.player != null) {
            client.player.sendMessage(Text.literal("[KillStick] " + text), true);
        }
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

        hint(client, targetName);

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
