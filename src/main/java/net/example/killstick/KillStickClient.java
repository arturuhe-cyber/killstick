package net.example.killstick;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.example.killstick.screen.KillStickScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class KillStickClient implements ClientModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<String>>() {}.getType();
    private static List<String> savedCommands = new ArrayList<>();
    private static Path configFile;

    @Override
    public void onInitializeClient() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve("killstick.json");
        loadConfig();
    }

    public static void setCommands(List<String> commands) {
        savedCommands = new ArrayList<>(commands);
        saveConfig();
    }

    public static List<String> getCommands() {
        return savedCommands;
    }

    public static void openScreen() {
        MinecraftClient.getInstance().setScreen(new KillStickScreen(savedCommands));
    }

    private static void loadConfig() {
        try {
            if (configFile != null && Files.exists(configFile)) {
                try (Reader reader = Files.newBufferedReader(configFile)) {
                    List<String> loaded = GSON.fromJson(reader, LIST_TYPE);
                    if (loaded != null) savedCommands = new ArrayList<>(loaded);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void saveConfig() {
        try (Writer writer = Files.newBufferedWriter(configFile)) {
            GSON.toJson(savedCommands, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Выполняет все сохранённые команды, подставляя ник цели вместо %player%. */
    public static void executeCommands(String targetName) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        ClientPlayNetworkHandler handler = client.player.networkHandler;
        if (handler == null) return;

        for (String cmd : savedCommands) {
            String finalCmd = cmd.trim().replace("%player%", targetName);
            if (finalCmd.isEmpty()) continue;
            if (finalCmd.startsWith("/")) {
                handler.sendChatCommand(finalCmd.substring(1));
            } else {
                handler.sendChatMessage(finalCmd);
            }
        }
    }
}
