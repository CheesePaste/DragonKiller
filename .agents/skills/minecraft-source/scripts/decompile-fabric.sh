#!/usr/bin/env bash
# Extract and display a Minecraft source file using Fabric Loom's generated sources jar.
# The sources jar already contains decompiled Java with Yarn (human-readable) names.
#
# Usage: decompile.sh <class-name>
# Examples:
#   decompile.sh net.minecraft.util.ActionResult
#   decompile.sh net/minecraft/util/ActionResult
#   decompile.sh ActionResult
#   decompile.sh ActionResult.Success       (inner class, extracts outer file)

set -euo pipefail

CLASS_INPUT="$1"

# ---------- 1. Find project root ----------
find_project_root() {
    local dir
    dir="$(pwd)"
    while [[ "$dir" != "/" && "$dir" != "" ]]; do
        if [[ -f "$dir/gradle.properties" ]]; then
            echo "$dir"
            return 0
        fi
        dir="$(dirname "$dir")"
    done
    echo ""
}

PROJECT_ROOT="$(find_project_root)"
if [[ -z "$PROJECT_ROOT" ]]; then
    echo "ERROR: Not in a Fabric mod project." >&2
    exit 1
fi

# ---------- 2. Find the Yarn-mapped sources jar ----------
# Location: .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/
#           .../<version>-net.fabricmc.yarn.<version>-v2/*-sources.jar
LOOM_MC_DIR="$PROJECT_ROOT/.gradle/loom-cache/minecraftMaven/net/minecraft"

if [[ ! -d "$LOOM_MC_DIR" ]]; then
    echo "ERROR: Loom cache not found at: $LOOM_MC_DIR" >&2
    echo "       Run './gradlew build' or './gradlew genSources' first." >&2
    exit 1
fi

MC_VERSION=$(grep -E '^minecraft_version=' "$PROJECT_ROOT/gradle.properties" | cut -d= -f2 | tr -d '[:space:]')
# Match jar whose path contains the MC version (e.g. "1.21.10-net.fabricmc.yarn...")
ALL_SOURCES_JARS=()
while IFS= read -r jar; do
    [[ -n "$jar" ]] && ALL_SOURCES_JARS+=("$jar")
done < <(find "$LOOM_MC_DIR" -name "*-sources.jar" 2>/dev/null | grep -F "${MC_VERSION}-")

if [[ ${#ALL_SOURCES_JARS[@]} -eq 0 ]]; then
    # Fallback to any versions
    while IFS= read -r jar; do
        [[ -n "$jar" ]] && ALL_SOURCES_JARS+=("$jar")
    done < <(find "$LOOM_MC_DIR" -name "*-sources.jar" 2>/dev/null)
fi

if [[ ${#ALL_SOURCES_JARS[@]} -eq 0 ]]; then
    echo "ERROR: No sources jar found in $LOOM_MC_DIR" >&2
    echo "       Run './gradlew genSources' first to generate decompiled sources." >&2
    exit 1
fi

# ---------- 3. Convert class name to file path ----------
# Input:  net.minecraft.util.ActionResult, net/minecraft/util/ActionResult, ActionResult
# Output: net/minecraft/util/ActionResult.java
class_to_path() {
    local name="$1"
    # Strip inner class suffix ($Foo) for file lookup (inner classes are in the same file)
    name="${name%%\$*}"
    # Convert dots to slashes: net.minecraft.util.ActionResult -> net/minecraft/util/ActionResult
    name="${name//.//}"
    echo "${name}.java"
}

# Wrapper for jar tf to handle Windows native Java in MSYS/Git Bash/WSL
list_jar() {
    local jar="$1"
    local jar_cmd="jar"
    if ! command -v jar >/dev/null 2>&1 && command -v jar.exe >/dev/null 2>&1; then
        jar_cmd="jar.exe"
    fi
    if command -v cygpath >/dev/null 2>&1; then
        jar=$(cygpath -w "$jar")
    elif command -v wslpath >/dev/null 2>&1; then
        jar=$(wslpath -w "$jar")
    fi
    "$jar_cmd" tf "$jar" 2>/dev/null | tr -d '\r'
}

# Helper to search all jars
find_in_jars() {
    local query="$1"
    for jar in "${ALL_SOURCES_JARS[@]}"; do
        local m=$(list_jar "$jar" | grep -i "/${query}\.java" | grep -v '[$]' || true)
        if [[ -n "$m" ]]; then
            echo "$jar|$m"
            return 0
        fi
    done
    return 1
}

if [[ "$CLASS_INPUT" != *"."* && "$CLASS_INPUT" != *"/"* && "$CLASS_INPUT" != *'$'* ]]; then
    SHORT_NAME="$CLASS_INPUT"
    RESULT=$(find_in_jars "$SHORT_NAME" || true)
    if [[ -z "$RESULT" ]]; then
        echo "ERROR: No source file found with name '${SHORT_NAME}.java'" >&2
        exit 1
    fi
    SOURCES_JAR="${RESULT%%|*}"
    MATCHES="${RESULT#*|}"
    MATCH_COUNT=$(echo "$MATCHES" | grep -c . || true)
    if [[ "$MATCH_COUNT" -gt 1 ]]; then
        echo "Multiple matches for '${SHORT_NAME}':" >&2
        echo "$MATCHES" | while read -r line; do echo "  $line" >&2; done
        echo "Please re-run with a full class name." >&2
        exit 1
    fi
    FILE_PATH="$MATCHES"
else
    FILE_PATH=$(class_to_path "$CLASS_INPUT")
    if [[ "$FILE_PATH" != *"/"* ]]; then
        SHORT_NAME="${FILE_PATH%.java}"
        RESULT=$(find_in_jars "$SHORT_NAME" || true)
        if [[ -n "$RESULT" ]]; then
            SOURCES_JAR="${RESULT%%|*}"
            FILE_PATH=$(echo "${RESULT#*|}" | head -1)
        fi
    fi
    
    # Verify file exists
    FOUND_JAR=""
    if [[ -n "${SOURCES_JAR:-}" ]] && list_jar "$SOURCES_JAR" | grep "^${FILE_PATH}$" >/dev/null; then
        FOUND_JAR="$SOURCES_JAR"
    else
        for jar in "${ALL_SOURCES_JARS[@]}"; do
            if list_jar "$jar" | grep "^${FILE_PATH}$" >/dev/null; then
                FOUND_JAR="$jar"
                break
            fi
        done
    fi
    
    if [[ -z "$FOUND_JAR" ]]; then
        BASE_NAME="${CLASS_INPUT##*.}"
        RESULT=$(find_in_jars "$BASE_NAME" || true)
        if [[ -n "$RESULT" ]]; then
            FOUND_JAR="${RESULT%%|*}"
            FILE_PATH=$(echo "${RESULT#*|}" | head -1)
        else
            echo "ERROR: '${FILE_PATH}' not found in any sources jar." >&2
            echo "       Check the class name." >&2
            exit 1
        fi
    fi
    SOURCES_JAR="$FOUND_JAR"
fi

# ---------- 4. Extract and print ----------
echo "--- Minecraft ${MC_VERSION} ---"
echo "Source:  ${FILE_PATH}"
echo "Jar:     ${SOURCES_JAR}"
echo ""

unzip -p "$SOURCES_JAR" "$FILE_PATH" 2>/dev/null || {
    echo "ERROR: Failed to extract ${FILE_PATH} from sources jar." >&2
    exit 1
}
