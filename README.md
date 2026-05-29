# UnifiedResourcePack

UnifiedResourcePack is a Paper/Purpur backend plugin that turns the resource-pack workflow into one repeatable operation: merge CraftEngine and ModelEngine packs, upload through CraftEngine, and optionally notify Velocity PackGate to refresh and resend the pack.

UnifiedResourcePack 是一个后端 Paper/Purpur 插件，用来把资源包发布流程变成一条稳定命令：合并 CraftEngine 与 ModelEngine 资源包，通过 CraftEngine 上传，并可通知 Velocity PackGate 刷新和重发资源包。

## Why It Exists / 设计目标

CraftEngine and ModelEngine can each produce assets that players need, but Velocity should only send one final resource pack. Manual zip merging is fragile: generated packs can overwrite font mappings, ModelEngine atlas entries, or patched texture paths after reloads.

CraftEngine 与 ModelEngine 都会产生玩家需要的资源，但 Velocity 最好只下发一个最终资源包。手动合并 zip 很容易出错：reload 后可能覆盖字体映射、ModelEngine atlas 条目或贴图路径修补。

This plugin automates that handoff and validates upload completion by watching CraftEngine's `gitlab.json` cache.

本插件会自动完成合并，并通过检查 CraftEngine 的 `gitlab.json` 缓存确认上传是否真正完成。

## Platform / 平台

- Server: Paper/Purpur/Leaf backend
- API: Minecraft 1.21.x
- Soft dependencies: CraftEngine, ModelEngine
- Primary command: `/unifiedresourcepack`
- Short alias: `/urpack`
- Legacy aliases: `/upack`, `/unifiedpack`

## Commands / 命令

| Command | Description | 中文说明 |
| --- | --- | --- |
| `/urpack build` | Merge CraftEngine and ModelEngine packs locally. | 只在本地合并统一资源包。 |
| `/urpack upload` | Build, then run the configured CraftEngine upload command. | 合并后执行配置里的 CraftEngine 上传命令。 |
| `/urpack upload velocity` | Build, upload, then notify Velocity PackGate to refresh/resend. | 合并、上传，并通知 Velocity PackGate 刷新/重发。 |
| `/urpack refreshvelocity` | Only send the Velocity refresh plugin message. | 只通知 Velocity 刷新。 |
| `/urpack reload` | Reload this plugin's config. | 重载本插件配置。 |

## Permission / 权限

```text
unifiedresourcepack.admin
```

Default: `op`. The legacy permission `craftengineunifiedpack.admin` is still accepted for compatibility.

默认：`op`。为了兼容旧权限配置，插件仍接受 `craftengineunifiedpack.admin`。

## Configuration / 配置

Default config:

```yaml
craftengine-pack: plugins/CraftEngine/generated/resource_pack.zip
modelengine-pack: plugins/ModelEngine/resource pack.zip
cache-file: plugins/CraftEngine/cache/gitlab.json
backup-before-build: true
backup-directory: plugins/UnifiedResourcePack/backups
upload-command: ce upload
upload-wait-seconds: 180
velocity-refresh:
  channel: craftengine_pack_gate:refresh
  resend-online-players: true
merge:
  skip-modelengine-entries:
    - pack.mcmeta
    - pack.png
  merge-json-entries:
    - assets/minecraft/items/leather_horse_armor.json
    - assets/minecraft/items/player_head.json
    - assets/minecraft/atlases/blocks.json
  overwrite-conflicts: false
  repoint-modelengine-entity-textures: true
  repoint-texture-prefix: customcrops:block/customcrops/sprinkler/
  copy-repointed-textures: true
```

Key behavior:

- skips ModelEngine `pack.mcmeta` and `pack.png` so CraftEngine remains the base pack;
- merges selected JSON files instead of overwriting them;
- copies `assets/modelengine/**` entries into the final CraftEngine pack;
- rewrites `modelengine:entity/...` texture references to a CustomCrops namespace when enabled;
- backs up the CraftEngine pack before replacing it;
- waits until `gitlab.json` matches the generated SHA-1 before reporting upload success.

关键行为：

- 跳过 ModelEngine 的 `pack.mcmeta` 和 `pack.png`，以 CraftEngine 包为基础；
- 对指定 JSON 做合并而不是覆盖；
- 将 `assets/modelengine/**` 合入最终 CraftEngine 包；
- 可将 `modelengine:entity/...` 贴图引用改写到 CustomCrops 命名空间；
- 替换资源包前自动备份；
- 等待 `gitlab.json` 的 SHA-1 与生成包一致后才认为上传成功。

## Deployment / 部署

1. Build the jar with Java 21-compatible tooling.
2. Copy it to each backend server's `plugins` directory.
3. Restart the backend servers.
4. Confirm startup log contains `UnifiedResourcePack enabled`.
5. Run `/urpack build` once on a staging backend before production upload.

中文步骤：

1. 使用兼容 Java 21 的工具构建 jar。
2. 将 jar 复制到每个后端服务器的 `plugins` 目录。
3. 重启后端服务器。
4. 确认日志出现 `UnifiedResourcePack enabled`。
5. 生产上传前，先在测试后端执行一次 `/urpack build`。

## Recommended Workflow / 推荐流程

```text
/urpack upload velocity
```

Use this after CraftEngine/ModelEngine assets are generated and Velocity PackGate 1.2.0 or newer is installed.

在 CraftEngine/ModelEngine 资源生成完毕，并且 Velocity 已部署 PackGate 1.2.0 或更高版本后使用。

## Verification / 验证

After upload:

- `plugins/CraftEngine/cache/gitlab.json` should contain the new URL and SHA-1.
- Velocity logs should show PackGate refresh/resend activity if `velocity` was requested.
- Players should receive one network resource pack, not separate backend and proxy packs.
- Client-side resource reload should show CraftEngine and ModelEngine assets together.

上传后检查：

- `plugins/CraftEngine/cache/gitlab.json` 是否包含新的 URL 和 SHA-1。
- 如果使用了 `velocity` 参数，Velocity 日志是否出现 PackGate 刷新/重发记录。
- 玩家应只收到一个网络资源包，而不是后端和代理各发一个。
- 客户端重载资源后应同时显示 CraftEngine 与 ModelEngine 资源。

## Troubleshooting / 排障

- If upload times out, inspect CraftEngine upload logs and `gitlab.json`.
- If Velocity does not resend, confirm an online backend player exists to carry the plugin message and that PackGate 1.2.0 is running.
- If a model turns purple/black, verify texture references and atlas entries inside the final zip.
- If `/ce reload` regenerates the pack, run `/urpack upload velocity` again.

- 如果上传超时，检查 CraftEngine 上传日志和 `gitlab.json`。
- 如果 Velocity 没有重发，确认后端有在线玩家可转发插件消息，并确认 PackGate 1.2.0 正在运行。
- 如果模型紫黑，检查最终 zip 内的贴图引用和 atlas 条目。
- 如果 `/ce reload` 重新生成了资源包，请重新执行 `/urpack upload velocity`。
