#!/bin/bash
# Compile and run Google Maps System Demo
# Usage: ./compile-and-run.sh

echo "=== Google Maps System Demo ==="
echo "Compiling..."

# Find all Java files
JAVA_FILES=$(find src -name "*.java")

# Create output directory
mkdir -p target/classes

# Compile
javac -d target/classes $JAVA_FILES

if [ $? -eq 0 ]; then
    echo "Compilation successful!"
    echo ""
    echo "Running demo..."
    echo "================================"
    java -cp target/classes com.googlemaps.GoogleMapsDemo
else
    echo "Compilation failed!"
    exit 1
fi
