package net.example.killstick.screen;

import net.example.killstick.KillStickClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

public class KillStickScreen extends Screen {
    private static final int MAX_COMMANDS = 8;
    private final List<String> commands;

    public KillStickScreen(List<String> initialCommands) {
        super(Text.literal("KillStick"));
        this.commands = new ArrayList<>(initialCommands);
        if (this.commands.isEmpty()) this.commands.add("");
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(300, width - 80);
        int x = (width - fieldWidth - 24) / 2;
        int y = 50;

        for (int i = 0; i < commands.size(); i++) {
            final int index = i;
            TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, fieldWidth, 20,
                    Text.literal("Command " + (i + 1)));
            field.setMaxLength(256);
            field.setText(commands.get(i));
            field.setChangedListener(text -> commands.set(index, text));
            addDrawableChild(field);

            addDrawableChild(ButtonWidget.builder(Text.literal("X"), button -> {
                commands.remove(index);
                if (commands.isEmpty()) commands.add("");
                reopen();
            }).dimensions(x + fieldWidth + 4, y, 20, 20).build());
            y += 24;
        }

        if (commands.size() < MAX_COMMANDS) {
            addDrawableChild(ButtonWidget.builder(Text.literal("+ Add command"), button -> {
                commands.add("");
                reopen();
            }).dimensions(x, y + 6, 120, 20).build());
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> close())
                .dimensions(x + fieldWidth + 24 - 100, y + 6, 100, 20).build());
    }

    private void reopen() {
        client.setScreen(new KillStickScreen(commands));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 15, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Use %player% for the target's nickname").formatted(Formatting.GRAY),
                width / 2, 30, 0xFFFFFFFF);
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
