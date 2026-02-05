#!/bin/bash

# Compile and run the Nearby Friends System Demo

echo "Compiling Nearby Friends System..."

# Create target directory
mkdir -p target/classes

# Compile all Java files
javac -d target/classes -sourcepath src/main/java \
  src/main/java/com/nearbyfriends/model/*.java \
  src/main/java/com/nearbyfriends/storage/*.java \
  src/main/java/com/nearbyfriends/cache/*.java \
  src/main/java/com/nearbyfriends/websocket/*.java \
  src/main/java/com/nearbyfriends/service/*.java \
  src/main/java/com/nearbyfriends/NearbyFriendsDemo.java

if [ $? -eq 0 ]; then
  echo "Compilation successful!"
  echo ""
  echo "Running demo..."
  echo ""
  java -cp target/classes com.nearbyfriends.NearbyFriendsDemo
else
  echo "Compilation failed!"
  exit 1
fi
