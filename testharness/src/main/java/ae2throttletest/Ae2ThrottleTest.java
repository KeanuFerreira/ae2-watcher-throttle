package ae2throttletest;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.parts.IPartHost;
import appeng.api.parts.PartHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.util.AEColor;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.core.definitions.AEParts;
import appeng.parts.automation.StorageLevelEmitterPart;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Commands for run_tests.py. A test network is a drive with a 1k cell at pos, a creative energy
 * cell east of it, and a cable west of it with a Level Emitter watching diamonds (threshold 1).
 */
@Mod("ae2throttletest")
public class Ae2ThrottleTest {
   public static final AtomicLong REBUILDS = new AtomicLong();
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final List<BooleanSupplier> TASKS = new ArrayList<>();
   private static final AEItemKey DIAMOND = AEItemKey.of(Items.DIAMOND);

   public Ae2ThrottleTest() {
      MinecraftForge.EVENT_BUS.addListener(this::commands);
      MinecraftForge.EVENT_BUS.addListener(this::tick);
   }

   private static void out(String s) {
      LOGGER.info("ATT " + s);
   }

   private static int run(CommandContext<CommandSourceStack> c, Body b) {
      try {
         b.run(c);
      } catch (Exception e) {
         LOGGER.error("ATT ERROR " + e, e);
      }
      return 1;
   }

   interface Body {
      void run(CommandContext<CommandSourceStack> c) throws Exception;
   }

   private void commands(RegisterCommandsEvent event) {
      event.getDispatcher().register(Commands.literal("att").requires(s -> s.hasPermission(4))
         .then(Commands.literal("setup").then(Commands.argument("pos", BlockPosArgument.blockPos())
            .then(Commands.argument("emitter", IntegerArgumentType.integer(0, 1)).executes(c -> run(c, Ae2ThrottleTest::setup)))))
         .then(Commands.literal("rate").then(Commands.argument("ticks", IntegerArgumentType.integer(1, 2000)).executes(c -> run(c, Ae2ThrottleTest::rate))))
         .then(Commands.literal("latency").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> run(c, Ae2ThrottleTest::latency))))
         .then(Commands.literal("dump").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> run(c, Ae2ThrottleTest::dump))))
         .then(Commands.literal("fresh").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> run(c, Ae2ThrottleTest::fresh)))));
   }

   private static IGrid grid(ServerLevel level, BlockPos pos) {
      DriveBlockEntity drive = (DriveBlockEntity) level.getBlockEntity(pos);
      return drive.getMainNode().getGrid();
   }

   private static StorageLevelEmitterPart emitter(ServerLevel level, BlockPos pos) {
      return PartHelper.getPart(AEParts.LEVEL_EMITTER.asItem(), level, pos.west(), Direction.WEST);
   }

   private static void setup(CommandContext<CommandSourceStack> c) throws Exception {
      ServerLevel level = c.getSource().getLevel();
      BlockPos pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
      boolean withEmitter = IntegerArgumentType.getInteger(c, "emitter") == 1;
      level.setBlockAndUpdate(pos, AEBlocks.DRIVE.block().defaultBlockState());
      level.setBlockAndUpdate(pos.east(), AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
      DriveBlockEntity drive = (DriveBlockEntity) level.getBlockEntity(pos);
      drive.getInternalInventory().setItemDirect(0, new ItemStack(AEItems.ITEM_CELL_1K.asItem()));
      if (withEmitter) {
         IPartHost host = PartHelper.getOrPlacePartHost(level, pos.west(), true, null);
         host.addPart(AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT), null, null);
         StorageLevelEmitterPart part = host.addPart(AEParts.LEVEL_EMITTER.asItem(), Direction.WEST, null);
         part.getConfig().setStack(0, new GenericStack(DIAMOND, 1));
         part.setReportingValue(1);
      }
      out("setup " + pos.toShortString() + " emitter=" + withEmitter);
   }

   /** Rebuilds of the cached item list per tick, over the given number of ticks. */
   private static void rate(CommandContext<CommandSourceStack> c) {
      int ticks = IntegerArgumentType.getInteger(c, "ticks");
      long start = REBUILDS.get();
      int[] left = {ticks};
      TASKS.add(() -> {
         if (--left[0] > 0) {
            return false;
         }
         long n = REBUILDS.get() - start;
         out("rate rebuilds=" + n + " ticks=" + ticks + " perTick=" + String.format("%.3f", n / (double) ticks));
         return true;
      });
   }

   /** Inserts a diamond into an empty network and counts ticks until the Level Emitter turns on, then the same for off. */
   private static void latency(CommandContext<CommandSourceStack> c) throws Exception {
      ServerLevel level = c.getSource().getLevel();
      BlockPos pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
      IGrid grid = grid(level, pos);
      StorageLevelEmitterPart part = emitter(level, pos);
      int before = part.isProvidingWeakPower();
      long inserted = grid.getStorageService().getInventory().insert(DIAMOND, 1, Actionable.MODULATE, IActionSource.empty());
      int[] ticks = {0};
      boolean[] phaseOff = {false};
      TASKS.add(() -> {
         ticks[0]++;
         int power = part.isProvidingWeakPower();
         if (!phaseOff[0]) {
            if (power == before && ticks[0] < 400) {
               return false;
            }
            out("latency on ticks=" + ticks[0] + " power " + before + "->" + power + " inserted=" + inserted);
            grid.getStorageService().getInventory().extract(DIAMOND, 1, Actionable.MODULATE, IActionSource.empty());
            phaseOff[0] = true;
            ticks[0] = 0;
            return false;
         }
         if (power != before && ticks[0] < 400) {
            return false;
         }
         out("latency off ticks=" + ticks[0] + " power=" + power);
         return true;
      });
   }

   private static void dump(CommandContext<CommandSourceStack> c) throws Exception {
      ServerLevel level = c.getSource().getLevel();
      BlockPos pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
      DriveBlockEntity drive = (DriveBlockEntity) level.getBlockEntity(pos);
      IGrid grid = drive.getMainNode().getGrid();
      appeng.api.stacks.KeyCounter all = grid.getStorageService().getInventory().getAvailableStacks();
      out("dump nodes=" + grid.size() + " cell0=" + drive.getInternalInventory().getStackInSlot(0) + " cellInv=" + drive.getCellInventory(0)
         + " status=" + drive.getCellStatus(0) + " keys=" + all.size() + " diamonds=" + all.get(DIAMOND) + " cached=" + grid.getStorageService().getCachedInventory().get(DIAMOND)
         + " active=" + drive.getMainNode().isActive() + " online=" + drive.getMainNode().isOnline());
   }

   /** Inserts a diamond and reads the cached inventory in the same tick: on-demand readers must see it at once. */
   private static void fresh(CommandContext<CommandSourceStack> c) throws Exception {
      ServerLevel level = c.getSource().getLevel();
      IGrid grid = grid(level, BlockPosArgument.getLoadedBlockPos(c, "pos"));
      long before = grid.getStorageService().getCachedInventory().get(DIAMOND);
      grid.getStorageService().getInventory().insert(DIAMOND, 5, Actionable.MODULATE, IActionSource.empty());
      // The command runs mid-tick; read two ticks later, well inside a 10-tick throttle window,
      // so the value can only be fresh if skipped ticks leave the cache marked stale.
      int[] wait = {2};
      TASKS.add(() -> {
         if (wait[0]-- > 0) {
            return false;
         }
         long after = grid.getStorageService().getCachedInventory().get(DIAMOND);
         out("fresh before=" + before + " after=" + after);
         grid.getStorageService().getInventory().extract(DIAMOND, 5, Actionable.MODULATE, IActionSource.empty());
         return true;
      });
   }

   private void tick(TickEvent.ServerTickEvent event) {
      if (event.phase != TickEvent.Phase.END) {
         return;
      }
      for (Iterator<BooleanSupplier> it = TASKS.iterator(); it.hasNext(); ) {
         if (it.next().getAsBoolean()) {
            it.remove();
         }
      }
   }
}
