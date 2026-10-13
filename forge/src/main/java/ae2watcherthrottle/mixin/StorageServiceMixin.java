package ae2watcherthrottle.mixin;

import ae2watcherthrottle.Config;
import appeng.me.helpers.InterestManager;
import appeng.me.service.StorageService;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AE2 rebuilds a network's full item list at the end of every tick while any storage watcher is
 * registered, so it can tell watchers about changed amounts. This runs that rebuild only every
 * N ticks. On the skipped ticks the cache is marked stale instead, exactly as AE2 does when there
 * are no watchers, so anything that reads the cached inventory still gets fresh amounts.
 */
@Mixin(value = StorageService.class, remap = false)
public abstract class StorageServiceMixin {
   @Shadow
   @Final
   private InterestManager<?> interestManager;

   @Shadow
   private boolean cachedStacksNeedUpdate;

   /** Ticks since the last rebuild; starts at a per-network offset so networks don't all rebuild on the same tick. */
   @Unique
   private int ae2watcherthrottle$ticks = -1;

   @Inject(method = "onServerEndTick", at = @At("HEAD"), cancellable = true)
   private void ae2watcherthrottle$throttle(CallbackInfo ci) {
      int interval = Config.interval();
      if (interval <= 1 || this.interestManager.isEmpty()) {
         return;
      }
      if (this.ae2watcherthrottle$ticks < 0) {
         this.ae2watcherthrottle$ticks = Math.floorMod(System.identityHashCode(this), interval);
      }
      if (++this.ae2watcherthrottle$ticks >= interval) {
         this.ae2watcherthrottle$ticks = 0;
         return;
      }
      this.cachedStacksNeedUpdate = true;
      ci.cancel();
   }
}
