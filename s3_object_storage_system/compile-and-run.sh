#!/bin/bash
# Compile and run S3 Object Storage Demo
# Usage: ./compile-and-run.sh

echo "=== S3-like Object Storage Demo ==="
echo "Compiling..."

JAVA_FILES=$(find src -name "*.java")
mkdir -p target/classes
javac -d target/classes $JAVA_FILES

if [ $? -eq 0 ]; then
    echo "Compilation successful!"
    echo ""
    echo "Running demo..."
    echo "================================"
    java -cp target/classes com.objectstorage.ObjectStorageDemo
else
    echo "Compilation failed!"
    exit 1
fi
