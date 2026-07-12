package ai.cp;

import ai.cp.config.RLConfig;
import ai.cp.config.TickRateHelper;
import ai.cp.rl.RLTickHandler;
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
		registerCommands();
	}

	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("tps").executes(ctx -> {
				int tps = TickRateHelper.getTps();
				float mspt = TickRateHelper.getMspt();
				long interval = TickRateHelper.getIntervalMs();
				ctx.getSource().sendFeedback(
					() -> Text.literal(String.format(
						"§6[TPS] §e%d  §6[MSPT] §e%.1fms  §6[Interval] §e%dms",
						tps, mspt, interval)),
					false);
				return 1;
			}));

			// /gm — toggle spectator ↔ creative for player inspection
			dispatcher.register(CommandManager.literal("gm")
				.requires(src -> src.hasPermissionLevel(0))
				.executes(ctx -> {
					ServerPlayerEntity player = ctx.getSource().getPlayer();
					if (player == null) return 0;
					boolean nowCreative = RLTickHandler.toggleCreativeMode(player.getUuid());
					ctx.getSource().sendFeedback(() ->
						Text.literal(nowCreative
							? "§a[GM] Creative mode — fly around to inspect"
							: "§7[GM] Spectator mode"),
						false);
					return 1;
				}));
		});
	}
}
