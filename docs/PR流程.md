# PR 提交流程

> 本仓库是**开发正本**；给上游 `houxinlin/cool-request-tools` 提 PR 走 fork 分支 `rabbitmq-tool`（**投放镜像**）。
> 两边内容**本来就该不一样**（凭据隔离：Smoke\*/probe_test 只在本仓库，不进 PR），PR 评审对象是镜像分支。

## 角色分工

| 仓库 | 角色 | 内容 |
|---|---|---|
| `SongRenShuo/cool-request-tool-rabbitmq`（本仓库） | 开发正本 | 完整源码 + docs + Smoke 测试（凭据已 gitignore）+ 产物 jar |
| `SongRenShuo/cool-request-tools` fork 的 `rabbitmq-tool` 分支 | PR 投放镜像 | 仅 `tools-rep/rabbitmq/`：源码 + lib + 构建脚本 + README + jar + harness |
| `houxinlin/cool-request-tools` | 上游 | PR #1 目标，作者合并 + 后台录入才上架 |

## 流程图

```
┌─────────────────────────────────────────────────────────────┐
│  ① 开发正本（本仓库 master）                                  │
│     改代码 → 本地构建 build-tool.sh → harness 回归           │
│     → git commit → git push origin master                    │
└──────────────────────────┬──────────────────────────────────┘
                           │ 白名单同步（只拷该给作者的）
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  ② PR 镜像（cool-request-tools-upstream 工作区               │
│              分支 rabbitmq-tool → tools-rep/rabbitmq/）      │
│     checkout 白名单源码 + 拷 jar + commit（中文）+ push       │
└──────────────────────────┬──────────────────────────────────┘
                           │ push 自动更新已开的 PR #1
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  ③ 上游 PR #1（houxinlin/cool-request-tools）                │
│     作者评审合并 → coolrequest.dev 后台录入 → 上架            │
└─────────────────────────────────────────────────────────────┘
```

## 逐步命令

### ① 本仓库：提交开发改动

```bash
cd /f/code/Java/Github/SongRenShuo/cool-request-tool-rabbitmq
bash build-tool.sh                                  # 构建 fat jar
# harness 回归（凭据走命令行参数，MSYS2 必须排除路径转换）
export MSYS2_ARG_CONV_EXCL="*"
JBR="C:/Users/USER/AppData/Local/Programs/IntelliJ IDEA/jbr"
DEPS=$(ls lib/*.jar | tr '\n' ';')
"$JBR/bin/javac.exe" --release 17 -encoding UTF-8 -cp "build/libs/cool-request-tool-rabbitmq-1.0-SNAPSHOT.jar;$DEPS" \
  -d test/manual/classes test/manual/AmqpErrorsHarness.java
"$JBR/bin/java.exe" -cp "build/libs/cool-request-tool-rabbitmq-1.0-SNAPSHOT.jar;test/manual/classes;$DEPS" \
  AmqpErrorsHarness <host> <port> <vhost> <user> <password>   # 全绿再走下一步
git add -A && git commit -m "..." && git push
```

### ② 同步到 PR 镜像并推送

```bash
UP=/f/code/Java/Github/other/houxinlin/cool-request-tools-upstream
DEV=/f/code/Java/Github/SongRenShuo/cool-request-tool-rabbitmq
cd $UP && git checkout rabbitmq-tool

# 白名单 checkout（凭据文件天然隔离，永远别整仓同步）
git checkout rabbitmq-dev -- src lib build-tool.sh build-tool.bat README.md
#   ↑ 若未配 remote，先执行一次：
#   git remote add rabbitmq-dev $DEV && git fetch rabbitmq-dev
cp $DEV/build/libs/cool-request-tool-rabbitmq-1.0-SNAPSHOT.jar tools-rep/rabbitmq/
cp $DEV/test/manual/AmqpErrorsHarness.java tools-rep/rabbitmq/test/manual/   # 凭据走参数版，可入库

git add -A && git commit -m "fix/feat: 中文描述"
# push 必须绕 ghfast.top 重写：显式 URL + 本地代理，-c 在子命令前
git -c http.proxy=http://127.0.0.1:7890 push \
  "https://SongRenShuo@github.com/SongRenShuo/cool-request-tools.git" rabbitmq-tool
```

### ③ PR 维护

- push 后已开的 [PR #1](https://github.com/houxinlin/cool-request-tools/pull/1) 自动带上新 commit，无需重开。
- 首次建 PR：GitHub 网页或 `gh pr create`（base=houxinlin:main，head=SongRenShuo:rabbitmq-tool），标题描述写中文，注明 Widget Store 上架需作者后台录入。

## 纪律清单（每轮 push 前过一遍）

- [ ] **凭据终检**：`git -C $UP grep -E "bxGyxEy|114\.66\.55" -- tools-rep/rabbitmq` 零命中（Smoke\*/probe_test 永不进镜像）
- [ ] jar 已用最新构建刷新（镜像根目录与本仓库 `build/libs/` 一致）
- [ ] commit 信息中文
- [ ] 版本号保持 `1.0-SNAPSHOT` 不动（生态定例，升级走商店后台 checksum/minVersion）
- [ ] 测试服务器 `cr.ui.*` 种子用完即清（管理 API 20071 清点复核）

## 已知平台坑速查

- push 用 `-c http.proxy=...` 前置 + `https://SongRenShuo@github.com/...` 显式 URL（全局 insteadOf 把 github.com 重写成 ghfast.top 只读代理）。
- git bash 跑 `java -cp` 带 `;` 必须 `export MSYS2_ARG_CONV_EXCL="*"`。
- GitHub 登录名 SongRenShuo（GCM 用户名标签 XiaoSongTX 与实际 login 不一致，勿被误导）。
