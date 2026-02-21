#!/bin/bash

# Spotify System Design Demo - Compile and Run
# No external dependencies needed - pure Java

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src/main/java"
OUT_DIR="$SCRIPT_DIR/target/classes"

echo "Cleaning..."
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

echo "Compiling..."
find "$SRC_DIR" -name "*.java" | xargs javac -d "$OUT_DIR"

echo "Running SpotifyDemo..."
echo ""
java -cp "$OUT_DIR" com.spotify.SpotifyDemo
