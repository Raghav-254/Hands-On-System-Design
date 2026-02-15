package com.digitalwallet.service;

import com.digitalwallet.model.TransferEvent;
import com.digitalwallet.storage.EventStore;
import com.digitalwallet.storage.StateStore;
import java.util.List;

/**
 * Simulates a Raft node (Leader or Follower).
 * Leader processes commands; Followers replicate events.
 */
public class RaftNode {
    public enum Role { LEADER, FOLLOWER }

    private final String nodeId;
    private Role role;
    private final EventStore eventStore;
    private final StateStore stateStore;

    public RaftNode(String nodeId, Role role) {
        this.nodeId = nodeId;
        this.role = role;
        this.eventStore = new EventStore(nodeId);
        this.stateStore = new StateStore();
    }

    /**
     * Replicate events from leader to this follower.
     */
    public void replicateEvents(List<TransferEvent> events) {
        for (TransferEvent event : events) {
            eventStore.append(event);
            stateStore.applyEvent(event);
        }
    }

    public void promoteToLeader() {
        this.role = Role.LEADER;
        System.out.println("  [RAFT] " + nodeId + " promoted to LEADER");
    }

    public String getNodeId() { return nodeId; }
    public Role getRole() { return role; }
    public EventStore getEventStore() { return eventStore; }
    public StateStore getStateStore() { return stateStore; }

    @Override
    public String toString() {
        return String.format("RaftNode[%s, %s, events=%d]", nodeId, role, eventStore.size());
    }
}
