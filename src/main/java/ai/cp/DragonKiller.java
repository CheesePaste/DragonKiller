package ai.cp;

import ai.cp.config.TickRateHelper;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
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
		});
	}
}
