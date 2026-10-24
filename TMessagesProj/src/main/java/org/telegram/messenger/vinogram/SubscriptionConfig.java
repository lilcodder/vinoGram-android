package org.telegram.messenger.vinogram;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class SubscriptionConfig {
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
    public static final int MAX_BODY = 4 * 1024 * 1024;
    public static final int MAX_NODES = 128;

    public static final class Parsed {
        public final JsonArray nodes = new JsonArray();
        public int skipped;
    }

    public static Parsed parse(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BODY) throw new IllegalArgumentException("subscription_size");
        String content = new String(bytes, StandardCharsets.UTF_8).trim();
        if (content.startsWith("\ufeff")) content = content.substring(1).trim();
        JsonArray candidates = new JsonArray();
        if (content.startsWith("{") || content.startsWith("[")) {
            JsonElement document = JsonParser.parseString(content);
            JsonArray profiles = document.isJsonArray() ? document.getAsJsonArray()
                    : document.getAsJsonObject().has("profiles") ? array(document.getAsJsonObject(), "profiles") : list(document);
            for (JsonElement profile : profiles) {
                try {
                    JsonObject object = profile.getAsJsonObject();
                    if (object.has("outbounds")) {
                        for (JsonElement entry : array(object, "outbounds")) {
                            JsonObject outbound = entry.getAsJsonObject();
                            String protocol = text(outbound, "protocol", "");
                            if (protocol.equals("freedom") || protocol.equals("blackhole") || protocol.equals("dns")) continue;
                            candidates.add(sanitize(outbound, text(object, "remarks", "")));
                        }
                    } else {
                        candidates.add(fromValues(text(object, "protocol", ""), text(object, "address", ""),
                                number(object, "port"), text(object, "secret", ""), text(object, "name", ""),
                                text(object, "transport", "tcp"), text(object, "security", "none"),
                                object(object, "options"), object(object, "options")));
                    }
                } catch (RuntimeException ignored) {
                    candidates.add(new JsonObject());
                }
            }
        } else {
            if (!content.contains("://")) content = new String(decode64(content), StandardCharsets.UTF_8);
            for (String row : content.split("\\r?\\n")) {
                row = row.trim();
                if (row.isEmpty() || row.startsWith("#")) continue;
                try {
                    candidates.add(fromUri(row));
                } catch (Exception ignored) {
                    candidates.add(new JsonObject());
                }
            }
        }
        Parsed result = new Parsed();
        Set<JsonObject> seen = new HashSet<>();
        for (JsonElement entry : candidates) {
            JsonObject node = entry.getAsJsonObject();
            if (node.size() == 0) { result.skipped++; continue; }
            JsonObject identity = node.deepCopy();
            identity.remove("name");
            if (seen.add(identity)) result.nodes.add(node);
            if (result.nodes.size() > MAX_NODES) throw new IllegalArgumentException("subscription_nodes");
        }
        if (result.nodes.size() == 0) throw new IllegalArgumentException("subscription_empty");
        return result;
    }

    private static JsonObject fromUri(String line) throws Exception {
        if (line.startsWith("vmess://")) {
            JsonObject v = JsonParser.parseString(new String(decode64(line.substring(8).split("#", 2)[0]), StandardCharsets.UTF_8)).getAsJsonObject();
            return fromValues("vmess", text(v, "add", ""), number(v, "port"), text(v, "id", ""),
                    text(v, "ps", ""), text(v, "net", "tcp"), text(v, "tls", "none"), v, v);
        }
        URI uri = new URI(line);
        String protocol = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = uri.getHost(), secret = uri.getRawUserInfo() == null ? "" : decode(uri.getRawUserInfo());
        int port = uri.getPort();
        if (protocol.equals("ss") && (host == null || port < 1)) {
            String value = line.substring(5).split("#", 2)[0];
            String decoded = new String(decode64(value), StandardCharsets.UTF_8);
            int at = decoded.lastIndexOf('@');
            if (at < 1) return new JsonObject();
            URI endpoint = new URI("socks://" + decoded.substring(at + 1));
            host = endpoint.getHost(); port = endpoint.getPort(); secret = decoded.substring(0, at);
        } else if (protocol.equals("ss") && !secret.contains(":")) {
            secret = new String(decode64(secret), StandardCharsets.UTF_8);
        }
        JsonObject options = new JsonObject();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                options.addProperty(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : "");
            }
        }
        if (options.has("plugin")) return new JsonObject();
        String name = uri.getRawFragment() == null ? "" : decode(uri.getRawFragment());
        return fromValues(protocol, host, port, secret, name, text(options, "type", "tcp"),
                text(options, "security", protocol.equals("trojan") ? "tls" : "none"), options, new JsonObject());
    }

    private static JsonObject fromValues(String protocol, String host, int port, String secret, String name,
                                         String network, String security, JsonObject options, JsonObject vmess) {
        if (host == null || host.isEmpty() || host.matches(".*\\s.*") || port < 1 || port > 65535) return new JsonObject();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (network.isEmpty() || network.equals("raw")) network = "tcp";
        if (security.isEmpty()) security = protocol.equals("trojan") ? "tls" : "none";
        JsonObject server = json("address", host, "port", port), settings = new JsonObject();
        switch (protocol) {
            case "vless": case "vmess":
                if (!validUuid(secret)) return new JsonObject();
                JsonObject user = json("id", secret);
                if (protocol.equals("vless")) {
                    user.addProperty("encryption", text(options, "encryption", "none"));
                    user.addProperty("flow", text(options, "flow", ""));
                } else {
                    user.addProperty("security", "auto"); user.addProperty("alterId", number(vmess, "aid"));
                }
                server.add("users", list(user)); settings.add("vnext", list(server)); break;
            case "trojan":
                if (secret.isEmpty()) return new JsonObject();
                server.addProperty("password", secret); settings.add("servers", list(server)); break;
            case "ss": case "shadowsocks":
                int colon = secret.indexOf(':');
                if (colon < 1 || colon == secret.length() - 1) return new JsonObject();
                protocol = "shadowsocks";
                server.addProperty("method", secret.substring(0, colon)); server.addProperty("password", secret.substring(colon + 1));
                settings.add("servers", list(server)); break;
            case "socks": case "socks5":
                protocol = "socks";
                if (!secret.isEmpty()) {
                    String[] parts = secret.split(":", 2);
                    server.add("users", list(json("user", parts[0], "pass", parts.length == 2 ? parts[1] : "")));
                }
                settings.add("servers", list(server)); break;
            default: return new JsonObject();
        }
        JsonObject stream = json("network", network, "security", security);
        if (network.equals("grpc")) {
            stream.add("grpcSettings", json("serviceName", text(options, "serviceName", "")));
        } else if (network.equals("ws") || network.equals("httpupgrade") || network.equals("xhttp")) {
            JsonObject details = json("path", text(options, "path", "/"));
            if (!text(options, "host", "").isEmpty()) {
                if (network.equals("ws")) details.add("headers", json("Host", text(options, "host", "")));
                else details.addProperty("host", text(options, "host", ""));
            }
            if (network.equals("xhttp") && options.has("mode")) details.add("mode", options.get("mode"));
            stream.add(network + "Settings", details);
        }
        if (security.equals("tls") || security.equals("reality")) {
            JsonObject tls = json("serverName", text(options, "sni", host));
            if (options.has("fp")) tls.add("fingerprint", options.get("fp"));
            if (options.has("alpn")) tls.add("alpn", list((Object[]) text(options, "alpn", "").split(",")));
            if (security.equals("reality")) {
                tls.addProperty("publicKey", text(options, "pbk", ""));
                tls.addProperty("shortId", text(options, "sid", ""));
                tls.addProperty("spiderX", text(options, "spx", ""));
                if (!options.has("fp")) tls.addProperty("fingerprint", "chrome");
            }
            stream.add(security + "Settings", tls);
        }
        return sanitize(json("protocol", protocol, "settings", settings, "streamSettings", stream), name);
    }

    private static JsonObject sanitize(JsonObject outbound, String name) {
        String protocol = text(outbound, "protocol", "");
        boolean next = protocol.equals("vless") || protocol.equals("vmess");
        if (!next && !protocol.equals("socks") && !protocol.equals("trojan") && !protocol.equals("shadowsocks")) return new JsonObject();
        if (outbound.has("proxySettings")) return new JsonObject();
        JsonArray servers = array(object(outbound, "settings"), next ? "vnext" : "servers"), clean = new JsonArray();
        if (servers.size() == 0) return new JsonObject();
        for (JsonElement element : servers) {
            JsonObject server = element.getAsJsonObject();
            if (text(server, "address", "").isEmpty() || text(server, "address", "").matches(".*\\s.*")
                    || number(server, "port") < 1 || number(server, "port") > 65535) return new JsonObject();
            JsonObject value = select(server, "address", "port");
            if (next || protocol.equals("socks")) {
                JsonArray users = array(server, "users"), cleanUsers = new JsonArray();
                if (next && users.size() == 0) return new JsonObject();
                for (JsonElement entry : users) {
                    JsonObject user = entry.getAsJsonObject();
                    if (next && !validUuid(text(user, "id", ""))) return new JsonObject();
                    cleanUsers.add(next ? select(user, "id", "encryption", "flow", "security", "alterId") : select(user, "user", "pass"));
                }
                if (users.size() != 0) value.add("users", cleanUsers);
            } else {
                if (text(server, "password", "").isEmpty()) return new JsonObject();
                value.add("password", server.get("password"));
                if (protocol.equals("shadowsocks")) value.addProperty("method", text(server, "method", ""));
            }
            clean.add(value);
        }
        JsonObject source = object(outbound, "streamSettings");
        if (!text(object(source, "sockopt"), "dialerProxy", "").isEmpty()) return new JsonObject();
        String network = text(source, "network", "tcp"), security = text(source, "security", "none");
        if (network.equals("raw")) network = "tcp";
        if (!list("tcp", "ws", "grpc", "xhttp", "httpupgrade").contains(new com.google.gson.JsonPrimitive(network))) return new JsonObject();
        JsonObject stream = json("network", network, "security", security);
        if (security.equals("tls")) stream.add("tlsSettings", select(object(source, "tlsSettings"), "serverName", "alpn", "fingerprint"));
        else if (security.equals("reality")) {
            JsonObject reality = select(object(source, "realitySettings"), "serverName", "publicKey", "password", "shortId", "fingerprint", "spiderX", "mldsa65Verify");
            if (!protocol.equals("vless") || (text(reality, "publicKey", "").isEmpty() && text(reality, "password", "").isEmpty())) return new JsonObject();
            stream.add("realitySettings", reality);
        } else if (!security.equals("none")) return new JsonObject();
        if (network.equals("grpc")) stream.add("grpcSettings", select(object(source, "grpcSettings"), "serviceName", "authority", "multiMode", "mode"));
        else if (!network.equals("tcp")) stream.add(network + "Settings", select(object(source, network + "Settings"), "path", "host", "headers", "mode"));
        if (name.isEmpty()) name = text(clean.get(0).getAsJsonObject(), "address", "");
        return json("name", name.substring(0, Math.min(128, name.length())), "protocol", protocol,
                "settings", json(next ? "vnext" : "servers", clean), "streamSettings", stream);
    }

    public static JsonObject config(JsonArray nodes, int socks, int metrics, String user, String password) {
        JsonArray outbounds = list(json("tag", "blocked", "protocol", "blackhole"));
        for (int i = 0; i < nodes.size(); ++i) {
            JsonObject node = nodes.get(i).getAsJsonObject().deepCopy(); node.remove("name");
            node.addProperty("tag", "node-" + i); outbounds.add(node);
        }
        return json("log", json("loglevel", "none"),
                "inbounds", list(json("tag", "vinogram", "listen", "127.0.0.1", "port", socks, "protocol", "socks",
                        "settings", json("auth", "password", "udp", true, "accounts", list(json("user", user, "pass", password))))),
                "outbounds", outbounds, "metrics", json("tag", "metrics", "listen", "127.0.0.1:" + metrics),
                "observatory", json("subjectSelector", list("node-"), "probeUrl", "https://cp.cloudflare.com/generate_204", "probeInterval", "30s", "enableConcurrency", true),
                "routing", json("domainStrategy", "AsIs", "rules", list(json("type", "field", "inboundTag", list("vinogram"), "balancerTag", "automatic")),
                        "balancers", list(json("tag", "automatic", "selector", list("node-"), "fallbackTag", "blocked", "strategy", json("type", "leastPing")))));
    }

    public static JsonObject json(Object... pairs) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) object.add((String) pairs[i], element(pairs[i + 1]));
        return object;
    }
    private static JsonElement element(Object value) { return value instanceof JsonElement ? (JsonElement) value : GSON.toJsonTree(value); }
    public static JsonArray list(Object... values) { JsonArray array = new JsonArray(); for (Object value : values) array.add(element(value)); return array; }
    public static String text(JsonObject o, String k, String fallback) { return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : fallback; }
    private static int number(JsonObject o, String k) { try { return o.get(k).getAsInt(); } catch (RuntimeException ignored) { return 0; } }
    private static JsonObject object(JsonObject o, String k) { return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject(); }
    private static JsonArray array(JsonObject o, String k) { return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray(); }
    private static JsonObject select(JsonObject source, String... keys) { JsonObject result = new JsonObject(); for (String key : keys) if (source.has(key)) result.add(key, source.get(key).deepCopy()); return result; }
    private static boolean validUuid(String value) { try { return UUID.fromString(value).toString().equalsIgnoreCase(value); } catch (RuntimeException ignored) { return false; } }
    private static String decode(String value) throws Exception { return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8"); }
    private static byte[] decode64(String value) {
        String input = value.replaceAll("\\s", "").replace('-', '+').replace('_', '/');
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int accumulator = 0, bits = 0;
        boolean padding = false;
        for (char c : input.toCharArray()) {
            if (c == '=') { padding = true; continue; }
            int digit = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".indexOf(c);
            if (digit < 0 || padding) throw new IllegalArgumentException("base64");
            accumulator = (accumulator << 6) | digit; bits += 6;
            if (bits >= 8) { bits -= 8; output.write((accumulator >> bits) & 255); }
        }
        if (bits == 6 || (bits != 0 && (accumulator & ((1 << bits) - 1)) != 0)) throw new IllegalArgumentException("base64");
        return output.toByteArray();
    }
}
