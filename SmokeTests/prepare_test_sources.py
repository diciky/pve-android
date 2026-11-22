#!/usr/bin/env python3
"""组装 JVM 冒烟测试源码：复制 core 类 + AppLogger 剥离 android 依赖。

AppLogger 的落盘依赖 android.content.Context 与 File 落盘，
JVM 冒烟只需要内存环形缓冲——本脚本生成剥离版测试副本。
"""
import re
import shutil
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
BUILD = ROOT / "SmokeTests" / "build"
BUILD.mkdir(exist_ok=True)

shutil.copy(ROOT / "src/com/pvebuddy/android/core/PveClient.java", BUILD)
shutil.copy(ROOT / "src/com/pvebuddy/android/core/LocalScanner.java", BUILD)

src = (ROOT / "src/com/pvebuddy/android/core/AppLogger.java").read_text()
src = src.replace("import android.content.Context;\n\n", "")
src = re.sub(r"    public void init\(Context ctx\) \{[\s\S]*?\n    \}\n", "", src, count=1)
src = src.replace("    public File logFile() { return file; }\n\n", "")
src = re.sub(r"import java\.io\.File;\n", "", src)
src = src.replace("    private File file;\n", "")
# 删除落盘写块与清空重建块
src = re.sub(r"            if \(logFile != null\) \{\n                try \{\n                    logFile\.write[\s\S]*?catch \(Exception ignore\) \{ \}\n            \}\n", "", src)
src = re.sub(r"            if \(logFile != null\) \{\n                try \{ logFile\.close\(\); \} catch \(Exception ignore\) \{ \}\n[\s\S]*?\n            \}\n", "", src)
# build 目录副本不带 android 包名问题：包名不变，直接用
(BUILD / "AppLogger.java").write_text(src)
print("测试源码就绪:", BUILD)
