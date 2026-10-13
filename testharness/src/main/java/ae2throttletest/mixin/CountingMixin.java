package ae2throttletest.mixin;

import ae2throttletest.Ae2ThrottleTest;
import appeng.me.service.StorageService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = StorageService.class, remap = false)
public abstract class CountingMixin {
   @Inject(method = "updateCachedStacks", at = @At("HEAD"))
   private void ae2throttletest$count(CallbackInfo ci) {
      Ae2ThrottleTest.REBUILDS.incrementAndGet();
   }
}
