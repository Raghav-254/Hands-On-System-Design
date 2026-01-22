#!/bin/bash

# Compile and run the Chat System Demo
# Requires Java 11+

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║           CHAT SYSTEM - COMPILE AND RUN                      ║"
echo "╚══════════════════════════════════════════════════════════════╝"

# Create output directory
mkdir -p target/classes

# Compile all Java files
echo "Compiling Java files..."
find src/main/java -name "*.java" -exec javac -d target/classes {} +

if [ $? -eq 0 ]; then
    echo "Compilation successful!"
    echo ""
    echo "Running demo..."
    echo ""
    java -cp target/classes com.chatapp.ChatSystemDemo
else
    echo "Compilation failed!"
    exit 1
fi

