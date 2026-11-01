# ScratchMC —— 用 Scratch 面板驱动 Minecraft Java 版（JVM 注入版）

一个 `.sb3` 工程 + 一个注入进 `java(w).exe` 的 agent。Scratch 里的「模块 / 开关 / 自瞄 / 准星」
不是纯摆设：积木通过本地 HTTP 打进游戏进程内部的反射层，真正读玩家坐标、实体列表，
真正写 `yRot / xRot`，真正调用 `gameMode.attack()`。

```
 TurboWarp 工程                      mcsi-agent.jar（注入在 javaw.exe 里）
 project/ScratchMC.sb3               ┌──────────────────────────────────────┐
   [模块 aim 开] ──HTTP POST──>  :8791 /module   → Cheat.aimOn = true        │
   [锁定目标名字] <──JSON─────        /target     ← 25ms 脉冲线程算好的目标     │
   [准星 移到 x,y] ──HTTP────>        /entities    → 反射遍历 ClientLevel       │
                                    │  Mapping：Mojang / Yarn / SRG 三套映射 │
                                    └──────────────────────────────────────┘
```

## 目录

```
project/ScratchMC.sb3      要拖进 Scratch 的工程（这份就是主交付）
extension/scratch-mc-inject.js   扩展源码（等同 http://127.0.0.1:8791/extension.js）
java/src/mcsi/             agent 源码：Agent / Mapping / Mc / Cheat / HttpBridge / Routes
loader/McAttach.java       com.sun.tools.attach 注入器
java/mock-mojang, mock-inter     两套假 MC，用于不装游戏也能跑通全链路
java/selftest/             SelfTest（离线自测）+ MockHost（e2e 的假游戏进程）
tools/make_project.py      生成 sb3
tools/check_project.py     校验 sb3 结构 / opcode（拖进编辑器前先跑）
tests/e2e.ps1              真的 attach 进一个 JVM，再从 HTTP 验证
build.ps1                  编译 + 打 jar
```

## 快速开始

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1        # 出 java\build\mcsi-agent.jar

# 1) 先开游戏，进到一个世界里
# 2) 找到游戏进程
java --add-modules jdk.attach -cp loader\build McAttach --list
# 3) 注入（不带 --pid 会自动认 net.minecraft / KnotClient / BootstrapLauncher）
java --add-modules jdk.attach -cp loader\build McAttach --pid 12345
# 4) 自检：应该直接返回游戏状态
curl http://127.0.0.1:8791/status
```

然后在 **TurboWarp**（桌面版或 turbowarp.org）里打开 `project/ScratchMC.sb3`：

* 工程里带 `extensionURLs`，会自动从 `http://127.0.0.1:8791/extension.js` 拉扩展；
* 也可以手动「扩展 → 加载扩展 → 从 URL 加载」贴同一个地址；
* 点绿旗 → 角色「注入端」会说连接结果和自检报告 → 列表「模块 / 开关」出现三行 → 点方块切换。

> 官方 Scratch 编辑器不支持自定义扩展，必须用 TurboWarp / PenguinMod。
## 没有 TurboWarp 怎么办

官方 Scratch（scratch.mit.edu）的积木沙箱没有任何网络能力，也加载不了自定义扩展，
所以「连本地注入端」这件事官方站做不到 —— 用官方站打开这个 sb3，工程本身不会崩，
但 `scratchMC_*` 会显示成未知积木。三条路，任选：

1. **网页版 TurboWarp（零安装，最快）**
   浏览器打开 <https://turbowarp.org/> → 菜单「文件 → 从电脑中加载」→ 选 `ScratchMC.sb3`。
   扩展会自动从 `http://127.0.0.1:8791/extension.js` 拉取。注入端已经带了
   `Access-Control-Allow-Private-Network: true`，Chrome 的「本地网络访问」限制不会拦。
   自检：浏览器直接打开 `http://127.0.0.1:8791/extension.js`，看到 JS 源码就说明路是通的。

2. **桌面版 TurboWarp**
   双击 `open-turbowarp.bat`：它会启动随包下载的
   `tools/turbowarp/TurboWarp.Portable.1.17.1.x64.exe` 并把 `ScratchMC.sb3` 一起打开。
   内置副本不在时脚本会打印下载地址。

3. **干脆不用 Scratch 前端**
   注入端就是个 HTTP 服务，`curl http://127.0.0.1:8791/status` 一样能读状态、开模块、写视角。


### 用官方 Scratch 3 / Scratch Desktop 打开会怎样

打开得开，但 `scratchMC_*` 全部显示成灰色未知积木，点绿旗不会有任何效果 ——
Scratch 3 的 VM 没有网络能力，扩展加载器也只在 TurboWarp / PenguinMod 里存在。
这不是配置问题，是运行时能力问题，所以注入型外挂的前端只能用 TurboWarp。


## 工程里有什么

| 角色 | 作用 |
| --- | --- |
| **注入端** | 绿旗连接、报连接结果与 `/mappings` 自检、之后每 0.2 秒 `刷新状态` |
| **模块面板** | 按列表「模块」克隆出一排方块，鼠标点一下就 `模块 [名字] 开/关`，并且方块自己报当前状态 |
| **准星** | 每 0.05 秒把 `锁定目标 偏航差/俯仰差` 换算成屏幕坐标（×5 像素/度），按 `1` 键切自瞄 |

Stage 上：列表 `模块` / `开关`（对应截图那两块监视器），变量 `序号` / `连接地址` / `自瞄开关` / `锁定` / `距离`。

## 积木（扩展 `scratchMC`）

| 积木 | 说明 |
| --- | --- |
| `连接注入端 [URL]` | 握手 `/ping` + `/status`，返回可读结果 |
| `注入端已连接？` / `连接结果` | 连线状态 |
| `刷新状态` | 拉一次 `/status` + `/entities`（内部带 100/180ms 缓存） |
| `玩家 [x/y/z/偏航/俯仰/血量/名字]` | 读本地玩家 |
| `实体数量` / `实体 [i] 的 [字段]` | 按距离排序的实体快照（默认 32 格内） |
| `锁定目标 [字段]` | 脉冲线程选出来的目标：名字 / 距离 / 偏航差 / 俯仰差 / 血量 |
| `模块 [aim/trigger/esp] [开/关]` | 开关作弊模块（可接变量，面板就是这么用的） |
| `设置 [参数] 为 [值]` | range / speed / step / fov / sensStep / 目标 / 触发方式 / 排序 / 平滑 |
| `转向 偏航 [Y] 俯仰 [P]` | 直接写视角 |
| `攻击锁定目标` | 调 `MultiPlayerGameMode#attack` |
| `自检报告` | `/mappings` 的解析结论（哪个类、哪个字段被认成了什么） |
| `停止注入端` | 关掉 HTTP 与脉冲线程（游戏不受影响） |

## 参数怎么调

| 参数 | 默认 | 作用 |
| --- | --- | --- |
| `range` | 4 | 瞄准范围（格）。实体快照也用它 |
| `speed` | 300 | 角速度上限（度/秒） |
| `step` | 8 | 单帧最大转动（度），防止瞬移式甩头 |
| `fov` | 30 | 目标离准星超过这个角度就不自瞄 |
| `sensStep` | 0.15 | 视角量化步进（≈ 一格鼠标灵敏度），转出来的角度更"像手" |
| `平滑` | 开 | 接近目标时减速，避免过冲抖动 |
| `目标` | mob | mob / player / all |
| `触发方式` | left | 准星进 `triggerBox`（默认 2°）就 attack，冷却 130ms |

## HTTP 接口

`GET /ping` `GET /status` `GET /player` `GET /entities?range=&filter=&sort=&limit=`
`GET /target` `GET /pulse` `GET /mappings` `POST /module {name,on}`
`POST /params {key,value}` `POST /look {yaw,pitch}` 或 `{dyaw,dpitch}`
`POST /attack` `POST /resolve` `POST /shutdown`
`GET /extension.js` `GET /project/ScratchMC.sb3`

带口令启动：`--token abc` → 请求头 `X-MC-Token: abc`（`/extension.js` 与 `/project/*` 免校验，方便扩展加载）。

## 映射适配（为什么它能跨版本）

同一个 MC 在不同环境下名字完全不同，`Mapping` 同时认三套：

| | Minecraft 类 | player 字段 | 实体枚举 |
| --- | --- | --- | --- |
| Mojang 官方映射 | `net.minecraft.client.Minecraft` | `player` | `entitiesForRendering()` |
| Yarn / Intermediary | `net.minecraft.class_310` | `field_1724` | `method_18122()` |
| Forge / NeoForge SRG | `net.minecraft.client.Minecraft` | `f_91074_` | `m_104735_()` |

名字都对不上时走结构探测：类里有 `static` 自身类型字段 → 是单例主类；字段类型里有三个 `double`
→ 是 Entity 位置；两个 `float` 且一个落在 ±180、另一个落在 ±90 → 是 yaw / pitch；`Level` 里任何
无参返回 `Iterable` 的方法 → 实体枚举。实测这套在「方法名全不认识、只有字段」的假 MC 上也能跑通
（`tests/e2e.ps1` 与 `mcsi.SelfTest` 都覆盖）。

解析结论随时看：`GET /mappings`，或 Scratch 里的 `自检报告` 积木。纯原版（未加 Fabric/Forge 的混淆 jar）
字段名会全部对不上，这时只能拿到坐标/朝向的结构探测结果，`canAim` 可能为 false —— 换 Fabric/Forge 客户端最省事。

## 测试

```powershell
# 离线自测：假 MC + 假 HttpBridge，两套映射各 22 项
java -cp "java\build\selftest;java\build\mock-mojang" mcsi.SelfTest
java -cp "java\build\selftest;java\build\mock-inter"  mcsi.SelfTest

# 端到端：起一个假游戏 JVM，真的 attach 进去，再从 HTTP 打一遍
powershell -ExecutionPolicy Bypass -File .\tests\e2e.ps1
```

`e2e.ps1` 覆盖：attach → loadAgent → 映射解析 → 状态/实体/转向/模块/参数/自瞄/攻击 → `/extension.js`
与 `/project/ScratchMC.sb3` 可取 → `/shutdown` 真的停。最后杀进程。

改完 `tools/make_project.py` 记得：

```powershell
python tools\make_project.py     # 重新生成 sb3
python tools\check_project.py    # 校验积木图 / opcode / 造型 md5 / 监视器
```

## 报错排查

### `Error in sandboxed script: http://127.0.0.1:8791/extension.js`

TurboWarp 桌面版把这条错误吐出来时，意思只有一句：那个 URL 的脚本没跑通。三个成因，按顺序排：

1. **扩展被塞进 sandbox iframe** —— 桌面版默认把 URL 扩展放进 sandbox，那里
   `fetch http://127.0.0.1` 会被 CSP / 本地网络访问策略拦掉（iframe origin 是 null）。
   现在扩展在 `getInfo()` 里声明了 `unsandboxed: true`，用最新的
   `extension/scratch-mc-inject.js`。
2. **URL 返回的不是 JS** —— 注入端是旧 jar 时 `/extension.js` 会 404，返回的是 JSON；
   TurboWarp 把 JSON 当脚本执行，报的还是这句话。现在 404 会返回一段会 `throw` 的 JS，
   控制台能直接看到原因。自检：浏览器打开 `http://127.0.0.1:8791/extension.js`，
   必须看到 JS 源码。
3. **`this.lastError` 和积木方法 `lastError()` 同名** —— 字段覆盖了原型方法，
   TurboWarp 校验 opcode 时拿到的是字符串，注册就会失败。已改名为 `lastErr`。

不想重启游戏、也不想重新注入时：TurboWarp → 扩展 → **Load from file** →
选 `extension/scratch-mc-inject.js`。扩展只是 HTTP 客户端，连正在跑的旧注入端也照样工作。

离线先把扩展验一遍（不需要 Scratch / TurboWarp / 游戏）：

```powershell
node tools\check-extension.js
```

它会用假 Scratch 桩加载扩展、检查 `getInfo()` 能否 `structuredClone`（sandbox 就是走
postMessage 回传，不可克隆就会变成上面那句报错）、检查每个 opcode 都有实现，
再起一个假注入端把 connect / 读状态 / 开关模块 / 改参数全跑一遍。

### 先跑 doctor

双击 `check.bat`（可带端口号，如 `check.bat 8793`），四步自检。正常长这样：

```
[1/4] /ping
  OK  {"ok":true,"name":"ScratchMC","version":"1.0.0","pid":33152,...}
[2/4] /extension.js  (TurboWarp must see JS here, not JSON)
  type: application/javascript; charset=utf-8
  len : 15098
  head: /**
[3/4] /status
  player  : Steve
  inGame  : True
  canAim  : True
  canAttack: True
  entities: 4
[4/4] mapping report
  Minecraft 类 = net.minecraft.client.Minecraft（名字候选）
  player 字段 = player（名字候选）
```

第 2 步的 `head` 必须是 `/**`。**看到 `{` 就说明注入端在返回 JSON，TurboWarp 必然报
`Error in sandboxed script`** —— 那是旧 jar 或端口被别的程序占着。

### 注入「看起来成功」但端口没起来

目标 JVM 刚启动时，`loadAgent` 偶尔会返回成功但 `agentmain` 没真正执行。
`McAttach` 现在注入完会自己打一次 `/ping`，不通就重试（最多 3 次），然后给结论：

```
注入完成，HTTP 已就绪。
  状态: http://127.0.0.1:8791/status
  扩展: http://127.0.0.1:8791/extension.js
```

看到这句才算真的成了。看不到就是没成，按它打印的三条（pid 是否还活着 / 权限是否对齐 /
`-XX:+DisableAttachMechanism`）排查。

### 还不行：用内联扩展的工程

`ScratchMC-inline.sb3` 把扩展字节以 `data:application/javascript;base64,` 直接内联进
`extensionURLs`。TurboWarp 不再需要向 127.0.0.1 取脚本，只剩扩展内部的 fetch，
可以排除「取脚本」这一层的所有问题（CSP / 本地网络策略 / 端口没起来）。

### 日志在哪

agent 会把日志写一份到 `%TEMP%\mcsi-agent.log`（游戏控制台常被启动器藏起来）。
里面有 1 秒节流的访问记录，能直接看出 TurboWarp 到底有没有来取扩展：

```
[mcsi 16:56:51.936] → GET /extension.js
[mcsi 16:56:52.260] → GET /status
[mcsi 16:56:52.627] → GET /mappings
```

只看到 `/status` 没有 `/extension.js`，说明 TurboWarp 根本没走到取扩展那一步。

## 不装编辑器也能跑：`tools/vmrun.js`

用 scratch-vm（就是 Scratch 3 自己的运行时）headless 跑这个 sb3，不需要 TurboWarp、不需要浏览器：

```powershell
cd tools\vmrun                                         # 一次
"C:\Program Files\nodejs\npm.cmd" install scratch-vm
cd ..\..
node tools\vmrun.js ScratchMC.sb3 8                    # 跑 8 秒
```

实测输出（注入端在 8791 上跑着）：

```
extension registered: scratchMC
extension URLs the project asked for: scratchMC
project loaded: ScratchMC.sb3 16777 bytes
targets: 注入端, 模块面板, 准星
blocks in sprites: 154  ext blocks: 19
green flag -> running for 8s ...

--- stage variables ---
  var  序号 = 4
  var  连接地址 = "http://127.0.0.1:8791"
  list 模块 = ["aim","trigger","esp"]
  list 开关 = ["1","1","1"]

--- sprite variables ---
  模块面板.我的模块 = "esp"
  模块面板.我的开关 = "1"

--- extension state ---
  connected : true
  connect   : 已连接 http://127.0.0.1:8791（pid 7040，玩家 Steve）
```

`connect` 那行是扩展真的发 HTTP 拿回来的 —— 也就是说工程里的积木确实在执行，而不是摆设。

顺带它揪出一个真 bug：`control_create_clone_of` 的 `CLONE_OPTION` 里菜单 primitive 写成了
`[11, "_myself_", null]`，而 sb3 schema 只允许两段。scratch-vm 会因此拒绝整个工程
（`Could not parse as a valid SB2 or SB3 project`），TurboWarp 则只报成扩展加载失败。
现在已改成 `[11, "_myself_"]`，`tools/check_project.py` 也加了这条校验。

## 已知限制

* 注入需要和目标进程同权限：游戏用管理员启动，注入器也要管理员。
* 需要目标 JVM 允许 attach（官方启动器、Fabric、Forge 都行；被加固过或加了 `-XX:+DisableAttachMechanism` 的不行）。
* 纯原版混淆 jar 的字段名认不出来，`/mappings` 会告诉你缺哪一段。
* 只做本地单人/自建服玩法验证；服务端反作弊看到的仍然是"视角很平滑的玩家"。
* Windows / macOS / Linux 都能跑（attach 与反射不绑平台），但 `tests/e2e.ps1` 是 PowerShell。

## 参考

* 【全网首发，使用 Scratch 注入什么岛并且绕过 native】 <https://www.bilibili.com/video/BV17AxC6nETT/>

## 许可

MIT，见 [LICENSE](LICENSE)。
