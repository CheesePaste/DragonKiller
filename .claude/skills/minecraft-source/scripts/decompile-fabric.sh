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

# ---------- 2. Find all Yarn-mapped sources jars ----------
# Fabric Loom splits sources: minecraft-common (server) + minecraft-clientOnly (client)
# We need to search ALL of them since classes are distributed across jars.
LOOM_MC_DIR="$PROJECT_ROOT/.gradle/loom-cache/minecraftMaven/net/minecraft"

if [[ ! -d "$LOOM_MC_DIR" ]]; then
    echo "ERROR: Loom cache not found at: $LOOM_MC_DIR" >&2
    echo "       Run './gradlew build' or './gradlew genSources' first." >&2
    exit 1
fi

MC_VERSION=$(grep -E '^minecraft_version=' "$PROJECT_ROOT/gradle.properties" | cut -d= -f2 | tr -d '[:space:]')
# Collect ALL sources jars matching this MC version (common + clientOnly)
SOURCES_JARS=()
while IFS= read -r jar; do
    SOURCES_JARS+=("$jar")
done < <(find "$LOOM_MC_DIR" -name "*-sources.jar" 2>/dev/null | grep -F "${MC_VERSION}-" | sort)

if [[ ${#SOURCES_JARS[@]} -eq 0 ]]; then
    echo "ERROR: No sources jar found in $LOOM_MC_DIR" >&2
    echo "       Run './gradlew genSources' first to generate decompiled sources." >&2
    exit 1
fi

# Helper: list all .java entries across all source jars
list_all_sources() {
    local pattern="${1:-}"
    for jar in "${SOURCES_JARS[@]}"; do
        if [[ -n "$pattern" ]]; then
            unzip -Z1 "$jar" 2>/dev/null | grep -i "$pattern" || true
        else
            unzip -Z1 "$jar" 2>/dev/null
        fi
    done
}

# Helper: find which jar contains a given file path
find_jar_for_file() {
    local file_path="$1"
    for jar in "${SOURCES_JARS[@]}"; do
        if unzip -Z1 "$jar" 2>/dev/null | grep "^${file_path}$" >/dev/null; then
            echo "$jar"
            return 0
        fi
    done
    return 1
}

# ---------- 3. Convert class name to file path ----------
# Input:  net.minecraft.util.ActionResult, net/minecraft/util/ActionResult, ActionResult
# Output: net/minecraft/util/ActionResult.java
class_to_path() {
    local name="$1"
    # Strip inner class suffix ($Foo or .Foo) for file lookup (inner classes are in the same file)
    name="${name%%\$*}"
    # Only strip inner class suffix for simple names (≤1 dot: e.g. "ActionResult.Success")
    # Names with 2+ dots are fully qualified package paths and must not be stripped
    if [[ "$name" != *.*.* ]]; then
        name="${name%%.*}"
    fi
    # Convert dots to slashes: net.minecraft.util.ActionResult -> net/minecraft/util/ActionResult
    name="${name//.//}"
    echo "${name}.java"
}

# Short name search: find full path from jar contents
if [[ "$CLASS_INPUT" != *"."* && "$CLASS_INPUT" != *"/"* && "$CLASS_INPUT" != *'$'* ]]; then
    SHORT_NAME="$CLASS_INPUT"
    MATCHES=$(list_all_sources "/${SHORT_NAME}\.java")
    MATCH_COUNT=$(echo "$MATCHES" | wc -l | tr -d '[:space:]')
    if [[ "$MATCH_COUNT" -eq 0 ]]; then
        echo "ERROR: No source file found with name '${SHORT_NAME}.java'" >&2
        exit 1
    elif [[ "$MATCH_COUNT" -gt 1 ]]; then
        echo "Multiple matches for '${SHORT_NAME}':" >&2
        echo "$MATCHES" | while read -r line; do echo "  $line" >&2; done
        echo "Please re-run with a full class name." >&2
        exit 1
    fi
    FILE_PATH="$MATCHES"
else
    FILE_PATH=$(class_to_path "$CLASS_INPUT")
    # If result has no package path (e.g. inner class "ActionResult.Success" -> "ActionResult.java"),
    # search all jars for the full path
    if [[ "$FILE_PATH" != *"/"* ]]; then
        SHORT_NAME="${FILE_PATH%.java}"
        MATCHES=$(list_all_sources "/${SHORT_NAME}\.java")
        MATCH_COUNT=$(echo "$MATCHES" | wc -l | tr -d '[:space:]')
        if [[ "$MATCH_COUNT" -ge 1 ]]; then
            FILE_PATH=$(echo "$MATCHES" | head -1)
        fi
    fi
fi

# Verify file exists across all jars
SOURCES_JAR=$(find_jar_for_file "$FILE_PATH")
if [[ -z "$SOURCES_JAR" ]]; then
    # Try fallback: maybe the user provided dot notation for inner class
    BASE_NAME="${CLASS_INPUT##*.}"
    for jar in "${SOURCES_JARS[@]}"; do
        MATCHES=$(unzip -Z1 "$jar" 2>/dev/null | grep -i "/${BASE_NAME}\.java" | grep -v '[$]' || true)
        if [[ -n "$MATCHES" ]]; then
            FILE_PATH=$(echo "$MATCHES" | head -1)
            SOURCES_JAR="$jar"
            break
        fi
    done
    if [[ -z "$SOURCES_JAR" ]]; then
        echo "ERROR: '${FILE_PATH}' not found in any sources jar." >&2
        echo "       Check the class name." >&2
        exit 1
    fi
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
