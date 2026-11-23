package net.example.killstick.screen;

import com.mojang.brigadier.suggestion.Suggestion;
import net.example.killstick.KillStickClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

public class KillStickScreen extends Screen {
    private static final int MAX_COMMANDS = 6;
    private static final int SLOTS = 5;
    private static final List<String> PRESETS = List.of(
            "/kill %player%", "/msg %player% ", "/tp %player%", "/say ", "# ");

    private final List<String> commands;
    private final List<TextFieldWidget> fields = new ArrayList<>();
    private final List<ButtonWidget> chips = new ArrayList<>();
    private final String[] chipValues = new String[SLOTS];
    private int activeIndex;
    private int requestId = 0;

    public KillStickScreen(List<String> initialCommands) {
        this(initialCommands, 0);
    }

    public KillStickScreen(List<String> initialCommands, int focusIndex) {
        super(Text.literal("KillStick"));
        this.commands = new ArrayList<>(initialCommands);
        if (this.commands.isEmpty()) this.commands.add("");
        this.activeIndex = Math.max(0, Math.min(focusIndex, this.commands.size() - 1));
    }

    @Override
    protected void init() {
        fields.clear();
        chips.clear();

        int fieldWidth = Math.min(300, width - 80);
        int rowWidth = fieldWidth + 24;
        int x = (width - rowWidth) / 2;

        // Строка подсказок
        int gap = 3;
        int chipWidth = (rowWidth - gap * (SLOTS - 1)) / SLOTS;
        for (int i = 0; i < SLOTS; i++) {
            final int slot = i;
            ButtonWidget chip = ButtonWidget.builder(Text.empty(), button -> applyChip(slot))
                    .dimensions(x + i * (chipWidth + gap), 44, chipWidth, 18).build();
            chip.visible = false;
            chips.add(chip);
            addDrawableChild(chip);
        }

        // Поля команд
        int y = 68;
        for (int i = 0; i < commands.size(); i++) {
            final int index = i;
            TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, fieldWidth, 20,
                    Text.literal("Command " + (i + 1)));
            field.setMaxLength(256);
            field.setText(commands.get(i));
            field.setChangedListener(text -> {
                commands.set(index, text);
                activeIndex = index;
                refreshSuggestions();
            });
            addDrawableChild(field);
            fields.add(field);

            addDrawableChild(ButtonWidget.builder(Text.literal("X"), button -> {
                commands.remove(index);
                if (commands.isEmpty()) commands.add("");
                client.setScreen(new KillStickScreen(commands, 0));
            }).dimensions(x + fieldWidth + 4, y, 20, 20).build());
            y += 22;
        }

        // Нижняя строка кнопок
        if (commands.size() < MAX_COMMANDS) {
            addDrawableChild(ButtonWidget.builder(Text.literal("+ Add"), button -> {
                commands.add("");
                client.setScreen(new KillStickScreen(commands, commands.size() - 1));
            }).dimensions(x, y + 6, 60, 20).build());
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("%player%"), button -> {
            if (activeIndex < fields.size()) {
                TextFieldWidget field = fields.get(activeIndex);
                field.write("%player%");
                setFocused(field);
            }
        }).dimensions(x + 64, y + 6, 70, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> close())
                .dimensions(x + rowWidth - 90, y + 6, 90, 20).build());

        if (activeIndex < fields.size()) {
            setInitialFocus(fields.get(activeIndex));
        }
        refreshSuggestions();
    }

    private void applyChip(int slot) {
        String value = chipValues[slot];
        if (value == null || activeIndex >= fields.size()) return;
        TextFieldWidget field = fields.get(activeIndex);
        field.setText(value);
        setFocused(field);
    }

    private void showChips(List<String[]> items) {
        for (int i = 0; i < SLOTS; i++) {
            ButtonWidget chip = chips.get(i);
            if (i < items.size()) {
                chip.setMessage(Text.literal(items.get(i)[0]));
                chipValues[i] = items.get(i)[1];
                chip.visible = true;
            } else {
                chipValues[i] = null;
                chip.visible = false;
            }
        }
    }

    /** Обновляет подсказки для активного поля. */
    private void refreshSuggestions() {
        if (chips.isEmpty()) return;
        if (activeIndex >= commands.size()) {
            showChips(List.of());
            return;
        }
        final String text = commands.get(activeIndex);
        final int id = ++requestId;

        // Пустое поле: готовые шаблоны
        if (text.isEmpty()) {
            List<String[]> presets = new ArrayList<>();
            for (String preset : PRESETS) presets.add(new String[]{preset, preset});
            showChips(presets);
            return;
        }

        // Не команда: подсказок нет
        if (!text.startsWith("/")) {
            showChips(List.of());
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            showChips(List.of());
            return;
        }

        // Команда: настоящие подсказки от встроенного диспетчера команд игры
        try {
            ClientPlayNetworkHandler handler = mc.player.networkHandler;
            final String cmd = text.substring(1).replace("%player%", "Steve");
            var dispatcher = handler.getCommandDispatcher();
            var parse = dispatcher.parse(cmd, handler.getCommandSource());
            dispatcher.getCompletionSuggestions(parse).thenAccept(suggestions -> mc.execute(() -> {
                if (id != requestId || activeIndex >= commands.size()) return;
                int cmdTokenStart = cmd.lastIndexOf(' ') + 1;
                int textTokenStart = text.lastIndexOf(' ') + 1;
                if (textTokenStart == 0) textTokenStart = 1; // после "/"
                List<String[]> list = new ArrayList<>();
                for (Suggestion suggestion : suggestions.getList()) {
                    if (suggestion.getRange().getStart() != cmdTokenStart) continue;
                    list.add(new String[]{suggestion.getText(),
                            text.substring(0, textTokenStart) + suggestion.getText()});
                    if (list.size() >= SLOTS) break;
                }
                showChips(list);
            }));
        } catch (Exception e) {
            showChips(List.of());
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Определяем, в каком поле сейчас курсор, и обновляем подсказки
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).isFocused() && i != activeIndex) {
                activeIndex = i;
                refreshSuggestions();
            }
        }
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("%player% = target's nickname").formatted(Formatting.GRAY),
                width / 2, 20, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("/command = command  |  text = public chat  |  #text = only you").formatted(Formatting.GRAY),
                width / 2, 31, 0xFFFFFFFF);
    }

    @Override
    public void close() {
        List<String> result = new ArrayList<>();
        for (String c : commands) {
            if (!c.isBlank()) result.add(c);
        }
        KillStickClient.setCommands(result);
        super.close();
    }
}
