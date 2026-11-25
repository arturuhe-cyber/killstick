package net.example.killstick.mixin;

import net.example.killstick.KillStickClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class PlayerMixin {

    /** ЛКМ по игроку предметом с триггером "attack" -> выполнить команды. */
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void killstick$onDoAttack(CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (client.player == null) return;

        KillStickClient.Profile profile = KillStickClient.getProfile(KillStickClient.heldItemId(client.player));
        if (profile == null || !profile.isAttack()) return;

        String target = KillStickClient.targetPlayerName(client);
        if (target != null) {
            KillStickClient.executeCommands(profile.commands, target);
            cir.setReturnValue(true);
        }
    }

    /**
     * ПКМ:
     *  - триггер "use": команды выполняются, когда вы нажимаете ПКМ, глядя на игрока;
     *  - триггер "hit": ПКМ работает как обычно (бросок), команды сработают при попадании;
     *  - триггер "attack": ПКМ открывает настройки предмета (как раньше у палки).
     */
    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void killstick$onDoItemUse(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (client.player == null) return;

        String itemId = KillStickClient.heldItemId(client.player);
        KillStickClient.Profile profile = KillStickClient.getProfile(itemId);
        if (profile == null) return;

        if (profile.isHit()) {
            return; // срабатывает, когда брошенный предмет попадает в игрока
        }

        if (profile.isUse()) {
            String target = KillStickClient.targetPlayerNameRanged(client, KillStickClient.useRange());
            if (target != null) {
                KillStickClient.executeCommands(profile.commands, target);
            } else {
                KillStickClient.hint(client, "No player in sight");
            }
            // действие предмета (натягивание трезубца) не отменяем
        } else {
            KillStickClient.openScreen(client, itemId);
            ci.cancel();
        }
    }
}
