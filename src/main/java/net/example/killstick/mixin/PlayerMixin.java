package net.example.killstick.mixin;

import net.example.killstick.KillStickClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class PlayerMixin {

    /** ЛКМ палкой по игроку -> выполнить команды. */
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void killstick$onDoAttack(CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (client.player == null) return;
        if (!client.player.getStackInHand(Hand.MAIN_HAND).isOf(Items.STICK)) return;

        HitResult hit = client.crosshairTarget;
        if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
            Entity target = ((EntityHitResult) hit).getEntity();
            if (target instanceof PlayerEntity) {
                KillStickClient.executeCommands(target.getName().getString());
                cir.setReturnValue(true);
            }
        }
    }

    /** ПКМ палкой -> открыть настройки. */
    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void killstick$onDoItemUse(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (client.player == null) return;
        if (client.player.getStackInHand(Hand.MAIN_HAND).isOf(Items.STICK)) {
            KillStickClient.openScreen();
            ci.cancel();
        }
    }
}
