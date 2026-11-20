# conventions/ — 通用规范索引

存放跨任务的通用规范：代码风格、注释规范、文档规范、git 工作流、提交确认协议。与根目录的场景型提示词（`review` / `test-writer` / `docs-writer` / `release`）不同，本目录是所有任务共享的约束，按主题拆分、独立维护。

## 文件与触发时机

| 文件 | 什么时候读 | 什么时候不读 |
| --- | --- | --- |
| `code-style.md` | 写代码、改代码、审查代码 | 纯文档任务 |
| `comment-style.md` | 写注释、补 Javadoc、清理 TODO | 不涉及代码注释的纯配置改动 |
| `docs-style.md` | 写任何 Markdown 或 DESIGN.docx | 纯代码任务 |
| `git-workflow.md` | 做任何 git 写操作前（branch/commit/merge/tag） | 只读 git 命令（status/log/diff） |
| `commit-push-confirm.md` | 执行 commit / push / merge / rebase / tag / 删除分支 / 改历史之前 | 无例外，写操作一律先读 |

## 与 AGENT.md 的关系

- `AGENT.md` 定义角色、任务类型、项目硬约束与默认工作流。
- 本目录是主提示词的外挂规范库，只被引用，不被复制；`AGENT.md` 不重复这里的细则。
- 本目录内容与 `AGENT.md` 的硬约束 N1–N12 冲突时，以更严格的一侧为准，并回头修正另一处。

## 内容边界

- 只写"怎么做才合规"，不写具体功能的实现方案。
- 不写成员姓名、固定分工；模块规则用模块名表达（`spi` / `core` / `cli` / `npm` / `go`）。
- 项目事实（退出码、接口签名、目录结构）在仓库代码与 `docs/CONTRACT.md` 中维护；规范里只引用其形态，不整段复制。

## 修改规则

1. 新增规范主题时新建独立 `.md`，并在本 README 与 `../README.md` 中登记，不塞进现有文件。
2. 修改后自检：是否与契约冲突？是否与 `AGENT.md` 硬约束冲突？是否引入了具体人名或固定分工？
3. 规范的增删属于协作流程变更，改动说明写进 PR 描述，需要时同步更新 `docs/LicenseLens分工合作书.md`。
