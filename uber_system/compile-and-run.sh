#!/bin/bash
# Compile and run Uber Ride-Sharing System Demo
# Usage: ./compile-and-run.sh

echo "=== Uber Ride-Sharing System Demo ==="
echo "Compiling..."

JAVA_FILES=$(find src -name "*.java")
mkdir -p target/classes
javac -d target/classes $JAVA_FILES

if [ $? -eq 0 ]; then
    echo "Compilation successful!"
    echo ""
    echo "Running demo..."
    echo "================================"
    java -cp target/classes com.uber.UberDemo
else
    echo "Compilation failed!"
    exit 1
fi
