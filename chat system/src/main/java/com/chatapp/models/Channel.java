package com.chatapp.models;

import java.util.HashSet;
import java.util.Set;

/**
 * Channel/Group model for group chat.
 * Contains list of member user IDs.
 */
public class Channel {
    private final long channelId;
    private final String channelName;
    private final Set<Long> memberIds;
    private final long createdBy;
    private final long createdAt;

    public Channel(long channelId, String channelName, long createdBy) {
        this.channelId = channelId;
        this.channelName = channelName;
        this.createdBy = createdBy;
        this.createdAt = System.currentTimeMillis();
        this.memberIds = new HashSet<>();
        this.memberIds.add(createdBy); // Creator is first member
    }

    public long getChannelId() {
        return channelId;
    }

    public String getChannelName() {
        return channelName;
    }

    public Set<Long> getMemberIds() {
        return new HashSet<>(memberIds);
    }

    public void addMember(long userId) {
        memberIds.add(userId);
    }

    public void removeMember(long userId) {
        memberIds.remove(userId);
    }

    public boolean isMember(long userId) {
        return memberIds.contains(userId);
    }

    public int getMemberCount() {
        return memberIds.size();
    }

    public long getCreatedBy() {
        return createdBy;
    }

    @Override
    public String toString() {
        return String.format("Channel{id=%d, name='%s', members=%d}", 
            channelId, channelName, memberIds.size());
    }
}

