#!/usr/bin/env bash
# Source this file in Codex cloud tasks; desktop Android Studio does not need it.
# Each cloud task is already isolated. Use the existing checkout, not a Git worktree.
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    printf '%s\n' 'Source this file: source /workspace/zetaRun/scripts/cloud-env.sh' >&2
    exit 1
fi

if [[ ! -x /workspace/tooling/jdk-21.0.9+10/bin/javac ]]; then
    printf '%s\n' 'Run scripts/cloud-install.sh first to install the full pinned JDK.' >&2
    return 1
fi
if [[ ! -r /etc/ssl/certs/java/cacerts ]]; then
    printf '%s\n' 'The cloud Java trust store is unavailable; restore its trusted CA configuration before building.' >&2
    return 1
fi

export JAVA_HOME=/workspace/tooling/jdk-21.0.9+10
export ANDROID_HOME=/workspace/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME=/workspace/.android
export GRADLE_USER_HOME=/workspace/.gradle

zetarun_path_prefix="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/19.0/bin:"
if [[ "$PATH" != "$zetarun_path_prefix"* ]]; then
    export PATH="$zetarun_path_prefix$PATH"
fi
unset zetarun_path_prefix
