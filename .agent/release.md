# release.md — 发布与交付专项提示词

叠加在 `AGENT.md`、`conventions/git-workflow.md`（标签与版本）、`conventions/docs-style.md`（RELEASE.md 字段）之上，用于准备 Tag、打包、写发布记录和交付自检。

## 1. 发布前冻结

```text
1. 全部 PR 已合并，无未完成的功能分支
2. mvn -B clean verify 通过（含核心 reactor 与两个扩展各自构建）
3. 演示命令实跑通过（npm 场景先构建并复制插件 jar）：
   java -jar licenselens-cli/target/licenselens-cli-0.1.0-SNAPSHOT.jar scan --manifest samples/demo-maven/pom.xml
   java -jar licenselens-cli/target/licenselens-cli-0.1.0-SNAPSHOT.jar scan --manifest samples/demo-maven/pom.xml --policy permissive-only
   cp plugins/licenselens-npm/target/licenselens-npm-0.1.0-SNAPSHOT.jar plugins/
   java -jar licenselens-cli/target/licenselens-cli-0.1.0-SNAPSHOT.jar scan --manifest samples/demo-npm/package-lock.json --plugins plugins
   cp plugins/licenselens-go/target/licenselens-go-0.1.0-SNAPSHOT.jar plugins/
   java -jar licenselens-cli/target/licenselens-cli-0.1.0-SNAPSHOT.jar scan --manifest samples/demo-go/go.mod --plugins plugins
4. README 的构建/运行命令与当前产物名一致，无占位符
5. git status 干净
```

- 版本号按实际成熟度选，通常 `0.1.0`；不强行凑 `1.0.0`。
- 产物名与 jar 版本号要对得上：当前 `pom.xml` 为 `0.1.0-SNAPSHOT`，正式发布前决定是否改为 `0.1.0` 并同步 README 中的命令。

## 2. 打 Tag

```powershell
# 确认最终 Commit
git rev-parse HEAD
git status

# 附注标签，必须带说明
git tag -a v0.1.0 -m "release: LicenseLens v0.1.0 首个可运行版本"

# 校验 Tag 指向 HEAD
git rev-list -n 1 v0.1.0

# 逐个推送（先走确认流程）
git push origin v0.1.0
```

- 用附注标签，不用轻量标签。
- Tag 只能在发布确认后打；**打完不再改动仓库**，否则交付物 SHA-256 与 Tag 内容不一致，只能删 Tag 重打并额外记录。
- 标签推送属 git 写操作，按 `conventions/commit-push-confirm.md` 单独确认。

## 3. 打包与摘要

```powershell
# 在仓库外准备交付目录
New-Item -ItemType Directory -Force ..\组号_LicenseLens\project

# 复制工程（必须包含 .git，不能用 git archive 代替）
Copy-Item -Recurse -Force . ..\组号_LicenseLens\project

# 计算制品摘要
Get-FileHash ..\组号_LicenseLens.zip -Algorithm SHA256
```

- ZIP 结构：`组号_项目名/project/`（完整工程，含 `.git`）+ `组号_项目名/release/`（交付物 + `RELEASE.md`）。
- 排除 `target/`、IDE 目录、缓存与密钥；保留 `samples/`、依赖声明和 CI 配置。
- SHA-256 只用于内容一致性核对，不能单独证明来源或质量。

## 4. 写 RELEASE.md（仓库外）

按 `conventions/docs-style.md` 第 7 节字段填写，重点写实：

- 最终 Commit 完整 SHA、Tag 与目标 Commit；
- 工作区状态、构建与测试的实际结果；
- CI 真实状态（链接或"未托管/未执行"）；
- 交付物名称、环境要求、运行命令；
- 交付物 SHA-256 与原件/副本一致性；
- 核心场景与扩展操作的实际结果；
- 本次变化、已知限制、未验证范围；
- 发布决定：`RELEASE` 或 `HOLD` + 理由。

未完成的核对项写 `HOLD` 并说明缺口，不隐瞒。

## 5. 交付自检

- [ ] 在新目录解压 ZIP，只按 README 与 RELEASE.md 就能构建并运行
- [ ] ZIP 内含 `.git`、源码、测试、`docs/DESIGN.docx`、`LICENSE`、样例、CI 配置
- [ ] 无 `target/`、`.idea/`、密钥、大文件、个人临时脚本
- [ ] `docs/DESIGN.docx` 章节齐全，含架构图与流程图，且与提交代码一致
- [ ] README 无占位符，成员贡献与 AI 核验表已由本人填写
- [ ] `git status` 干净，Tag 指向最终 Commit，SHA-256 与 `RELEASE.md` 一致

## 6. 禁止

- 不虚构 CI 运行、测试结果、SHA-256 或运行截图。
- 不打完 Tag 再改仓库、改版本号或补提交。
- 不用 `git archive` 生成交付 ZIP，不把工作区缓存打进包。
- 不在未通过 `mvn -B clean verify` 的情况下发布。
- 不把发布记录写进仓库内（会破坏"最终 Commit 后再改仓库"的一致性要求）。
