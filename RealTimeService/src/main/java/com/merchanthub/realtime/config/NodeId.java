package com.merchanthub.realtime.config;

/**
 * This node's ID. It determines the value stored in the route table, the Tag for inter-node forwarding and this node's dedicated consumer group name,
 * so it may only contain characters allowed in RocketMQ consumer group names (letters, digits, - and _).
 */
public record NodeId(String value) {

    public NodeId {
        value = value.replaceAll("[^A-Za-z0-9_-]", "-");
    }

    @Override
    public String toString() {
        return value;
    }
}
