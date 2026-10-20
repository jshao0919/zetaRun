#!/usr/bin/env bash
# Reproduce the Linux x86_64 cloud prerequisites without changing application source.
# This refreshes retained dependencies; live services/processes are not retained.
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tooling_dir=/workspace/tooling
jdk_dir="$tooling_dir/jdk-21.0.9+10"
sdk_dir=/workspace/android-sdk
cmdline_dir="$sdk_dir/cmdline-tools/19.0"
trust_store=/etc/ssl/certs/java/cacerts

[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || {
    printf '%s\n' 'This bootstrap is pinned for the Linux x86_64 cloud machine.' >&2
    exit 1
}
for required_command in curl python3 tar unzip sha256sum sha1sum; do
    command -v "$required_command" >/dev/null || {
        printf 'Required tool is missing: %s\n' "$required_command" >&2
        exit 1
    }
done
[[ -r "$trust_store" ]] || {
    printf '%s\n' 'The cloud Java trust store is missing. Restore trusted CA configuration before installing.' >&2
    exit 1
}
mkdir -p "$tooling_dir" "$sdk_dir/cmdline-tools" /workspace/.android /workspace/.gradle

# TLS and published archive checksums stay enabled, including on redirects.
fetch_verified() {
    local archive="$1" url="$2" algorithm="$3" expected="$4"
    if [[ -f "$archive" ]] && printf '%s  %s\n' "$expected" "$archive" | "${algorithm}sum" --check --status; then
        return
    fi
    local partial
    partial="$(mktemp "$tooling_dir/.download.XXXXXX")"
    if ! curl --fail --location --silent --show-error --retry 3 --connect-timeout 30 \
        --proto '=https' --proto-redir '=https' --tlsv1.2 "$url" -o "$partial"; then
        rm -f "$partial"
        return 1
    fi
    if ! printf '%s  %s\n' "$expected" "$partial" | "${algorithm}sum" --check --status; then
        rm -f "$partial"
        printf '%s\n' 'Downloaded tool checksum mismatch; refusing to use the artifact.' >&2
        return 1
    fi
    mv "$partial" "$archive"
}

if [[ -x "$jdk_dir/bin/java" && -x "$jdk_dir/bin/javac" && -f "$jdk_dir/jmods/jdk.compiler.jmod" ]] \
    && [[ -f "$jdk_dir/release" ]] && grep -q '^JAVA_VERSION="21.0.9"$' "$jdk_dir/release"; then
    printf '%s\n' 'Pinned full JDK already installed.'
else
    [[ ! -e "$jdk_dir" ]] || {
        printf '%s\n' 'Existing pinned JDK is incomplete. Inspect and move it aside before rerunning; it was preserved.' >&2
        exit 1
    }
    fetch_verified "$tooling_dir/jdk21.tar.gz" \
        'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz' \
        sha256 810d3773df7e0d6c4394e4e244b264c8b30e0b05a0acf542d065fd78a6b65c2f
    jdk_stage="$(mktemp -d "$tooling_dir/.jdk-install.XXXXXX")"
    tar -xzf "$tooling_dir/jdk21.tar.gz" -C "$jdk_stage"
    [[ -x "$jdk_stage/jdk-21.0.9+10/bin/javac" ]]
    mv "$jdk_stage/jdk-21.0.9+10" "$jdk_dir"
    rmdir "$jdk_stage"
fi

if [[ -x "$cmdline_dir/bin/sdkmanager" && -f "$cmdline_dir/lib/sdkmanager-classpath.jar" ]] \
    && [[ -f "$cmdline_dir/source.properties" ]] && grep -q '^Pkg.Revision=19.0$' "$cmdline_dir/source.properties"; then
    printf '%s\n' 'Pinned Android command-line tools already installed.'
else
    [[ ! -e "$cmdline_dir" ]] || {
        printf '%s\n' 'Existing Android command-line tools are incomplete. Inspect and move them aside before rerunning; they were preserved.' >&2
        exit 1
    }
    # SHA-1 is published in Google's repository metadata, retrieved over verified TLS.
    fetch_verified "$tooling_dir/android-tools-19.zip" \
        'https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip' \
        sha1 5fdcc763663eefb86a5b8879697aa6088b041e70
    android_stage="$(mktemp -d "$tooling_dir/.android-install.XXXXXX")"
    unzip -q "$tooling_dir/android-tools-19.zip" -d "$android_stage"
    [[ -x "$android_stage/cmdline-tools/bin/sdkmanager" ]]
    mv "$android_stage/cmdline-tools" "$cmdline_dir"
    rmdir "$android_stage"
fi

# shellcheck source=cloud-env.sh
source "$project_dir/scripts/cloud-env.sh"

# Keep cloud transport settings outside the checkout. Never print proxy URLs,
# persist proxy credentials, or replace unrelated user Gradle properties.
python3 - <<'PY'
import os
from pathlib import Path
import re
import shlex
import tempfile
from urllib.parse import urlsplit

path = Path(os.environ['GRADLE_USER_HOME']) / 'gradle.properties'
original = path.read_text() if path.exists() else ''
lines = original.splitlines(keepends=True)
property_line = re.compile(r'^\s*([A-Za-z0-9_.-]+)(?:\s*[=:]\s*|\s+)(.*)$')
existing = {}
for line in lines:
    match = property_line.match(line.rstrip('\r\n'))
    if match:
        existing[match[1]] = match[2]

managed_prefixes = ('-Xmx', '-XX:ActiveProcessorCount=', '-Dfile.encoding=', '-Djavax.net.ssl.trustStore=')
existing_jvm_args = existing.get('org.gradle.jvmargs', '')
if existing_jvm_args.rstrip().endswith('\\'):
    raise SystemExit('A multiline org.gradle.jvmargs setting needs manual merging; the original file was preserved.')
try:
    retained = [arg for arg in shlex.split(existing_jvm_args) if not arg.startswith(managed_prefixes)]
except ValueError:
    raise SystemExit('The existing JVM arguments could not be parsed; the original file was preserved.') from None
required = ['-Xmx3g', '-XX:ActiveProcessorCount=4', '-Dfile.encoding=UTF-8',
            '-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts']
updates = {
    'org.gradle.jvmargs': shlex.join(retained + required),
    'systemProp.javax.net.ssl.trustStore': '/etc/ssl/certs/java/cacerts',
}
proxy = os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
if proxy:
    try:
        parsed = urlsplit(proxy)
        if parsed.scheme not in ('http', 'https') or not parsed.hostname:
            raise ValueError()
        host = parsed.hostname
        port = parsed.port or (443 if parsed.scheme == 'https' else 80)
    except ValueError:
        raise SystemExit('HTTPS_PROXY has an unsupported host or port; no proxy settings were changed.') from None
    for protocol in ('http', 'https'):
        updates[f'systemProp.{protocol}.proxyHost'] = host
        updates[f'systemProp.{protocol}.proxyPort'] = str(port)
    if 'systemProp.http.nonProxyHosts' not in existing:
        updates['systemProp.http.nonProxyHosts'] = 'localhost|127.*'

out = []
written = set()
for line in lines:
    match = property_line.match(line.rstrip('\r\n'))
    if match and match[1] in updates:
        key = match[1]
        if match[2].rstrip().endswith('\\'):
            raise SystemExit('A multiline managed Gradle property needs manual merging; the original file was preserved.')
        if key not in written:
            out.append(f'{key}={updates[key]}\n')
            written.add(key)
    else:
        out.append(line)
if out and not out[-1].endswith('\n'):
    out[-1] += '\n'
for key, value in updates.items():
    if key not in written:
        out.append(f'{key}={value}\n')
result = ''.join(out)
if result != original:
    descriptor, name = tempfile.mkstemp(prefix='.gradle-properties-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w') as stream:
            stream.write(result)
        if path.exists():
            os.chmod(name, path.stat().st_mode & 0o777)
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)
print('User-local Gradle proxy and trusted CA settings prepared.')
PY

# Install only missing SDK targets; a restored snapshot does not need fresh downloads.
sdk_packages=()
[[ -f "$sdk_dir/platforms/android-36/android.jar" && -f "$sdk_dir/platforms/android-36/package.xml" ]] \
    || sdk_packages+=("platforms;android-36")
[[ -x "$sdk_dir/build-tools/36.0.0/aapt2" && -x "$sdk_dir/build-tools/36.0.0/apksigner" \
    && -f "$sdk_dir/build-tools/36.0.0/package.xml" ]] || sdk_packages+=("build-tools;36.0.0")
[[ -x "$sdk_dir/platform-tools/adb" && -f "$sdk_dir/platform-tools/package.xml" ]] \
    || sdk_packages+=("platform-tools")
if (( ${#sdk_packages[@]} )); then
    # sdkmanager needs the cloud CA too, before Gradle has started. JAVA_OPTS is
    # scoped to these invocations, preserving any options supplied by the user.
    sdk_java_opts="${JAVA_OPTS:-} -XX:ActiveProcessorCount=4 -Djavax.net.ssl.trustStore=$trust_store"
    JAVA_OPTS="$sdk_java_opts" python3 - "$cmdline_dir/bin/sdkmanager" "$sdk_dir" "${sdk_packages[@]}" <<'PY'
import os
import subprocess
import sys
from urllib.parse import urlsplit

command = [sys.argv[1], f'--sdk_root={sys.argv[2]}']
proxy = os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
if proxy:
    parsed = urlsplit(proxy)  # Host/port were validated before writing Gradle settings.
    command += ['--proxy=http', f'--proxy_host={parsed.hostname}',
                f'--proxy_port={parsed.port or (443 if parsed.scheme == "https" else 80)}']
result = subprocess.run(command + ['--licenses'], input='y\n' * 1000, text=True)
if result.returncode:
    raise SystemExit(result.returncode)
raise SystemExit(subprocess.run(command + sys.argv[3:]).returncode)
PY
else
    printf '%s\n' 'Required Android SDK packages already installed.'
fi

# Preserve pre-existing per-user/local settings, including custom sdk.dir choices.
if [[ ! -e "$project_dir/local.properties" ]]; then
    printf 'sdk.dir=%s\n' "$sdk_dir" > "$project_dir/local.properties"
fi

cd "$project_dir"
./scripts/prepare-model.sh
"$JAVA_HOME/bin/javac" -J-XX:ActiveProcessorCount=4 -version
printf '%s\n' 'Cloud prerequisites prepared. Source scripts/cloud-env.sh before running ./gradlew.'
