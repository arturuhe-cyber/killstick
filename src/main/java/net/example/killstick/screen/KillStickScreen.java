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
    private static final int MAX_TOTAL = 60;
    private static final int SLOTS = 5;
    private static final List<String> PRESETS = List.of(
            "/kill %player%", "/msg %player% ", "/tp %player%", "/say ", "# ");

    private final List<String> commands;
    private final List<TextFieldWidget> fields = new ArrayList<>();
    private final List<ButtonWidget> chips = new ArrayList<>();
    private final String[] chipValues = new String[SLOTS];

    private int activeIndex;   // номер команды во всём списке
    private int page;
    private int perPage = 6;
    private int pageStart;
    private int totalPages = 1;
    private int labelX;
    private int labelY;
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

    private TextFieldWidget activeField() {
        int local = activeIndex - pageStart;
        if (local < 0 || local >= fields.size()) return null;
        return fields.get(local);
    }

    private void openAt(int focusIndex) {
        client.setScreen(new KillStickScreen(commands, focusIndex));
    }

    @Override
    protected void init() {
        fields.clear();
        chips.clear();

        perPage = Math.max(3, (height - 104) / 22);
        totalPages = (commands.size() + perPage - 1) / perPage;
        page = Math.min(activeIndex / perPage, totalPages - 1);
        pageStart = page * perPage;
        int pageEnd = Math.min(commands.size(), pageStart + perPage);

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

        // Поля команд текущей страницы
        int y = 68;
        for (int i = pageStart; i < pageEnd; i++) {
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
                openAt(Math.min(index, commands.size() - 1));
            }).dimensions(x + fieldWidth + 4, y, 20, 20).build());
            y += 22;
        }

        // Нижняя строка: если страниц несколько, кнопки стоят на одном месте
        int bottomY = (totalPages > 1 ? 68 + perPage * 22 : y) + 6;

        if (commands.size() < MAX_TOTAL) {
            addDrawableChild(ButtonWidget.builder(Text.literal("+ Add"), button -> {
                commands.add("");
                openAt(commands.size() - 1);
            }).dimensions(x, bottomY, 50, 20).build());
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("%player%"), button -> {
            TextFieldWidget field = activeField();
            if (field != null) {
                field.write("%player%");
                setFocused(field);
            }
        }).dimensions(x + 54, bottomY, 66, 20).build());

        ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), button -> openAt((page - 1) * perPage))
                .dimensions(x + 124, bottomY, 22, 20).build();
        prev.active = page > 0;
        addDrawableChild(prev);

        ButtonWidget next = ButtonWidget.builder(Text.literal(">"), button -> openAt((page + 1) * perPage))
                .dimensions(x + 176, bottomY, 22, 20).build();
        next.active = page < totalPages - 1;
        addDrawableChild(next);

        labelX = x + 161;
        labelY = bottomY + 6;

        addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> close())
                .dimensions(x + rowWidth - 70, bottomY, 70, 20).build());

        TextFieldWidget focus = activeField();
        if (focus != null) setInitialFocus(focus);
        refreshSuggestions();
    }

    private void applyChip(int slot) {
        String value = chipValues[slot];
        TextFieldWidget field = activeField();
        if (value == null || field == null) return;
        field.setText(value);
        setFocused(field);
    }

    private void showChips(List<String[]> items) {
        for (int i = 0; i < chips.size(); i++) {
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
            if (fields.get(i).isFocused() && pageStart + i != activeIndex) {
                activeIndex = pageStart + i;
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
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal((page + 1) + "/" + totalPages), labelX, labelY, 0xFFFFFFFF);
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
