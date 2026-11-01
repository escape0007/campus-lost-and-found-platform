#!/bin/zsh
set -eu
cd "$(dirname "$0")"
if ! command -v java >/dev/null 2>&1; then
  print '未找到 Java。请安装 JDK 21 后重试。'
  read '?按回车退出。'
  exit 1
fi
T12_JAR="campus-lost-found-0.1.0.jar"
if [[ ! -f "$T12_JAR" ]]; then
  T12_JAR="target/campus-lost-found-0.1.0.jar"
fi
if [[ ! -f "$T12_JAR" ]]; then
  print '请先在 backend 目录执行 mvn clean package，或使用交付包 app 目录中的已构建版本。'
  read '?按回车退出。'
  exit 1
fi
print 'T12 本地演示：启动后打开 http://127.0.0.1:8080/app/'
print '请先注册账号再登录。打开一条公告可查看匹配建议。建议不能证明物品归属。'
print '账号不代表校园身份认证。按 Ctrl+C 停止。请保留本目录下 data 文件夹，供下次启动继续使用。'
exec java -jar "$T12_JAR" "$@"
