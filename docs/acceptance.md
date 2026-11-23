# 发布前验收

在隔离测试世界运行，不复用生产存档、真实玩家档案或银行账本。测试不创建 GitHub Release，不推送标签。当前工作流跑核心测试和 Fabric 构建，**未接入下列实机 GameTest**。

## 第一关：同一个候选提交

1. 使用 JDK 17 跑 `bash test.sh` 和 `bash gradlew --no-daemon build`。保留日志及候选提交的完整 SHA。
2. 在独立测试分支跑 GitHub Actions（push 触发）。通过条件是该提交的核心测试和 Fabric 构建都成功。不要以旧版本的 Jar 或其他提交的测试结果代替。
3. 保存该提交的 remap 成品，在隔离的 Minecraft 1.20.1 / Fabric Loader 0.15.10 / Fabric API 0.92.2 / Carpet 1.4.112 上验证加载及行为。仅有 Loader 的模组识别日志不是行为验收。

## 第二关：首次生成（待接实机适配）

在独立测试模组内使用 [1.20.1 Fabric GameTest](https://github.com/FabricMC/fabric/blob/1.20.1/fabric-gametest-api-v1/src/main/java/net/fabricmc/fabric/api/gametest/v1/package-info.java#L26-L76)，运行 JVM 参数 `-Dfabric-api.gametest` 和 `-Dfabric-api.gametest.report-file=<XML绝对路径>`。从依赖解析中选同版的 `fabric-gametest-api-v1`，只放在测试运行时；正式 Jar 不打入测试入口或模组。确认 XML 含每个必测用例且没有跳过。若开发运行直接用 Gradle 源集，还要用构建的 remap Jar 重跑关键场景。

| 场景 | 必须观测到的结果 |
|---|---|
| 管理员指定安全出生点 | 登记身份匹配，实际落在所选维度和位置，进入生存模式，无 OP、无飞行 |
| 未配置，默认出生点安全 | 走默认点，普通身份校验不变 |
| 默认是虚空或保护区；或必须破坏方块才能离开 | 不生成、不开始任务，场景方块不变 |
| 指定点不安全，另有安全默认点 | 拒绝，不改用默认点 |
| 目标区块未加载、档案重名或已在线 | 拒绝，不加载未知区块，不接管或删除其他会话 |

## 第三关：本机 Ollama

先在测试运行里对接可控的本机响应，分别验证成功、不可达、超时、畸形 JSON、无权限目标，以及推理期间 Bot 或真人重新加入时不投递旧回复。模拟规划只接受当前场景允许的动作，所有测试不得修改世界和账本。检查未打开公开上下文时仅传本次点名；开启后只传有界公开聊天。最后再用实际安装的小模型验证一次 `@Bot名字` 和 `/botmod plan-test`；模拟响应不能证明真实模型可以运行。命令见 [模型接入](model-adapters.md)。

实机验收分两层，避免把模型超时误判成聊天投递故障：

1. **先测 Ollama 本身。** 记录 `/api/version` 和 `ollama list` 的版本、模型标签及摘要；用 `/api/show` 查该模型的思考开关（旧版可能不返回元数据）。在本机 `/api/chat` 跑合成中文点名和合成规划各一条。复用适配器的 `stream: false`、`think: false`、`num_ctx: 2048` 及输出上限（聊天 80、规划 48）；规划复用其目标枚举和 JSON schema。不要用真实玩家聊天作测试输入。
2. **冷启和预热分开记录。** 读 `message.content` 验证有可用正文或合法目标；HTTP 200 不算通过，仅有 `message.thinking` 也不算。记录 `done_reason`、端到端耗时和 `load_duration`、`prompt_eval_duration`、`eval_duration`（API 单位纳秒）。不保存推理正文。用 `ollama ps` 确认实际 CPU/GPU 分配，不从显卡型号推断速度。当前适配器 8 秒超时，需观察多次预热后的结果；只有冷启超时也要验证回退。若预热后仍超时或正文不可用，可在测试环境单独比较 [`qwen3:4b-instruct`](https://ollama.com/library/qwen3:4b-instruct)，不直接替换默认模型。
3. **再测消息投递和回退。** 用同一模型在隔离服从游戏里点名：只有发起人收到简短回复或明确回退。再静止执行 `mine`、`unload`、`blocked` 三个模拟规划；拒绝不合法选择，核对所有场景都没有执行动作。若第一层成功、第二层无回复，再查登记、点名识别和会话失效，不先归因于显卡或模型。
