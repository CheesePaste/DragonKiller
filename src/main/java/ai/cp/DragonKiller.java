package ai.cp;

import ai.cp.config.RLConfig;
import ai.cp.config.TickRateHelper;
import ai.cp.rl.RLTickHandler;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DragonKiller implements ModInitializer {
	public static final String MOD_ID = "dragonkiller";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("DragonKiller RL Environment initializing...");
		LOGGER.info("Phase: {} (IS_PHASE_2={}, rlphase={})",
			RLConfig.IS_PHASE_2 ? "PHASE 2" : "PHASE 1",
			RLConfig.IS_PHASE_2,
			System.getProperty("rlphase", "default_p1"));

		// Set target TPS from system property if specified
		int initialTps = Integer.getInteger("rltps", 0);
		if (initialTps > 0) {
			TickRateHelper.setTargetTps(initialTps);
			LOGGER.info("Forcing target TPS to: {}", initialTps);
		}

		registerCommands();
	}

	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			// /tps — show or set TPS
			dispatcher.register(CommandManager.literal("tps")
				.executes(ctx -> {
					int tps = TickRateHelper.getTps();
					float mspt = TickRateHelper.getMspt();
					long interval = TickRateHelper.getIntervalMs();
					boolean locked = TickRateHelper.getForcedIntervalMs() > 0;
					ctx.getSource().sendFeedback(
						() -> Text.literal(String.format(
							"%s §e%d  §6[MSPT] §e%.1fms  §6[Interval] §e%dms",
							locked ? "§c[LOCKED]" : "§6[TPS]", tps, mspt, interval)),
						false);
					return 1;
				})
				.then(CommandManager.argument("tps", IntegerArgumentType.integer(1, 500))
					.executes(ctx -> {
						int target = IntegerArgumentType.getInteger(ctx, "tps");
						TickRateHelper.setTargetTps(target);
						ctx.getSource().sendFeedback(() ->
							Text.literal(String.format("§6[TPS] §fLocked to §e%d TPS", target)),
							false);
						return 1;
					}))
				.then(CommandManager.literal("auto")
					.executes(ctx -> {
						TickRateHelper.setTargetTps(0);
						ctx.getSource().sendFeedback(() ->
							Text.literal("§6[TPS] §fAdaptive mode"),
							false);
						return 1;
					})));

			// /gm <mode> — set game mode: spectator / creative / survival
			dispatcher.register(CommandManager.literal("gm")
				.requires(src -> src.hasPermissionLevel(0))
				.then(CommandManager.argument("mode", StringArgumentType.word())
					.executes(ctx -> {
						ServerPlayerEntity player = ctx.getSource().getPlayer();
						if (player == null) return 0;
						String modeName = StringArgumentType.getString(ctx, "mode");
						String mode = RLTickHandler.setPlayerGameMode(player.getUuid(), modeName);
						ctx.getSource().sendFeedback(() ->
							Text.literal("§6[GM] §fSwitched to " + mode),
							false);
						return 1;
					}))
				.executes(ctx -> {
					ServerPlayerEntity player = ctx.getSource().getPlayer();
					if (player == null) return 0;
					int current = RLTickHandler.getPlayerGameMode(player.getUuid());
					String name = current == 1 ? "Creative" : current == 2 ? "Survival" : "Spectator";
					String color = current == 1 ? "§a" : current == 2 ? "§e" : "§7";
					ctx.getSource().sendFeedback(() ->
						Text.literal("§6[GM] §fCurrent: " + color + name),
						false);
					return 1;
				}));

			// /watch — toggle auto-spectating RLBot
			dispatcher.register(CommandManager.literal("watch")
				.requires(src -> src.hasPermissionLevel(0))
				.executes(ctx -> {
					ServerPlayerEntity player = ctx.getSource().getPlayer();
					if (player == null) return 0;
					boolean active = RLTickHandler.toggleAutoSpectate(player.getUuid());
					if (active) {
						player.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
						ServerPlayerEntity bot = RLTickHandler.getBotPlayer();
						if (bot != null && !bot.isRemoved()) {
							player.setCameraEntity(bot);
						}
						ctx.getSource().sendFeedback(() ->
							Text.literal("§6[WATCH] §aAuto-spectating RLBot enabled."),
							false);
					} else {
						player.setCameraEntity(player);
						ctx.getSource().sendFeedback(() ->
							Text.literal("§6[WATCH] §cAuto-spectating RLBot disabled."),
							false);
					}
					return 1;
				}));
		});
	}
}
