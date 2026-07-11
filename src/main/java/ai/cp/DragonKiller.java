package ai.cp;

import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DragonKiller implements ModInitializer {
	public static final String MOD_ID = "dragonkiller";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("DragonKiller RL Environment initializing...");
	}

	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}
}
