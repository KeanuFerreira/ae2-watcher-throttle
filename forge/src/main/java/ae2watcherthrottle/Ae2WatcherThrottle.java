package ae2watcherthrottle;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(Ae2WatcherThrottle.MODID)
public class Ae2WatcherThrottle {
   public static final String MODID = "ae2watcherthrottle";
   private static final Logger LOGGER = LogUtils.getLogger();

   public Ae2WatcherThrottle() {
      ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
      MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
         LOGGER.info("AE2 storage watchers are updated every {} tick(s)", Config.interval()));
   }
}
