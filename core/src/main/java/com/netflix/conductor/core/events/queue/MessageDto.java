package com.netflix.conductor.core.events.queue;

import java.util.Objects;

public class MessageDto {

    private Long type;

    private String messageId;

    private String payload;

    private String queue;

    public MessageDto(Long type, String messageId, String payload, String queue) {
        this.type = type;
        this.messageId = messageId;
        this.payload = payload;
        this.queue = queue;
    }

    public MessageDto() {}


    @Override
    public String toString() {
        return "MessageDto{" +
                "type=" + type +
                ", messageId='" + messageId + '\'' +
                ", payload='" + payload + '\'' +
                ", queue='" + queue + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MessageDto that = (MessageDto) o;
        return Objects.equals(type, that.type) && Objects.equals(messageId, that.messageId) && Objects.equals(payload, that.payload) && Objects.equals(queue, that.queue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, messageId, payload, queue);
    }

    public Long getType() {
        return type;
    }

    public void setType(Long type) {
        this.type = type;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }
}
