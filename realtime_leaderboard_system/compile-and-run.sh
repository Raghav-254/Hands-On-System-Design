#!/bin/bash
# Compile and run Leaderboard System Demo
# Usage: ./compile-and-run.sh

echo "=== Real-time Game Leaderboard Demo ==="
echo "Compiling..."

JAVA_FILES=$(find src -name "*.java")
mkdir -p target/classes
javac -d target/classes $JAVA_FILES

if [ $? -eq 0 ]; then
    echo "Compilation successful!"
    echo ""
    echo "Running demo..."
    echo "================================"
    java -cp target/classes com.leaderboard.LeaderboardDemo
else
    echo "Compilation failed!"
    exit 1
fi
