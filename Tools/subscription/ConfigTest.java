import org.telegram.messenger.vinogram.SubscriptionConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class ConfigTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static SubscriptionConfig.Parsed parse(String value) { return SubscriptionConfig.parse(value.getBytes(StandardCharsets.UTF_8)); }
    private static void rejects(String value) {
        try { parse(value); throw new AssertionError("Accepted invalid input"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--config")) {
            System.out.println(SubscriptionConfig.config(SubscriptionConfig.parse(Files.readAllBytes(Paths.get(args[1]))).nodes, 10808, 10809, "test", "test"));
            return;
        }
        String uuid = "11111111-1111-4111-8111-111111111111";
        String vless = "vless://" + uuid + "@example.invalid:443?security=tls&type=ws&path=%2Fedge&host=cdn.invalid#Alpha";
        JsonArray nodes = parse(vless).nodes;
        check(nodes.size() == 1, "VLESS import");
        check(nodes.equals(parse(Base64.getEncoder().encodeToString(vless.getBytes(StandardCharsets.UTF_8))).nodes), "Base64 body");
        check(nodes.equals(parse("\ufeff" + vless).nodes), "UTF-8 BOM");
        check(parse(vless + "\n" + vless + "\ninvalid://bad").nodes.size() == 1, "Deduplication");
        check(parse(vless + "\ninvalid://bad").skipped == 1, "Unsupported count");
        JsonObject stream = nodes.get(0).getAsJsonObject().getAsJsonObject("streamSettings");
        check(stream.getAsJsonObject("wsSettings").get("path").getAsString().equals("/edge"), "Percent-decoded path");
        rejects("<html>error</html>");
        rejects("vless://bad@host.invalid:443");
        rejects("vless://" + uuid + "@host.invalid:443?security=reality");
        rejects("vless://" + uuid + "@host.invalid:443?type=unsupported");
        rejects("A".repeat(SubscriptionConfig.MAX_BODY + 1));
        check(parse("socks5://user:pass@[::1]:1080#IPv6").nodes.size() == 1, "IPv6");
        JsonObject trojan = parse("trojan://a%3Ab+plus@host.invalid:443").nodes.get(0).getAsJsonObject();
        check(trojan.getAsJsonObject("settings").getAsJsonArray("servers").get(0).getAsJsonObject().get("password").getAsString().equals("a:b+plus"), "Password escaping");
        String ssSecret = Base64.getEncoder().encodeToString("aes-128-gcm:secret".getBytes(StandardCharsets.UTF_8));
        check(parse("ss://" + ssSecret + "@host.invalid:443").nodes.size() == 1, "SIP002");
        String legacy = Base64.getEncoder().encodeToString("aes-128-gcm:secret@host.invalid:443".getBytes(StandardCharsets.UTF_8));
        check(parse("ss://" + legacy).nodes.size() == 1, "Legacy Shadowsocks");
        JsonObject vmess = SubscriptionConfig.json("add", "host.invalid", "port", "443", "id", uuid, "net", "tcp", "tls", "tls");
        check(parse("vmess://" + Base64.getEncoder().encodeToString(vmess.toString().getBytes(StandardCharsets.UTF_8))).nodes.size() == 1, "VMess");
        JsonObject aura = SubscriptionConfig.json("protocol", "trojan", "address", "host.invalid", "port", 443, "secret", "secret", "security", "tls");
        check(parse(SubscriptionConfig.json("profiles", SubscriptionConfig.list(aura)).toString()).nodes.size() == 1, "AURA export");
        check(parse(aura.toString()).nodes.size() == 1, "Single AURA profile");
        JsonObject outbound = nodes.get(0).getAsJsonObject().deepCopy(); outbound.remove("name"); outbound.addProperty("tag", "proxy");
        JsonObject xray = SubscriptionConfig.json("remarks", "JSON node", "inbounds", SubscriptionConfig.list(SubscriptionConfig.json("listen", "0.0.0.0")),
                "outbounds", SubscriptionConfig.list(outbound, SubscriptionConfig.json("protocol", "freedom")));
        JsonArray imported = parse(xray.toString()).nodes;
        check(imported.size() == 1 && parse(SubscriptionConfig.list(xray, xray).toString()).nodes.size() == 1, "Xray JSON import and deduplication");
        outbound.add("proxySettings", SubscriptionConfig.json("tag", "direct"));
        rejects(SubscriptionConfig.json("outbounds", SubscriptionConfig.list(outbound)).toString());
        outbound.remove("proxySettings");
        outbound.getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings").add("certificates", SubscriptionConfig.list(SubscriptionConfig.json("keyFile", "private.key")));
        JsonObject safe = parse(SubscriptionConfig.json("outbounds", SubscriptionConfig.list(outbound)).toString()).nodes.get(0).getAsJsonObject();
        check(!safe.getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings").has("certificates"), "Local file settings excluded");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= 128; i++) many.append("socks5://127.0.0.1:").append(10000 + i).append('\n');
        rejects(many.toString());
        JsonObject config = SubscriptionConfig.config(imported, 10808, 10809, "local", "password");
        JsonObject inbound = config.getAsJsonArray("inbounds").get(0).getAsJsonObject();
        check(inbound.get("listen").getAsString().equals("127.0.0.1"), "Loopback only");
        check(inbound.getAsJsonObject("settings").get("auth").getAsString().equals("password"), "Authentication required");
        for (var entry : config.getAsJsonArray("outbounds")) check(!entry.getAsJsonObject().get("protocol").getAsString().equals("freedom"), "No direct fallback");
        check(config.getAsJsonObject("routing").getAsJsonArray("balancers").get(0).getAsJsonObject().get("fallbackTag").getAsString().equals("blocked"), "Fail-closed fallback");
        System.out.println("Subscription parser, imports and routing checks passed.");
    }
}
