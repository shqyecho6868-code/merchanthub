#!/bin/sh
# 本地手动实验的公共环境：JDK 17 + .env 里的密码（Spring 不会自己读 .env）
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
set -a
. ./.env   # 调用方需先 cd 到仓库根目录
set +a
