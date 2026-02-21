package com.splitwise.model;

import java.util.*;

public class Group {
    private final String groupId;
    private final String name;
    private final Set<String> memberIds;

    public Group(String groupId, String name, Set<String> memberIds) {
        this.groupId = groupId;
        this.name = name;
        this.memberIds = new LinkedHashSet<>(memberIds);
    }

    public String getGroupId() { return groupId; }
    public String getName() { return name; }
    public Set<String> getMemberIds() { return Collections.unmodifiableSet(memberIds); }
    public boolean hasMember(String userId) { return memberIds.contains(userId); }

    @Override
    public String toString() {
        return String.format("Group[%s '%s' members=%s]", groupId, name, memberIds);
    }
}
