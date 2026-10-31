#!/bin/sh
# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -eu
app_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
if [ -n "${JAVA_HOME:-}" ]; then
    java_exec="$JAVA_HOME/bin/java"
else
    java_exec=java
fi
exec "$java_exec" "-Dorg.gradle.appname=gradlew" \
    -classpath "$app_dir/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain "$@"
