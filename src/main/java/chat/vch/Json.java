package chat.vch;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;

public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private Json() {}
    public static JsonObject obj(Object... values) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < values.length; i += 2) o.add(values[i].toString(), GSON.toJsonTree(values[i + 1]));
        return o;
    }
    public static String str(JsonObject o, String key) { return o.has(key) ? o.get(key).getAsString() : ""; }
    public static long num(JsonObject o, String key, long fallback) { return o.has(key) ? o.get(key).getAsLong() : fallback; }
    public static byte[] bytes(Object o) { return GSON.toJson(o).getBytes(StandardCharsets.UTF_8); }
    public static JsonObject parse(byte[] b) { return JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject(); }
}
