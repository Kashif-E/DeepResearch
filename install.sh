#!/usr/bin/env bash
set -eo pipefail

# ─────────────────────────────────────────────────────
#  Delve — Install Script
#  Works on macOS, Linux, and Windows (Git Bash/WSL)
# ─────────────────────────────────────────────────────

REPO="${DELVE_REPO:-https://github.com/kashif-e/delve.git}"
GRAAL_VERSION="25.0.2-graal"
MIN_JDK="25"
BIN_NAME="delve"
CONFIG_DIR="$HOME/.config/delve"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
DIM='\033[2m'
BOLD='\033[1m'
RESET='\033[0m'

info()  { echo -e "  ${GREEN}✓${RESET} $1"; }
warn()  { echo -e "  ${YELLOW}⚠${RESET} $1"; }
err()   { echo -e "  ${RED}✗${RESET} $1"; }
step()  { echo -e "\n  ${BOLD}$1${RESET}"; }

# ── Detect OS ────────────────────────────────────────

detect_os() {
    case "$(uname -s)" in
        Darwin*)  OS="macos" ;;
        Linux*)   OS="linux" ;;
        MINGW*|MSYS*|CYGWIN*) OS="windows" ;;
        *)        err "Unsupported OS: $(uname -s)"; exit 1 ;;
    esac

    case "$(uname -m)" in
        x86_64|amd64)  ARCH="x64" ;;
        arm64|aarch64) ARCH="arm64" ;;
        *)             err "Unsupported architecture: $(uname -m)"; exit 1 ;;
    esac
}

# ── Install directory ────────────────────────────────

detect_install_dir() {
    if [ "$OS" = "windows" ]; then
        INSTALL_DIR="$HOME/AppData/Local/Programs/delve"
    else
        INSTALL_DIR="$HOME/.local/bin"
    fi
    mkdir -p "$INSTALL_DIR"
}

# ── SDKMAN ───────────────────────────────────────────

ensure_sdkman() {
    if [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
        source "$HOME/.sdkman/bin/sdkman-init.sh"
        info "SDKMAN found"
        return
    fi

    step "Installing SDKMAN..."
    curl -s "https://get.sdkman.io?rcupdate=false" | bash
    source "$HOME/.sdkman/bin/sdkman-init.sh"
    info "SDKMAN installed"
}

# ── GraalVM ──────────────────────────────────────────

graalvm_version_ok() {
    local java_bin="$1/bin/java"
    [ -f "$java_bin" ] || return 1
    local ver
    ver="$("$java_bin" -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\).*/\1/')"
    [ "$ver" -ge "$MIN_JDK" ] 2>/dev/null
}

ensure_graalvm() {
    source "$HOME/.sdkman/bin/sdkman-init.sh"

    # Check if current JAVA_HOME is already a suitable GraalVM
    if [ -n "${JAVA_HOME:-}" ] && [ -f "$JAVA_HOME/bin/native-image" ] && graalvm_version_ok "$JAVA_HOME"; then
        info "GraalVM found: $JAVA_HOME"
        return
    fi

    # Check common manual install locations (prefer newest first)
    for candidate in \
        "$HOME/Library/Java/JavaVirtualMachines"/graalvm-jdk-*/Contents/Home \
        "$HOME/Library/Java/JavaVirtualMachines"/graalvm-*/Contents/Home \
        /Library/Java/JavaVirtualMachines/graalvm-*.jdk/Contents/Home \
        /usr/lib/jvm/graalvm* \
        "$HOME/.graalvm"/*/Contents/Home \
        "$HOME/.graalvm"/*; do
        if [ -f "$candidate/bin/native-image" ] 2>/dev/null && graalvm_version_ok "$candidate"; then
            export JAVA_HOME="$candidate"
            export PATH="$JAVA_HOME/bin:$PATH"
            info "GraalVM found: $JAVA_HOME"
            return
        fi
    done

    # Check if installed via SDKMAN but not active
    local graal_home
    graal_home="$(sdk home java $GRAAL_VERSION 2>/dev/null || true)"
    if [ -n "$graal_home" ] && [ -d "$graal_home" ]; then
        export JAVA_HOME="$graal_home"
        export PATH="$JAVA_HOME/bin:$PATH"
        info "GraalVM found: $graal_home"
        return
    fi

    step "Installing GraalVM $MIN_JDK via SDKMAN..."
    sdk install java "$GRAAL_VERSION" < /dev/null
    graal_home="$(sdk home java $GRAAL_VERSION)"
    export JAVA_HOME="$graal_home"
    export PATH="$JAVA_HOME/bin:$PATH"
    info "GraalVM installed"
}

# ── Source code ──────────────────────────────────────

ensure_source() {
    # If running from inside the repo, use it
    if [ -f "./build.gradle.kts" ] && grep -q "delve" ./build.gradle.kts 2>/dev/null; then
        SOURCE_DIR="$(pwd)"
        info "Using local source: $SOURCE_DIR"
        return
    fi

    # If running via curl | bash, clone the repo
    local clone_dir="$HOME/.local/share/delve/source"
    if [ -d "$clone_dir/.git" ]; then
        step "Updating source..."
        git -C "$clone_dir" pull --quiet
        info "Source updated"
    else
        step "Cloning repository..."
        mkdir -p "$(dirname "$clone_dir")"
        git clone --depth 1 "$REPO" "$clone_dir"
        info "Source cloned"
    fi
    SOURCE_DIR="$clone_dir"
}

# ── Build ────────────────────────────────────────────

available_memory_gb() {
    case "$OS" in
        macos)  sysctl -n hw.memsize 2>/dev/null | awk '{printf "%d", $1/1073741824}' ;;
        linux)  awk '/MemTotal/{printf "%d", $2/1048576}' /proc/meminfo 2>/dev/null ;;
        *)      echo 0 ;;
    esac
}

build_delve() {
    cd "$SOURCE_DIR"

    local build_mode="${1:-auto}"

    if [ "$build_mode" = "jvm" ]; then
        build_jvm
        return
    fi

    # Check memory before attempting native build (needs ~10GB)
    local mem_gb
    mem_gb="$(available_memory_gb)"
    if [ "$mem_gb" -lt 10 ] 2>/dev/null; then
        warn "Only ${mem_gb}GB RAM detected (native build needs ~10GB)"
        warn "Falling back to JVM mode"
        build_jvm
        return
    fi

    # Try native first
    if command -v native-image &>/dev/null || [ -f "$JAVA_HOME/bin/native-image" ]; then
        step "Building native binary... ${DIM}(2-5 min, ~10GB RAM)${RESET}"
        if ./gradlew nativeCompile --quiet 2>&1 | tail -3; then
            if [ -f "build/native/nativeCompile/delve" ]; then
                BUILD_ARTIFACT="build/native/nativeCompile/delve"
                BUILD_MODE="native"
                info "Native build complete"
                return
            fi
        fi
        warn "Native build failed, falling back to JVM"
    fi

    build_jvm
}

build_jvm() {
    step "Building JVM distribution..."
    ./gradlew installDist --quiet 2>&1 | tail -3
    BUILD_ARTIFACT="jvm"
    BUILD_MODE="jvm"
    info "JVM build complete"
}

# ── Install ──────────────────────────────────────────

install_delve() {
    if [ "$BUILD_MODE" = "native" ]; then
        cp "$SOURCE_DIR/$BUILD_ARTIFACT" "$INSTALL_DIR/$BIN_NAME"
        chmod +x "$INSTALL_DIR/$BIN_NAME"
        # macOS requires ad-hoc signing for native binaries
        if [ "$OS" = "macos" ] && command -v codesign &>/dev/null; then
            codesign -s - "$INSTALL_DIR/$BIN_NAME" 2>/dev/null || true
        fi
        info "Installed native binary → $INSTALL_DIR/$BIN_NAME"
    else
        # Create a wrapper script that calls the JVM dist
        local jvm_bin="$SOURCE_DIR/build/install/Delve/bin/Delve"
        if [ "$OS" = "windows" ]; then
            jvm_bin="$SOURCE_DIR/build/install/Delve/bin/Delve.bat"
        fi

        cat > "$INSTALL_DIR/$BIN_NAME" << LAUNCHER
#!/usr/bin/env bash
exec "$jvm_bin" "\$@"
LAUNCHER
        chmod +x "$INSTALL_DIR/$BIN_NAME"
        info "Installed JVM launcher → $INSTALL_DIR/$BIN_NAME"
    fi
}

# ── PATH ─────────────────────────────────────────────

ensure_path() {
    # Already on PATH?
    if echo "$PATH" | tr ':' '\n' | grep -qx "$INSTALL_DIR"; then
        return
    fi

    local path_line="export PATH=\"$INSTALL_DIR:\$PATH\""
    local added=false

    for rc in "$HOME/.zshrc" "$HOME/.bashrc" "$HOME/.bash_profile"; do
        if [ -f "$rc" ]; then
            if ! grep -q "$INSTALL_DIR" "$rc" 2>/dev/null; then
                echo "" >> "$rc"
                echo "# Delve" >> "$rc"
                echo "$path_line" >> "$rc"
                added=true
            fi
        fi
    done

    # Windows: suggest manual PATH update
    if [ "$OS" = "windows" ] && [ "$added" = false ]; then
        warn "Add to PATH manually: $INSTALL_DIR"
        return
    fi

    if [ "$added" = true ]; then
        info "Added to PATH ${DIM}(restart terminal or: source ~/.zshrc)${RESET}"
    fi

    export PATH="$INSTALL_DIR:$PATH"
}

# ── Config ───────────────────────────────────────────

ensure_config() {
    local config_dir="$HOME/.config/delve"
    local config_file="$config_dir/config.json"

    if [ -f "$config_file" ]; then
        return
    fi

    mkdir -p "$config_dir"
    cat > "$config_file" << 'CONFIG'
{
    "tavilyApiKey": null,
    "defaultModel": null,
    "maxConcurrentResearch": 3,
    "maxSupervisorIterations": 10,
    "maxToolCalls": 20,
    "temperature": 0.0,
    "enableClarification": true,
    "mcpServers": {}
}
CONFIG
    info "Config created: $config_file"
}

# ── Summary ──────────────────────────────────────────

print_summary() {
    echo ""
    echo -e "  ${BOLD}Delve installed${RESET}"
    echo ""

    if [ "$BUILD_MODE" = "native" ]; then
        echo -e "  Mode     ${DIM}native binary (instant startup)${RESET}"
    else
        echo -e "  Mode     ${DIM}JVM (requires Java ${MIN_JDK}+)${RESET}"
    fi
    echo -e "  Binary   ${DIM}$INSTALL_DIR/$BIN_NAME${RESET}"
    echo -e "  Config   ${DIM}$HOME/.config/delve/config.json${RESET}"
    echo ""

    # Check Ollama
    if command -v ollama &>/dev/null; then
        info "Ollama found"
    else
        warn "Ollama not found — install from https://ollama.ai"
    fi

    echo ""
    echo -e "  ${BOLD}Get started:${RESET}"
    echo ""
    echo -e "    ollama serve              ${DIM}# start Ollama${RESET}"
    echo -e "    ollama pull llama3.1      ${DIM}# download a model${RESET}"
    echo -e "    delve                     ${DIM}# interactive mode${RESET}"
    echo ""
}

# ── Uninstall ────────────────────────────────────────

uninstall_delve() {
    echo ""
    echo -e "  ${BOLD}Delve — Uninstall${RESET}"
    echo ""

    detect_os
    detect_install_dir

    local removed=false

    # Remove binary
    if [ -f "$INSTALL_DIR/$BIN_NAME" ]; then
        rm -f "$INSTALL_DIR/$BIN_NAME"
        info "Removed $INSTALL_DIR/$BIN_NAME"
        removed=true
    fi

    # Remove config
    if [ -d "$CONFIG_DIR" ]; then
        rm -rf "$CONFIG_DIR"
        info "Removed $CONFIG_DIR"
        removed=true
    fi

    # Remove cloned source
    local clone_dir="$HOME/.local/share/delve"
    if [ -d "$clone_dir" ]; then
        rm -rf "$clone_dir"
        info "Removed $clone_dir"
        removed=true
    fi

    # Clean PATH entries from shell rc files
    for rc in "$HOME/.zshrc" "$HOME/.bashrc" "$HOME/.bash_profile"; do
        if [ -f "$rc" ] && grep -q "# Delve" "$rc" 2>/dev/null; then
            # Remove "# Delve" comment and the PATH export line after it
            sed -i.bak -e '/^# Delve$/,+1d' "$rc"
            # Also remove any blank line left before the block
            sed -i.bak -e '/^$/N;/\n$/d' "$rc"
            rm -f "${rc}.bak"
            info "Cleaned PATH from $rc"
        fi
    done

    if [ "$removed" = true ]; then
        echo ""
        echo -e "  ${GREEN}Delve uninstalled.${RESET}"
    else
        warn "Delve does not appear to be installed"
    fi
    echo ""
}

# ── Main ─────────────────────────────────────────────

main() {
    echo ""
    echo -e "  ${BOLD}Delve — Installer${RESET}"

    local mode="auto"
    for arg in "$@"; do
        case "$arg" in
            --jvm)       mode="jvm" ;;
            --uninstall) uninstall_delve; exit 0 ;;
            --help|-h)
                echo ""
                echo "  Usage: install.sh [options]"
                echo ""
                echo "  Options:"
                echo "    --jvm        Skip native build, use JVM mode"
                echo "    --uninstall  Remove delve and its config"
                echo "    --help       Show this help"
                echo ""
                exit 0
                ;;
        esac
    done

    detect_os
    detect_install_dir
    info "Platform: $OS/$ARCH"

    ensure_sdkman

    if [ "$mode" != "jvm" ]; then
        ensure_graalvm
    else
        # JVM mode still needs Java 25+
        source "$HOME/.sdkman/bin/sdkman-init.sh"
        local jdk_ver
        jdk_ver="$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\).*/\1/' 2>/dev/null || echo 0)"
        if [ "$jdk_ver" -lt "$MIN_JDK" ] 2>/dev/null; then
            step "Installing Java $MIN_JDK via SDKMAN..."
            sdk install java "$GRAAL_VERSION" < /dev/null
            source "$HOME/.sdkman/bin/sdkman-init.sh"
        fi
        export JAVA_HOME="$(sdk home java current 2>/dev/null || echo "$JAVA_HOME")"
        info "Java: $(java -version 2>&1 | head -1)"
    fi

    ensure_source
    build_delve "$mode"
    install_delve
    ensure_path
    ensure_config
    print_summary
}

main "$@"
