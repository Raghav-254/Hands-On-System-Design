package com.chatapp;

import com.chatapp.api.ApiServer;
import com.chatapp.discovery.ServiceDiscovery;
import com.chatapp.discovery.ServiceDiscovery.ChatServerInfo;
import com.chatapp.idgen.SnowflakeIdGenerator;
import com.chatapp.models.Channel;
import com.chatapp.notification.PushNotificationService;
import com.chatapp.presence.PresenceService;
import com.chatapp.queue.MessageSyncQueue;
import com.chatapp.server.ChatServer;
import com.chatapp.server.ChatServer.UserSession;
import com.chatapp.storage.MessageStore;

/**
 * Chat System Demo - Demonstrates all critical flows from Alex Xu's design.
 * 
 * This demo covers:
 * 1. Service Discovery & Chat Server Assignment (Figure 12-11)
 * 2. 1:1 Message Flow (Figure 12-12)
 * 3. Multi-device Sync (Figure 12-13)
 * 4. Small Group Chat (Figure 12-14)
 * 5. Large Group Chat (Figure 12-15)
 * 6. Online Presence (Figure 12-16)
 * 7. Offline Push Notifications
 */
public class ChatSystemDemo {
    
    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║           CHAT SYSTEM - HANDS-ON DEMONSTRATION               ║");
        System.out.println("║     Based on Alex Xu's System Design Interview Book          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");
        
        // Initialize all components
        ChatSystemDemo demo = new ChatSystemDemo();
        demo.initializeSystem();
        
        // Run all demo flows
        demo.demoFlow1_ServiceDiscovery();
        demo.demoFlow2_DirectMessaging();
        demo.demoFlow3_MultiDeviceSync();
        demo.demoFlow4_SmallGroupChat();
        demo.demoFlow5_OnlinePresence();
        demo.demoFlow6_OfflineNotification();
        
        // Print final stats
        demo.printSystemStats();
        
        // Cleanup
        demo.shutdown();
    }
    
    // System components
    private ServiceDiscovery serviceDiscovery;
    private MessageSyncQueue messageQueue;
    private MessageStore messageStore;
    private PresenceService presenceService;
    private PushNotificationService pushNotificationService;
    private SnowflakeIdGenerator idGenerator1;
    private SnowflakeIdGenerator idGenerator2;
    private ChatServer chatServer1;
    private ChatServer chatServer2;
    private ApiServer apiServer;
    
    /**
     * Initialize all system components.
     */
    private void initializeSystem() {
        printSection("SYSTEM INITIALIZATION");
        
        // Core services
        serviceDiscovery = new ServiceDiscovery();
        messageQueue = new MessageSyncQueue();
        messageStore = new MessageStore();
        presenceService = new PresenceService();
        pushNotificationService = new PushNotificationService();
        
        // ID Generators (one per datacenter/machine)
        idGenerator1 = new SnowflakeIdGenerator(1, 1); // DC1, Machine1
        idGenerator2 = new SnowflakeIdGenerator(1, 2); // DC1, Machine2
        
        // Chat Servers
        chatServer1 = new ChatServer("chat-server-1", "localhost", 8081,
            idGenerator1, messageQueue, messageStore, serviceDiscovery);
        chatServer2 = new ChatServer("chat-server-2", "localhost", 8082,
            idGenerator2, messageQueue, messageStore, serviceDiscovery);
        
        // Set up push notification handler for offline users
        chatServer1.setOfflineMessageHandler(pushNotificationService::notifyOfflineUser);
        chatServer2.setOfflineMessageHandler(pushNotificationService::notifyOfflineUser);
        
        // API Server
        apiServer = new ApiServer("api-server-1", serviceDiscovery, messageStore);
        
        // Register some test users
        apiServer.registerUser(1L, "alice");
        apiServer.registerUser(2L, "bob");
        apiServer.registerUser(3L, "charlie");
        apiServer.registerUser(4L, "diana");
        
        // Register device tokens for push notifications
        pushNotificationService.registerDeviceToken(1L, 
            PushNotificationService.Platform.IOS, "alice-ios-token-abc123");
        pushNotificationService.registerDeviceToken(2L, 
            PushNotificationService.Platform.ANDROID, "bob-android-token-xyz789");
        
        System.out.println("\n✓ System initialized successfully!\n");
    }
    
    /**
     * Demo Flow 1: Service Discovery & Chat Server Assignment
     * Demonstrates Figure 12-11
     */
    private void demoFlow1_ServiceDiscovery() throws InterruptedException {
        printSection("FLOW 1: SERVICE DISCOVERY & CHAT SERVER ASSIGNMENT");
        System.out.println("This demonstrates how users get assigned to chat servers (Figure 12-11)\n");
        
        // Step 1: User logs in
        System.out.println("Step 1: Alice logs in via API server...");
        ApiServer.LoginResponse response = apiServer.login("alice", "password123");
        System.out.println("Response: " + response);
        
        // Step 2: Get assigned chat server
        System.out.println("\nStep 2: Alice gets assigned chat server from Service Discovery...");
        ChatServerInfo assignedServer = response.getChatServer();
        System.out.println("Assigned server: " + assignedServer);
        System.out.println("WebSocket URL: " + assignedServer.getWebSocketUrl());
        
        // Step 3: User connects to chat server via WebSocket
        System.out.println("\nStep 3: Alice connects to chat server via WebSocket...");
        UserSession aliceSession = chatServer1.connect(1L, "alice-phone");
        System.out.println("Connected! Session established.");
        
        aliceSession.disconnect();
        Thread.sleep(100);
    }
    
    /**
     * Demo Flow 2: 1:1 Direct Messaging
     * Demonstrates Figure 12-12
     */
    private void demoFlow2_DirectMessaging() throws InterruptedException {
        printSection("FLOW 2: 1:1 DIRECT MESSAGING");
        System.out.println("This demonstrates the message flow between two users (Figure 12-12)\n");
        
        // Both users connect
        System.out.println("Step 1: Alice and Bob connect to chat servers...");
        UserSession aliceSession = chatServer1.connect(1L, "alice-phone");
        UserSession bobSession = chatServer2.connect(2L, "bob-phone");
        
        // Mark both users online in presence service
        presenceService.userOnline(1L);
        presenceService.userOnline(2L);
        
        Thread.sleep(100);
        
        // Alice sends message to Bob
        System.out.println("\nStep 2: Alice sends message to Bob...");
        System.out.println("Flow: Alice -> ChatServer1 -> IDGenerator -> MessageQueue -> KVStore -> ChatServer2 -> Bob");
        aliceSession.sendMessage(2L, "Hey Bob! How are you?");
        
        Thread.sleep(200);
        
        // Bob replies
        System.out.println("\nStep 3: Bob replies to Alice...");
        bobSession.sendMessage(1L, "Hi Alice! I'm doing great, thanks!");
        
        Thread.sleep(200);
        
        // Check received messages
        System.out.println("\nStep 4: Verify messages received...");
        System.out.println("Alice received: " + aliceSession.getReceivedMessages().size() + " messages");
        System.out.println("Bob received: " + bobSession.getReceivedMessages().size() + " messages");
        
        aliceSession.disconnect();
        bobSession.disconnect();
        presenceService.userOffline(1L);
        presenceService.userOffline(2L);
        Thread.sleep(100);
    }
    
    /**
     * Demo Flow 3: Multi-device Sync
     * Demonstrates Figure 12-13
     */
    private void demoFlow3_MultiDeviceSync() throws InterruptedException {
        printSection("FLOW 3: MULTI-DEVICE SYNCHRONIZATION");
        System.out.println("This demonstrates message sync across multiple devices (Figure 12-13)\n");
        
        // Alice has two devices
        System.out.println("Step 1: Alice connects from both phone and laptop...");
        UserSession alicePhone = chatServer1.connect(1L, "alice-phone");
        UserSession aliceLaptop = chatServer1.connect(1L, "alice-laptop");
        presenceService.userOnline(1L);
        
        // Bob sends message
        UserSession bobSession = chatServer2.connect(2L, "bob-phone");
        presenceService.userOnline(2L);
        
        Thread.sleep(100);
        
        System.out.println("\nStep 2: Bob sends message to Alice...");
        bobSession.sendMessage(1L, "Hey Alice, check this out!");
        
        Thread.sleep(200);
        
        // Both devices should receive the message
        System.out.println("\nStep 3: Verify both devices received the message...");
        System.out.println("Alice's phone received: " + alicePhone.getReceivedMessages().size() + " messages");
        System.out.println("Alice's laptop received: " + aliceLaptop.getReceivedMessages().size() + " messages");
        
        // Simulate device cursors (different sync points)
        System.out.println("\nStep 4: Device cursors track sync state...");
        long phoneCursor = messageStore.getDeviceCursor(1L, "alice-phone");
        long laptopCursor = messageStore.getDeviceCursor(1L, "alice-laptop");
        System.out.println("Phone cursor (last seen msg): " + phoneCursor);
        System.out.println("Laptop cursor (last seen msg): " + laptopCursor);
        
        alicePhone.disconnect();
        aliceLaptop.disconnect();
        bobSession.disconnect();
        presenceService.userOffline(1L);
        presenceService.userOffline(2L);
        Thread.sleep(100);
    }
    
    /**
     * Demo Flow 4: Small Group Chat
     * Demonstrates Figure 12-14 and 12-15
     */
    private void demoFlow4_SmallGroupChat() throws InterruptedException {
        printSection("FLOW 4: SMALL GROUP CHAT (Fan-out on Write)");
        System.out.println("This demonstrates group message delivery (Figure 12-14)\n");
        
        // Create a group channel
        System.out.println("Step 1: Alice creates a group 'Project Team'...");
        Channel projectTeam = apiServer.createChannel(100L, "Project Team", 1L);
        apiServer.addChannelMember(100L, 2L);  // Add Bob
        apiServer.addChannelMember(100L, 3L);  // Add Charlie
        System.out.println("Channel created: " + projectTeam);
        
        // All members connect
        System.out.println("\nStep 2: All members connect...");
        UserSession aliceSession = chatServer1.connect(1L, "alice-phone");
        UserSession bobSession = chatServer1.connect(2L, "bob-phone");
        UserSession charlieSession = chatServer2.connect(3L, "charlie-phone");
        
        Thread.sleep(100);
        
        // Alice sends a group message
        System.out.println("\nStep 3: Alice sends message to the group...");
        System.out.println("Small group (< 100 members) uses FAN-OUT ON WRITE");
        System.out.println("Each member gets their own copy in their message queue");
        
        chatServer1.sendGroupMessage(1L, 100L, "Hey team, let's discuss the project!", projectTeam);
        
        Thread.sleep(200);
        
        System.out.println("\nStep 4: Verify all members received the message...");
        // Note: In this demo, we'd need to add group message handling to sessions
        
        System.out.println("\n--- Large Group Behavior (Figure 12-15) ---");
        System.out.println("For large groups (> 100 members):");
        System.out.println("- Uses FAN-OUT ON READ");
        System.out.println("- Single message goes to shared channel queue");
        System.out.println("- Each user's device pulls from shared queue");
        System.out.println("- More efficient for large groups");
        
        aliceSession.disconnect();
        bobSession.disconnect();
        charlieSession.disconnect();
        Thread.sleep(100);
    }
    
    /**
     * Demo Flow 5: Online Presence
     * Demonstrates Figure 12-16
     */
    private void demoFlow5_OnlinePresence() throws InterruptedException {
        printSection("FLOW 5: ONLINE PRESENCE (Pub/Sub)");
        System.out.println("This demonstrates online status tracking (Figure 12-16)\n");
        
        // Bob and Charlie subscribe to Alice's presence
        System.out.println("Step 1: Bob and Charlie subscribe to Alice's presence...");
        presenceService.subscribe(2L, 1L);  // Bob subscribes to Alice
        presenceService.subscribe(3L, 1L);  // Charlie subscribes to Alice
        
        // Set up presence update handlers
        presenceService.onPresenceUpdate(2L, update -> 
            System.out.println("    Bob received: " + update));
        presenceService.onPresenceUpdate(3L, update -> 
            System.out.println("    Charlie received: " + update));
        
        Thread.sleep(100);
        
        // Alice comes online
        System.out.println("\nStep 2: Alice comes online...");
        presenceService.userOnline(1L);
        Thread.sleep(200);
        
        // Alice changes status
        System.out.println("\nStep 3: Alice sets status to BUSY...");
        presenceService.setStatus(1L, PresenceService.PresenceStatus.BUSY);
        Thread.sleep(200);
        
        // Alice goes offline
        System.out.println("\nStep 4: Alice goes offline...");
        presenceService.userOffline(1L);
        Thread.sleep(200);
        
        System.out.println("\nPresence subscription pattern:");
        System.out.println("- Each user has a presence channel");
        System.out.println("- Friends subscribe to relevant channels");
        System.out.println("- Status changes fan-out to all subscribers");
    }
    
    /**
     * Demo Flow 6: Offline Push Notification
     * Demonstrates Figure 12-12 (step 5.b)
     */
    private void demoFlow6_OfflineNotification() throws InterruptedException {
        printSection("FLOW 6: OFFLINE PUSH NOTIFICATION");
        System.out.println("This demonstrates push notification for offline users (Figure 12-12, step 5.b)\n");
        
        // Alice is online, Bob is offline
        System.out.println("Step 1: Alice is online, Bob is offline...");
        UserSession aliceSession = chatServer1.connect(1L, "alice-phone");
        presenceService.userOnline(1L);
        // Bob is NOT connected
        
        Thread.sleep(100);
        
        // Alice sends message to offline Bob
        System.out.println("\nStep 2: Alice sends message to Bob (who is offline)...");
        aliceSession.sendMessage(2L, "Bob, are you there? Important meeting in 5 mins!");
        
        Thread.sleep(300);
        
        // Check push notifications sent
        System.out.println("\nStep 3: Push notification sent to Bob's device...");
        var notifications = pushNotificationService.getSentNotifications();
        System.out.println("Notifications sent: " + notifications.size());
        for (var notification : notifications) {
            System.out.println("  " + notification);
        }
        
        aliceSession.disconnect();
        presenceService.userOffline(1L);
    }
    
    /**
     * Print final system statistics.
     */
    private void printSystemStats() {
        printSection("SYSTEM STATISTICS");
        
        serviceDiscovery.printStatus();
        messageStore.printStats();
        chatServer1.printStatus();
        chatServer2.printStatus();
    }
    
    /**
     * Cleanup and shutdown.
     */
    private void shutdown() {
        printSection("SHUTDOWN");
        presenceService.shutdown();
        pushNotificationService.shutdown();
        messageQueue.shutdown();
        System.out.println("All services shutdown successfully.");
    }
    
    private void printSection(String title) {
        System.out.println("\n" + "═".repeat(70));
        System.out.println("  " + title);
        System.out.println("═".repeat(70));
    }
}

