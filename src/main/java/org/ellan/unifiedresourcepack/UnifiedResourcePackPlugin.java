package org.ellan.unifiedresourcepack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class UnifiedResourcePackPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter BACKUP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern JSON_STRING_PATTERN = Pattern.compile("\\\"%s\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");

    private Path serverRoot;
    private String velocityChannel;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        serverRoot = Paths.get("").toAbsolutePath().normalize();
        velocityChannel = getConfig().getString("velocity-refresh.channel", "craftengine_pack_gate:refresh");
        getServer().getMessenger().registerOutgoingPluginChannel(this, velocityChannel);
        Objects.requireNonNull(getCommand("unifiedresourcepack")).setExecutor(this);
        Objects.requireNonNull(getCommand("unifiedresourcepack")).setTabCompleter(this);
        getLogger().info("UnifiedResourcePack enabled at " + serverRoot);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, velocityChannel);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!hasAdminPermission(sender)) {
            sender.sendMessage(color("&c你没有权限使用这个命令。"));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("reload")) {
            reloadConfig();
            velocityChannel = getConfig().getString("velocity-refresh.channel", "craftengine_pack_gate:refresh");
            sender.sendMessage(color("&aUnifiedResourcePack 配置已重载。"));
            return true;
        }
        if (action.equals("refreshvelocity")) {
            boolean sent = sendVelocityRefresh(sender, latestShaFromCache());
            sender.sendMessage(color(sent ? "&a已通知 Velocity PackGate 刷新并重发资源包。" : "&e没有在线玩家可转发 Velocity 刷新消息；新玩家进服仍会读取最新 cache。"));
            return true;
        }
        if (!action.equals("build") && !action.equals("upload")) {
            sendHelp(sender, label);
            return true;
        }

        boolean refreshVelocity = Arrays.stream(args).skip(1).anyMatch(argument -> argument.equalsIgnoreCase("velocity") || argument.equalsIgnoreCase("--velocity"));
        sender.sendMessage(color("&7开始处理统一资源包，动作：&f" + action + (refreshVelocity ? " + velocity" : "")));
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> runPackTask(sender, action.equals("upload"), refreshVelocity));
        return true;
    }

    private void runPackTask(CommandSender sender, boolean upload, boolean refreshVelocity) {
        try {
            BuildResult result = buildUnifiedPack();
            sender.sendMessage(color("&a统一包已生成：&f" + result.output() + " &7sha1=&f" + result.sha1()));
            sender.sendMessage(color("&7合并条目：新增/覆盖 &f" + result.addedEntries() + "&7，JSON 合并 &f" + result.mergedJsonEntries() + "&7，跳过冲突 &f" + result.skippedConflicts()));
            if (!upload) {
                return;
            }

            UploadResult uploadResult = runCraftEngineUpload(result.sha1());
            if (uploadResult.matched()) {
                sender.sendMessage(color("&a上传完成，cache 已匹配统一包：&f" + uploadResult.sha1() + " &7url=&f" + uploadResult.url()));
            } else {
                sender.sendMessage(color("&e已执行上传命令，但等待超时或 cache 未匹配 sha1。当前 cache sha1=&f" + uploadResult.sha1()));
                return;
            }
            if (refreshVelocity) {
                Bukkit.getScheduler().runTask(this, () -> {
                    boolean sent = sendVelocityRefresh(sender, result.sha1());
                    sender.sendMessage(color(sent ? "&a已通知 Velocity PackGate 刷新并重发资源包。" : "&e没有在线玩家可转发 Velocity 刷新消息；PackGate 会在玩家下次进服/切服时读取最新 cache。"));
                });
            }
        } catch (Exception exception) {
            getLogger().warning("Unified pack task failed: " + exception.getMessage());
            sender.sendMessage(color("&c统一资源包处理失败：&f" + exception.getMessage()));
        }
    }

    private BuildResult buildUnifiedPack() throws Exception {
        Path craftEnginePack = resolvePath(getConfig().getString("craftengine-pack", "plugins/CraftEngine/generated/resource_pack.zip"));
        Path modelEnginePack = resolvePath(getConfig().getString("modelengine-pack", "plugins/ModelEngine/resource pack.zip"));
        if (!Files.isRegularFile(craftEnginePack)) {
            throw new IOException("找不到 CraftEngine 资源包: " + craftEnginePack);
        }
        if (!Files.isRegularFile(modelEnginePack)) {
            throw new IOException("找不到 ModelEngine 资源包: " + modelEnginePack);
        }

        if (getConfig().getBoolean("backup-before-build", true)) {
            Path backupDirectory = resolvePath(getConfig().getString("backup-directory", "plugins/UnifiedResourcePack/backups"));
            Files.createDirectories(backupDirectory);
            Path backup = backupDirectory.resolve("resource_pack-before-unified-" + LocalDateTime.now().format(BACKUP_FORMAT) + ".zip");
            Files.copy(craftEnginePack, backup);
        }

        Set<String> skipEntries = normalizedSet(getConfig().getStringList("merge.skip-modelengine-entries"));
        Set<String> mergeJsonEntries = normalizedSet(getConfig().getStringList("merge.merge-json-entries"));
        boolean overwriteConflicts = getConfig().getBoolean("merge.overwrite-conflicts", false);
        boolean repointTextures = getConfig().getBoolean("merge.repoint-modelengine-entity-textures", true);
        boolean copyRepointedTextures = getConfig().getBoolean("merge.copy-repointed-textures", true);
        String repointPrefix = getConfig().getString("merge.repoint-texture-prefix", "customcrops:block/customcrops/sprinkler/");

        Map<String, byte[]> entries = readZip(craftEnginePack, false, false, repointPrefix);
        Map<String, byte[]> modelEntries = readZip(modelEnginePack, repointTextures, copyRepointedTextures, repointPrefix);
        int addedEntries = 0;
        int mergedJsonEntries = 0;
        int skippedConflicts = 0;

        for (Map.Entry<String, byte[]> modelEntry : modelEntries.entrySet()) {
            String name = modelEntry.getKey();
            if (skipEntries.contains(name)) {
                continue;
            }
            byte[] existing = entries.get(name);
            if (existing != null && mergeJsonEntries.contains(name)) {
                entries.put(name, mergeJson(existing, modelEntry.getValue()));
                mergedJsonEntries++;
                continue;
            }
            if (existing != null && !overwriteConflicts && !name.startsWith("assets/modelengine/")) {
                skippedConflicts++;
                continue;
            }
            entries.put(name, modelEntry.getValue());
            addedEntries++;
        }

        Path temporaryPack = craftEnginePack.resolveSibling(craftEnginePack.getFileName() + ".unified.tmp");
        writeZip(temporaryPack, entries);
        Files.move(temporaryPack, craftEnginePack, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new BuildResult(craftEnginePack, sha1(craftEnginePack), addedEntries, mergedJsonEntries, skippedConflicts);
    }

    private Map<String, byte[]> readZip(Path zipPath, boolean repointTextures, boolean copyRepointedTextures, String repointPrefix) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        Map<String, byte[]> copiedTextures = new LinkedHashMap<>();
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            var zipEntries = zipFile.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry zipEntry = zipEntries.nextElement();
                if (zipEntry.isDirectory()) {
                    continue;
                }
                String name = normalizeEntryName(zipEntry.getName());
                byte[] bytes;
                try (InputStream zipInput = zipFile.getInputStream(zipEntry)) {
                    bytes = readAllBytes(zipInput);
                } catch (IOException exception) {
                    throw new IOException("读取资源包条目失败: " + zipPath + " -> " + name + " (" + exception.getMessage() + ")", exception);
                }
                if (repointTextures && name.startsWith("assets/modelengine/") && name.endsWith(".json")) {
                    bytes = repointModelEngineTextureRefs(bytes, repointPrefix);
                }
                entries.put(name, bytes);

                if (copyRepointedTextures && name.startsWith("assets/modelengine/textures/entity/") && name.endsWith(".png")) {
                    String relativeTexture = name.substring("assets/modelengine/textures/entity/".length());
                    copiedTextures.put("assets/customcrops/textures/block/customcrops/sprinkler/" + relativeTexture, bytes);
                }
            }
        }
        entries.putAll(copiedTextures);
        return entries;
    }

    private byte[] repointModelEngineTextureRefs(byte[] bytes, String repointPrefix) {
        String json = new String(bytes, StandardCharsets.UTF_8);
        String updated = json.replaceAll("modelengine:entity/([^\\\"#]+)", Matcher.quoteReplacement(repointPrefix) + "$1");
        updated = updated.replace("\"tintindex\":0,", "").replace(",\"tintindex\":0", "");
        return updated.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] mergeJson(byte[] baseBytes, byte[] overlayBytes) {
        JsonElement base = JsonParser.parseString(new String(baseBytes, StandardCharsets.UTF_8));
        JsonElement overlay = JsonParser.parseString(new String(overlayBytes, StandardCharsets.UTF_8));
        JsonElement merged = mergeElements(base, overlay);
        return GSON.toJson(merged).getBytes(StandardCharsets.UTF_8);
    }

    private JsonElement mergeElements(JsonElement base, JsonElement overlay) {
        if (base == null || base.isJsonNull()) {
            return overlay.deepCopy();
        }
        if (overlay == null || overlay.isJsonNull()) {
            return base.deepCopy();
        }
        if (base.isJsonObject() && overlay.isJsonObject()) {
            JsonObject result = base.getAsJsonObject().deepCopy();
            JsonObject overlayObject = overlay.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : overlayObject.entrySet()) {
                if (result.has(entry.getKey())) {
                    result.add(entry.getKey(), mergeElements(result.get(entry.getKey()), entry.getValue()));
                } else {
                    result.add(entry.getKey(), entry.getValue().deepCopy());
                }
            }
            return result;
        }
        if (base.isJsonArray() && overlay.isJsonArray()) {
            JsonArray result = base.getAsJsonArray().deepCopy();
            Set<String> seen = new LinkedHashSet<>();
            for (JsonElement element : result) {
                seen.add(GSON.toJson(element));
            }
            for (JsonElement element : overlay.getAsJsonArray()) {
                String serialized = GSON.toJson(element);
                if (seen.add(serialized)) {
                    result.add(element.deepCopy());
                }
            }
            return result;
        }
        return base.deepCopy();
    }

    private void writeZip(Path zipPath, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zipOutput = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipOutput.putNextEntry(zipEntry);
                zipOutput.write(entry.getValue());
                zipOutput.closeEntry();
            }
        }
    }

    private UploadResult runCraftEngineUpload(String expectedSha1) throws InterruptedException {
        Path cacheFile = resolvePath(getConfig().getString("cache-file", "plugins/CraftEngine/cache/gitlab.json"));
        String uploadCommand = getConfig().getString("upload-command", "ce upload");
        int waitSeconds = Math.max(5, getConfig().getInt("upload-wait-seconds", 180));
        CountDownLatch latch = new CountDownLatch(1);
        Bukkit.getScheduler().runTask(this, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), uploadCommand);
            latch.countDown();
        });
        latch.await(10L, TimeUnit.SECONDS);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
        UploadResult lastResult = readUploadCache(cacheFile);
        while (System.nanoTime() < deadline) {
            lastResult = readUploadCache(cacheFile);
            if (lastResult.sha1().equalsIgnoreCase(expectedSha1) && !lastResult.url().isBlank()) {
                return new UploadResult(lastResult.sha1(), lastResult.url(), true);
            }
            Thread.sleep(1000L);
        }
        return lastResult;
    }

    private UploadResult readUploadCache(Path cacheFile) {
        try {
            if (!Files.isRegularFile(cacheFile)) {
                return new UploadResult("", "", false);
            }
            String json = Files.readString(cacheFile, StandardCharsets.UTF_8);
            return new UploadResult(extractJsonString(json, "sha1"), extractJsonString(json, "url"), false);
        } catch (IOException exception) {
            return new UploadResult("", "", false);
        }
    }

    private String latestShaFromCache() {
        return readUploadCache(resolvePath(getConfig().getString("cache-file", "plugins/CraftEngine/cache/gitlab.json"))).sha1();
    }

    private boolean sendVelocityRefresh(CommandSender sender, String sha1) {
        byte[] message = (getConfig().getBoolean("velocity-refresh.resend-online-players", true) ? "resend" : "reload")
                .concat(sha1 == null || sha1.isBlank() ? "" : ":" + sha1)
                .getBytes(StandardCharsets.UTF_8);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendPluginMessage(this, velocityChannel, message);
            getLogger().info("Sent Velocity refresh message through " + player.getName() + " by " + sender.getName());
            return true;
        }
        return false;
    }

    private String extractJsonString(String json, String key) {
        Pattern pattern = Pattern.compile(String.format(JSON_STRING_PATTERN.pattern(), Pattern.quote(key)));
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).replace("\\/", "/").trim();
    }

    private Path resolvePath(String configured) {
        Path path = Paths.get(configured == null ? "" : configured.trim());
        return path.isAbsolute() ? path.normalize() : serverRoot.resolve(path).normalize();
    }

    private Set<String> normalizedSet(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            result.add(normalizeEntryName(value));
        }
        return result;
    }

    private String normalizeEntryName(String name) {
        return name.replace('\\', '/').replaceFirst("^/+", "");
    }

    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        inputStream.transferTo(output);
        return output.toByteArray();
    }

    private String sha1(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest()) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private boolean hasAdminPermission(CommandSender sender) {
        return sender.hasPermission("unifiedresourcepack.admin") || sender.hasPermission("craftengineunifiedpack.admin");
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(color("&e/" + label + " build &7- 合并 CraftEngine + ModelEngine 到统一资源包"));
        sender.sendMessage(color("&e/" + label + " upload &7- 合并后执行 /ce upload"));
        sender.sendMessage(color("&e/" + label + " upload velocity &7- 上传后通知 Velocity PackGate 刷新/重发"));
        sender.sendMessage(color("&e/" + label + " refreshvelocity &7- 只通知 Velocity PackGate"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!hasAdminPermission(sender)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(List.of("build", "upload", "refreshvelocity", "reload", "help"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("upload")) {
            return filter(List.of("velocity"), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                result.add(value);
            }
        }
        return result;
    }

    private record BuildResult(Path output, String sha1, int addedEntries, int mergedJsonEntries, int skippedConflicts) {
    }

    private record UploadResult(String sha1, String url, boolean matched) {
    }
}