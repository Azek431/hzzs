# Git 提交规范

正文使用 **Markdown**，**适度详细**：说清动机、关键改动、不变量与验证即可，避免长篇清单与重复。

## 标题

- 格式：`type(scope): 中文摘要`
- 常用 type：`feat` / `fix` / `docs` / `refactor` / `test` / `chore` / `security`
- 摘要写清做了什么；避免「更新代码」「修 bug」这类空话。

## 正文（Markdown，约半屏内）

建议结构（可按提交省略无关节）：

```markdown
## 动机
一句话说明为什么改。

## 改动
- 关键行为/路径（3～6 条为宜，不逐文件流水账）

## 不变量
- 刻意未改的安全/协议/几何边界

## 验证
- 已跑门禁或单测；未跑的写一句即可
```

要求：

- 用完整短句或紧凑列表；**不要**把 diff 复述成超长 bullet。
- 可选 `## 风险` 仅在真有后续限制时写 1～2 句。
- 一个提交一个意图；ZIP、密钥、本机 IDE 私货、未接线孤立文件不要混入。
- 日常开发默认在 **`main`** 直接提交（用户偏好）；开 feature 分支须用户明确要求。
- 推送或合入远程 `main` 前，破坏性/未测改动须用户确认；门禁按任务约定执行。

## PowerShell 提交多行正文的坑（永久约束）

**禁止**用 `git commit -m @'…'@` 或 `git commit -m "…" -m "…"` 在 PowerShell 里提交多行正文。

- heredoc 写法会把起始 `@` 后面的换行吃进标题，导致标题变成 `@ fix(...): …`；
- 多个 `-m "…"` 在 PowerShell 里会被解析成 pathspec，报 `pathspec '+' did not match any file(s)`。

**正确做法**：把完整消息写入临时 UTF‑8 文件，用 `git commit -F <file>` 提交，然后立即删除临时文件。

```powershell
$msg = @"
fix(mcp): 中文摘要

动机
一句话。

改动
- 第一点
- 第二点

验证
- 单测通过
"@
$tmp = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tmp, $msg, [System.Text.Encoding]::UTF8)
git commit -F $tmp
Remove-Item $tmp
```

或调用 Python 走 stdin：`python -c "import subprocess; subprocess.run(['git','commit','--amend','-F','-'], input=msg.encode('utf-8'))"`。

提交前用 `git log -1 --format=%s` 抽查标题，确认没有前导 `@` 或乱码。

## 提交标题门禁（永久约束）

仓库已配置 `core.hooksPath = tools/git/hooks`，启用 `commit-msg` hook 校验标题第一行：

- 不能为空；
- 不能以空白或 `@` 开头（拦截 PowerShell heredoc 泄露）；
- 必须符合 `type(scope): 摘要` 或 `Merge/Revert/fixup!/squash!` 格式；
- 不超过 72 字符。

校验失败时 `git commit` 直接 abort 并给出原因，**不会**产生需要事后 amend 的脏提交。hook 脚本位于 [tools/git/hooks/commit-msg](tools/git/hooks/commit-msg)，纯 shell，可按需扩展。

## 示例

```markdown
feat(vision): 完成驱动取帧并增加 HUD 近似轮廓

## 动机
固定 FPS 丢帧与 HUD 自摄入会降低分析质量与显示一致性。

## 改动
- 运行时改为完成驱动取帧；HUD 显示时临时隐身并排空可疑帧
- Tracker 用分析序号；`displayContour` 仅供 HUD

## 不变量
- 动作仍只读 `bounds`；C++/JNI 仍为矩形；AUTO 不升权

## 验证
- check_resources / check_project / 轮廓相关 JVM 单测
```
