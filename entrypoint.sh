#!/bin/sh

# 打印启动信息
echo "=========================================================="
echo "Starting Application..."
echo "JAVA_OPTS: $JAVA_OPTS"
echo "=========================================================="

# 执行传入的命令 (即 Dockerfile CMD 中的内容)
exec "$@"
