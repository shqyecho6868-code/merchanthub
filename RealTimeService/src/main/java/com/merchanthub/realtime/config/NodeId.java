package com.merchanthub.realtime.config;

/**
 * 本节点的 ID。路由表里记的值、节点间转发消息的 Tag、本节点专属消费组名都由它决定，
 * 所以只能包含 RocketMQ 消费组名允许的字符（字母、数字、- 和 _）。
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
