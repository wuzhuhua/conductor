package com.netflix.conductor.sqs.eventqueue;

import com.netflix.conductor.core.events.queue.Message;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class InternalQueue {
    private final Map<String, Queue<Message>> queues;
    public InternalQueue(){
        this.queues = new ConcurrentHashMap<>();
    }

    public List<Message> pollMessages(String queueName, int count, int timeout) {
        //TODO timeout
        Queue<Message> queue = queues.getOrDefault(queueName, new ConcurrentLinkedQueue<>());
        List<Message> messages = new LinkedList<>();
        while (count > 0 && !queue.isEmpty()) {
            messages.add(queue.poll());
            count--;
        }
        return messages;
    }
    public boolean ack(String queueName, String messageId) {
        Queue<Message> queue = queues.get(queueName);
        if (queue == null) {
            return false;
        }
        for (Message message : queue) {
            if (message.getId().equals(messageId)) {
                queue.remove(message);
                return true;
            }
        }
        return false;
    }

    public void push(String queueName, List<Message> messages) {
        Queue<Message> queue = queues.getOrDefault(queueName, new ConcurrentLinkedQueue<>());
        queue.addAll(messages);
        queues.put(queueName, queue);
    }
    public int getSize(String queueName) {
        Queue<Message> queue = queues.getOrDefault(queueName, new ConcurrentLinkedQueue<>());
        return queue.size();
    }
}
