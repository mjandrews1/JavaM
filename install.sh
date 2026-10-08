#!/usr/bin/env bash
# install mJVM CLI + man page (user-local by default)
#   ./install.sh              -> ~/.local
#   PREFIX=/usr/local ./install.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
PREFIX="${PREFIX:-$HOME/.local}"
BIN="$PREFIX/bin"
LIB="$PREFIX/lib/mjvm"
MAN="$PREFIX/share/man/man1"

echo "install mJVM -> $PREFIX"
mkdir -p "$BIN" "$LIB" "$MAN" "$LIB/lib"

# classes
if [[ ! -d "$ROOT/build/mjvm" ]]; then
  echo "building…"
  JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"
  (cd "$ROOT" && "$JAVA_HOME/bin/javac" -cp "lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar" \
      -d build src/main/java/jvmumps/*.java src/main/java/mjvm/*/*.java)
fi
cp -R "$ROOT/build/." "$LIB/"
cp -R "$ROOT/lib/." "$LIB/lib/"

# wrapper
cat >"$BIN/mjvm" <<EOF
#!/usr/bin/env bash
JAVA_HOME="\${JAVA_HOME:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"
if [[ ! -x "\$JAVA_HOME/bin/java" ]]; then
  JAVA_BIN="\$(command -v java || true)"
  JAVA_HOME="\$(dirname "\$(dirname "\$JAVA_BIN")")"
fi
exec "\$JAVA_HOME/bin/java" -cp "$LIB:$LIB/lib/sqlite-jdbc.jar:$LIB/lib/slf4j-api.jar:$LIB/lib/slf4j-nop.jar" mjvm.cli.Mjvm "\$@"
EOF
chmod +x "$BIN/mjvm"

# man
cp "$ROOT/man/mjvm.1" "$MAN/mjvm.1"
if command -v mandb >/dev/null 2>&1 && [[ -w /var/cache/man ]]; then
  mandb -q || true
fi

echo "installed:"
echo "  $BIN/mjvm"
echo "  $MAN/mjvm.1"
echo "try:  mjvm -V && man mjvm"
