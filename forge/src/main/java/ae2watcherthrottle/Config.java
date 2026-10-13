package ae2watcherthrottle;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
   public static final ForgeConfigSpec SPEC;
   private static final ForgeConfigSpec.IntValue WATCHER_UPDATE_INTERVAL;

   static {
      ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
      WATCHER_UPDATE_INTERVAL = builder
         .comment("How often, in ticks, AE2 rebuilds a network's item list for storage watchers",
            "(Level Emitters, Storage/Conversion Monitors, ME Requesters, threshold and tag emitters).",
            "AE2 itself does this every tick while a network has any watcher. 1 = AE2's default behaviour.",
            "Terminals and crafting calculations still read fresh amounts whenever they ask.")
         .defineInRange("watcherUpdateInterval", 10, 1, 200);
      SPEC = builder.build();
   }

   private Config() {
   }

   public static int interval() {
      return SPEC.isLoaded() ? WATCHER_UPDATE_INTERVAL.get() : 1;
   }
}
