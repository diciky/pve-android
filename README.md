# PVE Buddy Android

Proxmox VE 移动管理客户端（Android 版），与 iOS 版 [pve-ios](https://github.com/diciky/pve-ios) 功能对等。

## 特性

- 登录与多服务器管理（密码 AES-GCM 加密存储）
- 仪表盘：节点 CPU/内存/存储实时刷新 + 资源汇总
- 资源管理：VM/CT 开关机、快照（创建/回滚/删除）、备份（vzdump）、迁移
- 任务列表 + 任务日志
- 控制面板：节点重启/关机、批量启停、核心服务一键重启
- 系统日志：syslog 服务过滤
- SSH 终端：JSch + PTY、ANSI 基础上色、命令历史
- 局域网扫描：/24 并发探测 8006、接口过滤（排除蜂窝/VPN）、手动指定网段
- App 诊断日志：环形 800 条，可复制导出

## 构建

零 Gradle 路线（aapt2 → javac → d8 → zipalign → apksigner），产物约 180KB。

```bash
# 前置：JDK 17 + Android platform 35 / build-tools 35.0.0
# 路径约定：/tmp/jdk17/jdk-17.0.14+7/Contents/Home 与 /tmp/android-sdk（可用环境变量覆盖）
./build-apk.sh
# 产物：dist/PVEBuddy-v<版本>.apk（内置 debug 密钥签名，可直接安装）
```

## CI

GitHub Actions（`.github/workflows/build.yml`）：push 自动构建 APK + 21 项冒烟测试（mock PVE e2e），产物在 artifact `PVEBuddy-apk`。

## 已知边界

- 终端第一版：ANSI 基础上色（SGR 颜色/加粗），光标定位类转义简化处理，复杂 TUI（vim/htop）渲染可能不全
- 2FA 登录暂不支持
- termproxy WebSocket 串口控制台暂未实现（SSH 终端可用）

## 版本号

见 `CHANGELOG.md` 与 `src/com/pvebuddy/android/BuildInfo.java`（语义化版本，App 内 设置→关于 可查完整历史）。
