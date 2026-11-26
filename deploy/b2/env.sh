#!/bin/sh
# Shared environment for local manual experiments: JDK 17 + passwords from .env (Spring doesn't read .env on its own)
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
set -a
. ./.env   # the caller must cd to the repo root first
set +a
