package ai.cp.rl.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class Protocol {
    public static final String TYPE_RESET = "reset";
    public static final String TYPE_STEP = "step";
    public static final String TYPE_OBS = "obs";
    public static final String TYPE_CONFIG = "config";
    public static final String TYPE_CLOSE = "close";

    private static final Gson GSON = new Gson();

    public static String createObsMessage(JsonObject data, double reward, boolean done) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", TYPE_OBS);
        msg.add("data", data);
        msg.addProperty("reward", reward);
        msg.addProperty("done", done);
        return GSON.toJson(msg) + "\n";
    }

    public static JsonObject parseMessage(String json) {
        return GSON.fromJson(json, JsonObject.class);
    }

    public static String getType(JsonObject msg) {
        return msg.has("type") ? msg.get("type").getAsString() : "";
    }

    public static int getAction(JsonObject msg) {
        return msg.has("action") ? msg.get("action").getAsInt() : 0;
    }
}
