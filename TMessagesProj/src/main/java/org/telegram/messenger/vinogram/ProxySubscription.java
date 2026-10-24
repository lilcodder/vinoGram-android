package org.telegram.messenger.vinogram;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ProxySubscription {
    private static final ProxySubscription INSTANCE = new ProxySubscription();
    private static final String[] PROXY_KEYS = {"proxy_type", "proxy_ip", "proxy_port", "proxy_user", "proxy_pass", "proxy_secret", "proxy_enabled"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final SecureRandom random = new SecureRandom();
    private SharedPreferences preferences;
    private JsonArray nodes = new JsonArray();
    private Process process;
    private String url = "";
    private String status = "";
    private boolean initialized, enabled, busy, applying;
    private int refreshGeneration, cacheGeneration, engineGeneration, metricsPort, retryDelay = 1000;
    private ProxySettings local;

    public static ProxySubscription get() { return INSTANCE; }
    public boolean enabled() { return enabled; }
    public boolean busy() { return busy; }
    public boolean hasServers() { return nodes.size() != 0; }
    public String url() { return url; }
    public String status() { return status; }
    public void addListener(Runnable listener) { listeners.addIfAbsent(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }

    public void init() {
        if (initialized) return;
        initialized = true;
        preferences = ApplicationLoader.applicationContext.getSharedPreferences("vinogram_subscription", 0);
        url = preferences.getString("url", "");
        enabled = preferences.getBoolean("enabled", false);
        final int generation = cacheGeneration;
        worker.execute(() -> {
            JsonArray loaded = new JsonArray();
            try (InputStream input = cache().openRead()) {
                loaded = SubscriptionConfig.parse(read(input, SubscriptionConfig.MAX_BODY)).nodes;
            } catch (Exception ignored) { }
            JsonArray saved = loaded;
            main.post(() -> {
                if (generation != cacheGeneration) return;
                nodes = saved;
                if (enabled && nodes.size() != 0) start();
                else if (enabled) {
                    apply(ProxySettings.builder().setType(ProxySettings.Type.SOCKS5).setAddress("127.0.0.1").setPort(9).build());
                    status = string(R.string.VinoSubscriptionUpdateError);
                    if (!url.isEmpty()) refresh(url);
                }
                changed();
            });
        });
        main.postDelayed(hourly, 60 * 60 * 1000);
    }

    public void refresh(String value) {
        init();
        try {
            URL parsed = new URL(value.trim());
            if (!parsed.getProtocol().equals("https") && !parsed.getProtocol().equals("http")) throw new Exception();
            if (parsed.getHost().isEmpty()) throw new Exception();
        } catch (Exception ignored) {
            status = string(R.string.VinoSubscriptionUrlError); changed(); return;
        }
        final String candidateUrl = value.trim();
        final int generation = ++refreshGeneration;
        busy = true; status = string(R.string.VinoSubscriptionLoading); changed();
        worker.execute(() -> {
            try {
                byte[] body = download(new URL(candidateUrl), SubscriptionConfig.MAX_BODY);
                SubscriptionConfig.Parsed parsed = SubscriptionConfig.parse(body);
                int socks = port(), metrics = port();
                if (socks == metrics) throw new IllegalStateException();
                JsonObject config = SubscriptionConfig.config(parsed.nodes, socks, metrics, "validation", "validation");
                Process validator = launch(config, true);
                if (!waitUntilExit(validator, 15000)) { validator.destroy(); throw new IllegalStateException(); }
                if (validator.exitValue() != 0) throw new IllegalStateException();
                main.post(() -> {
                    if (generation != refreshGeneration) return;
                    // Keep the previous subscription until its replacement has passed Xray validation.
                    try {
                        FileOutputStream output = null;
                        AtomicFile cache = cache();
                        try {
                            output = cache.startWrite(); output.write(body); cache.finishWrite(output);
                        } catch (Exception error) {
                            if (output != null) cache.failWrite(output);
                            throw error;
                        }
                        url = candidateUrl; nodes = parsed.nodes;
                        preferences.edit().putString("url", url).apply();
                        status = string(R.string.VinoSubscriptionImported) + " " + nodes.size()
                                + (parsed.skipped == 0 ? "" : " · " + string(R.string.VinoSubscriptionSkipped) + " " + parsed.skipped);
                        if (enabled) start();
                    } catch (Exception ignored) {
                        status = string(R.string.VinoSubscriptionUpdateError);
                    }
                    busy = false; changed();
                });
            } catch (Exception ignored) {
                main.post(() -> {
                    if (generation != refreshGeneration) return;
                    busy = false; status = string(R.string.VinoSubscriptionUpdateError); changed();
                });
            }
        });
    }

    public void setEnabled(boolean value) {
        init();
        if (value == enabled || (value && nodes.size() == 0)) return;
        if (value) snapshot();
        enabled = value;
        preferences.edit().putBoolean("enabled", value).apply();
        if (value) start();
        else { stop(); restore(); status = string(R.string.VinoSubscriptionDisabled); }
        changed();
    }

    public void remove() {
        init(); ++refreshGeneration; ++cacheGeneration; busy = false;
        if (enabled) setEnabled(false);
        url = ""; nodes = new JsonArray(); cache().delete();
        preferences.edit().remove("url").apply(); status = ""; changed();
    }

    public void onProxyChange(boolean value, ProxySettings settings) {
        if (!enabled || applying || (value && settings != null && settings.equals(local))) return;
        // A deliberate proxy choice takes precedence over the subscription's temporary listener.
        enabled = false; preferences.edit().putBoolean("enabled", false).remove("previous").apply();
        stop(); status = string(R.string.VinoSubscriptionDisabled); changed();
    }

    private void start() {
        stop();
        final int generation = engineGeneration;
        final JsonArray selected = nodes.deepCopy();
        final String user = randomString(), password = randomString();
        apply(ProxySettings.builder().setType(ProxySettings.Type.SOCKS5).setAddress("127.0.0.1").setPort(9).build());
        status = string(R.string.VinoSubscriptionStarting); changed();
        worker.execute(() -> {
            Process candidate = null;
            try {
                int socks = port(), metrics = port();
                if (socks == metrics) throw new IllegalStateException();
                candidate = launch(SubscriptionConfig.config(selected, socks, metrics, user, password), false);
                final Process running = candidate;
                ProxySettings settings = ProxySettings.builder().setType(ProxySettings.Type.SOCKS5)
                        .setAddress("127.0.0.1").setPort(socks).setUser(user).setPassword(password).build();
                main.post(() -> {
                    if (!enabled || generation != engineGeneration) { running.destroy(); return; }
                    process = running; metricsPort = metrics; apply(settings);
                    main.postDelayed(poll, 3000);
                });
                Thread watcher = new Thread(() -> {
                    try { running.waitFor(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    main.post(() -> {
                        if (!enabled || generation != engineGeneration) return;
                        process = null; failed();
                    });
                }, "vinoGram-Xray");
                watcher.setDaemon(true); watcher.start();
            } catch (Exception ignored) {
                if (candidate != null) candidate.destroy();
                main.post(() -> { if (enabled && generation == engineGeneration) failed(); });
            }
        });
    }

    private void failed() {
        main.removeCallbacks(poll);
        status = string(R.string.VinoSubscriptionRestarting); changed();
        main.removeCallbacks(restart); main.postDelayed(restart, retryDelay);
        retryDelay = Math.min(30000, retryDelay * 2);
    }

    private void stop() {
        ++engineGeneration;
        main.removeCallbacks(restart); main.removeCallbacks(poll);
        if (process != null) process.destroy();
        process = null; metricsPort = 0;
    }

    private final Runnable restart = () -> { if (enabled) start(); };
    private final Runnable hourly = new Runnable() {
        @Override public void run() {
            if (!url.isEmpty() && !busy) refresh(url);
            main.postDelayed(this, 60 * 60 * 1000);
        }
    };
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!enabled || process == null) return;
            final int generation = engineGeneration, port = metricsPort;
            worker.execute(() -> {
                String message;
                try {
                    JsonObject root = JsonParser.parseString(new String(download(new URL("http://127.0.0.1:" + port + "/debug/vars"), 1024 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonElement observation = root.get("observatory");
                    JsonObject rows = observation != null && observation.isJsonObject() ? observation.getAsJsonObject() : new JsonObject();
                    int alive = 0;
                    for (Map.Entry<String, JsonElement> item : rows.entrySet()) {
                        JsonObject entry = item.getValue().getAsJsonObject();
                        if (entry.has("alive") && entry.get("alive").getAsBoolean()) alive++;
                    }
                    message = string(R.string.VinoSubscriptionAlive) + " " + alive + "/" + nodes.size();
                } catch (Exception ignored) {
                    message = string(R.string.VinoSubscriptionChecking);
                }
                final String result = message;
                main.post(() -> { if (enabled && generation == engineGeneration) { retryDelay = 1000; status = result; changed(); main.postDelayed(poll, 30000); } });
            });
        }
    };

    private void apply(ProxySettings settings) {
        applying = true;
        try {
            local = settings;
            SharedConfig.currentProxy = new SharedConfig.ProxyInfo(settings);
            SharedConfig.proxyRotationEnabled = false;
            SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
            settings.toSharedPreferences(editor); editor.putBoolean("proxy_enabled", true).apply();
            SharedConfig.saveConfig();
            ConnectionsManager.setProxySettings(true, settings);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        } finally { applying = false; }
    }

    private void snapshot() {
        SharedPreferences global = MessagesController.getGlobalMainSettings();
        Map<String, ?> values = global.getAll();
        JsonObject saved = new JsonObject();
        for (String key : PROXY_KEYS) if (values.containsKey(key)) saved.add(key, new com.google.gson.Gson().toJsonTree(values.get(key)));
        saved.addProperty("proxy_rotation_enabled", SharedConfig.proxyRotationEnabled);
        preferences.edit().putString("previous", saved.toString()).apply();
    }

    private void restore() {
        if (!preferences.contains("previous")) return;
        applying = true;
        try {
            JsonObject saved = JsonParser.parseString(preferences.getString("previous", "{}")).getAsJsonObject();
            SharedPreferences global = MessagesController.getGlobalMainSettings();
            SharedPreferences.Editor editor = global.edit();
            for (String key : PROXY_KEYS) {
                editor.remove(key);
                if (!saved.has(key)) continue;
                if (key.equals("proxy_enabled")) editor.putBoolean(key, saved.get(key).getAsBoolean());
                else if (key.equals("proxy_type") || key.equals("proxy_port")) editor.putInt(key, saved.get(key).getAsInt());
                else editor.putString(key, saved.get(key).getAsString());
            }
            SharedConfig.proxyRotationEnabled = saved.has("proxy_rotation_enabled") && saved.get("proxy_rotation_enabled").getAsBoolean();
            editor.commit();
            SharedConfig.saveConfig();
            ProxySettings settings = ProxySettings.fromSharedPreferences(global);
            SharedConfig.currentProxy = settings.isValid() ? new SharedConfig.ProxyInfo(settings) : null;
            for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) if (info.settings.equals(settings)) SharedConfig.currentProxy = info;
            ConnectionsManager.setProxySettings(global.getBoolean("proxy_enabled", false), settings);
            preferences.edit().remove("previous").apply();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        } finally { applying = false; }
    }

    private Process launch(JsonObject config, boolean validate) throws Exception {
        File executable = new File(ApplicationLoader.applicationContext.getApplicationInfo().nativeLibraryDir, "libxray.so");
        if (!executable.isFile() || !executable.canExecute()) throw new IllegalStateException("Xray missing");
        ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(), "run", "-config", "stdin:");
        if (validate) builder.command().add("-test");
        builder.redirectErrorStream(true);
        Process result = builder.start();
        Thread drain = new Thread(() -> {
            try (InputStream input = result.getInputStream()) { byte[] buffer = new byte[4096]; while (input.read(buffer) != -1) { } }
            catch (Exception ignored) { }
        }, "vinoGram-Xray-output");
        drain.setDaemon(true); drain.start();
        try {
            result.getOutputStream().write(config.toString().getBytes(StandardCharsets.UTF_8)); result.getOutputStream().close();
            return result;
        } catch (Exception error) { result.destroy(); throw error; }
    }

    private static byte[] download(URL source, int limit) throws Exception {
        URL current = source;
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!current.getProtocol().equals("https") && !current.getProtocol().equals("http")) throw new IllegalArgumentException();
            HttpURLConnection connection = (HttpURLConnection) current.openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(15000); connection.setReadTimeout(15000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "vinoGram/1.0");
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IllegalArgumentException();
                    URL next = new URL(current, location);
                    if (current.getProtocol().equals("https") && !next.getProtocol().equals("https")) throw new IllegalArgumentException();
                    current = next; continue;
                }
                if (code != 200) throw new IllegalArgumentException();
                try (InputStream input = connection.getInputStream()) { return read(input, limit); }
            } finally { connection.disconnect(); }
        }
        throw new IllegalArgumentException("redirects");
    }
    private static byte[] read(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (result.size() + count > limit) throw new IllegalArgumentException("size");
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }
    private static boolean waitUntilExit(Process process, long timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout * 1000000;
        while (System.nanoTime() < end) {
            try { process.exitValue(); return true; }
            catch (IllegalThreadStateException ignored) { Thread.sleep(50); }
        }
        return false;
    }
    private static int port() throws Exception { try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); } }
    private AtomicFile cache() { return new AtomicFile(new File(ApplicationLoader.applicationContext.getFilesDir(), "vinogram-subscription.cache")); }
    private String randomString() { byte[] bytes = new byte[24]; random.nextBytes(bytes); return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING); }
    private static String string(int resource) { return org.telegram.messenger.LocaleController.getString(resource); }
    private void changed() { for (Runnable listener : listeners) listener.run(); }
}
