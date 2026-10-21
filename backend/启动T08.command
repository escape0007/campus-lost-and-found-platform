#!/bin/zsh
set -eu
cd "$(dirname "$0")"
if ! command -v java >/dev/null 2>&1; then
  print '未找到 Java。请安装 JDK 21–25 后重试（本机验证使用 JDK 25.0.2）。'
  read '?按回车退出。'
  exit 1
fi
T08_JAR="campus-lost-found-0.1.0.jar"
if [[ ! -f "$T08_JAR" ]]; then
  T08_JAR="target/campus-lost-found-0.1.0.jar"
fi
if [[ ! -f "$T08_JAR" ]]; then
  print '请先在 backend 目录执行 mvn clean package，或使用交付包中的已构建版本。'
  read '?按回车退出。'
  exit 1
fi
print 'T08 本地演示：启动成功后打开 http://127.0.0.1:8080/app/'
print '账号 alice 或 bob；密码 campus-demo-2026。按 Ctrl+C 停止。'
print '请保留本目录下 data 文件夹，供下次启动继续使用。'
exec java -jar "$T08_JAR" "$@"
