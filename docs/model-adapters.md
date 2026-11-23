# 模型适配规划

## 当前测试入口

Ollama 与 Minecraft 服务端同机运行。接入固定 `127.0.0.1`，默认端口 `11434`，不经过 HTTP 代理、不跟随跳转。BotMod 默认不开模型。在启动 Minecraft **服务端**的 PowerShell 会话中设置变量，再运行原有服务端启动脚本：

```powershell
ollama list
$env:SOCIETYBOTS_OLLAMA = "1"
$env:SOCIETYBOTS_OLLAMA_MODEL = "qwen3:4b"
$env:SOCIETYBOTS_OLLAMA_PORT = "11434"
$env:SOCIETYBOTS_OLLAMA_CHAT = "1"
$env:SOCIETYBOTS_OLLAMA_PLAN = "1"
```

模型名要与 `ollama list` 一致，端口要与本机 Ollama 服务一致。若由其他账号或 Windows 服务启动 Fabric，应在该进程的环境中设置。建议在 **Ollama 自己的进程**关闭云功能（`OLLAMA_NO_CLOUD=1`）并重启 Ollama；BotMod 不接远程模型。

| 入口 | 行为 |
|---|---|
| `/botmod status` | 查看登记在线数和聊天/规划配置开关，不代表模型已连通 |
| 普通聊天输入 `@Bot用户名 你在做什么？` | 已登记、在线、无管理权限的 Bot 私下回复，不能借聊天下达交易、授权或任务 |
| `/botmod plan-test mine`、`unload`、`blocked` | 仅管理员；模拟可采、可入库和受阻场景，不读聊天、不改变世界 |
| `/botmod plan-test preference <Bot用户名>` | 仅管理员；在两种工作都可选的模拟场景中对照居民偏好 |

默认仅发送本次点名。确需公开聊天上下文时，先告知测试服玩家，再设 `SOCIETYBOTS_OLLAMA_PUBLIC_CONTEXT=1`，最多传最近八条公开聊天；私聊与命令不进入缓冲。规划只给模型布尔状态、允许目标和偏好。请求使用 [Ollama /api/chat](https://docs.ollama.com/api/chat) 的 JSON schema；服务器只接受 `{"goal":"MINE"}`、`{"goal":"UNLOAD"}` 或 `{"goal":"STOP"}`，再按权限、观测和有效期判断是否采纳。仅模拟，不执行。共享单并发，规划遇忙、响应无效或超时采用本地规则。

当前试验版另有管理员合成场景规划命令，不读取玩家聊天做行动决策，不执行模型建议。以下为后续正式接入约束：

- chat(ChatInput) -> ChatReply：普通对话只产生文本，不能返回或触发指令、交易、授权。
- plan(Observation, AllowedCapabilities, CostBudget) -> ProposedPlan：可提新目标、组合受支持的动作；只返回带 schema 版本、观测版本、过期时间和候选目标引用的计划。不得返回任意命令或 URL。
- Ollama：使用本机预配置地址；远程 OpenAI-compatible 后端用单独适配器，管理员明确配置 HTTPS 主机、模型及仅保存在环境或密钥存储中的 API Key；不跟随重定向。禁止模型改服务地址。
- 本地公开聊天与远程共享必须分别开关。远程默认禁用原始聊天；需要启用时，先说明提供方、范围和用途并按服务器授权处理。优先发脱敏任务摘要，不把聊天日志、银行账号、管理员指令或玩家凭据放入请求。
- 模型与主游戏线程隔离，分开限流、超时和计费。先用单 P40 单并发测试，测队列、推理耗时和服务器 MSPT。超时使用本地规则/安全暂停。
- 提案过期或每步状态变化时重新验证使用权限、交易上限与数据版本；模型不能审批购买领地。对外服务故障时，不改变资产，也不重新执行已提交的交易。

真实的采购、领地审批、出入库与工作任务不是聊天工具。服务端适配相应模块前，模型不拥有这些能力。
