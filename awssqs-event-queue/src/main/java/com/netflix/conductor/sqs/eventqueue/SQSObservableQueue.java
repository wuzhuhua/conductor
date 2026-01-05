/*
 * Copyright 2022 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.conductor.sqs.eventqueue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.amazonaws.services.sqs.AmazonSQS;
import com.netflix.conductor.dao.QueueDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.conductor.core.events.queue.Message;
import com.netflix.conductor.core.events.queue.ObservableQueue;
import com.netflix.conductor.metrics.Monitors;

import com.amazonaws.auth.policy.Action;
import com.amazonaws.auth.policy.Policy;
import com.amazonaws.auth.policy.Principal;
import com.amazonaws.auth.policy.Resource;
import com.amazonaws.auth.policy.Statement;
import com.amazonaws.auth.policy.Statement.Effect;
import com.amazonaws.auth.policy.actions.SQSActions;
import com.amazonaws.services.sqs.model.BatchResultErrorEntry;
import com.amazonaws.services.sqs.model.ChangeMessageVisibilityRequest;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.CreateQueueResult;
import com.amazonaws.services.sqs.model.DeleteMessageBatchRequest;
import com.amazonaws.services.sqs.model.DeleteMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.DeleteMessageBatchResult;
import com.amazonaws.services.sqs.model.GetQueueAttributesResult;
import com.amazonaws.services.sqs.model.ListQueuesRequest;
import com.amazonaws.services.sqs.model.ListQueuesResult;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.SendMessageBatchResult;
import com.amazonaws.services.sqs.model.SetQueueAttributesResult;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Scheduler;

public class SQSObservableQueue implements ObservableQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(SQSObservableQueue.class);
    private static final String QUEUE_TYPE = "sqs";
    private final String queueName;
    private final int visibilityTimeoutInSeconds;
    private final int batchSize;

    private final int internalSize;
    private final AmazonSQS client;
    private final long pollTimeInMS;
    private final String queueURL;
    private final Scheduler scheduler;
    private volatile boolean running;

    //    private final InternalQueue queueDAO;
    private final QueueDAO queueDAO;


    private SQSObservableQueue(
            String queueName,
            AmazonSQS client,
            int visibilityTimeoutInSeconds,
            int batchSize,
            int internalSize,
            long pollTimeInMS,
            List<String> accountsToAuthorize,
            Scheduler scheduler, QueueDAO queueDAO) {
        this.queueName = queueName;
        this.client = client;
        this.visibilityTimeoutInSeconds = visibilityTimeoutInSeconds;
        this.batchSize = batchSize;
        this.pollTimeInMS = pollTimeInMS;
        this.queueURL = queueName;
        this.scheduler = scheduler;
//        this.queueDAO=new InternalQueue();
        this.queueDAO = queueDAO;
        this.internalSize = internalSize;
//        addPolicy(accountsToAuthorize);
    }

    @Override
    public Observable<Message> observe() {
        OnSubscribe<Message> subscriber = getOnSubscribe();
        return Observable.create(subscriber);
    }

    @Override
    public List<String> ack(List<Message> messages) {
        return delete(messages);
    }

    @Override
    public void publish(List<Message> messages) {
//        publishMessages(messages);
        queueDAO.push(queueName, messages);
    }

    @Override
    public long size() {
        GetQueueAttributesResult attributes =
                client.getQueueAttributes(
                        queueURL, Collections.singletonList("ApproximateNumberOfMessages"));
        String sizeAsStr = attributes.getAttributes().get("ApproximateNumberOfMessages");
        try {
            return Long.parseLong(sizeAsStr) + queueDAO.getSize(queueName);
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public void setUnackTimeout(Message message, long unackTimeout) {
        int unackTimeoutInSeconds = (int) (unackTimeout / 1000);
        ChangeMessageVisibilityRequest request =
                new ChangeMessageVisibilityRequest(
                        queueURL, message.getReceipt(), unackTimeoutInSeconds);
        client.changeMessageVisibility(request);
    }

    @Override
    public String getType() {
        return QUEUE_TYPE;
    }

    @Override
    public String getName() {
        return queueName;
    }

    @Override
    public String getURI() {
        return queueURL;
    }

    public long getPollTimeInMS() {
        return pollTimeInMS;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getVisibilityTimeoutInSeconds() {
        return visibilityTimeoutInSeconds;
    }

    @Override
    public void start() {
        LOGGER.info("Started listening to {}:{}", getClass().getSimpleName(), queueName);
        running = true;
    }

    @Override
    public void stop() {
        LOGGER.info("Stopped listening to {}:{}", getClass().getSimpleName(), queueName);
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public static class Builder {

        private String queueName;
        private int visibilityTimeout = 30; // seconds
        private int batchSize = 5;
        private int internalSize = 1000;
        private long pollTimeInMS = 100;
        private AmazonSQS client;
        private List<String> accountsToAuthorize = new LinkedList<>();
        private Scheduler scheduler;
        private QueueDAO queueDAO;

        public Builder withQueueName(String queueName) {
            this.queueName = queueName;
            return this;
        }

        /**
         * @param visibilityTimeout Visibility timeout for the message in SECONDS
         * @return builder instance
         */
        public Builder withVisibilityTimeout(int visibilityTimeout) {
            this.visibilityTimeout = visibilityTimeout;
            return this;
        }

        public Builder withBatchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder withInternalSize(int batchSize) {
            this.internalSize = batchSize;
            return this;
        }

        public Builder withClient(AmazonSQS client) {
            this.client = client;
            return this;
        }

        public Builder withPollTimeInMS(long pollTimeInMS) {
            this.pollTimeInMS = pollTimeInMS;
            return this;
        }

        public Builder withAccountsToAuthorize(List<String> accountsToAuthorize) {
            this.accountsToAuthorize = accountsToAuthorize;
            return this;
        }

        public Builder addAccountToAuthorize(String accountToAuthorize) {
            this.accountsToAuthorize.add(accountToAuthorize);
            return this;
        }

        public Builder withScheduler(Scheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public Builder withQueueDAO(QueueDAO queueDAO) {
            this.queueDAO = queueDAO;
            return this;
        }

        public SQSObservableQueue build() {
            return new SQSObservableQueue(
                    queueName,
                    client,
                    visibilityTimeout,
                    batchSize,
                    internalSize,
                    pollTimeInMS,
                    accountsToAuthorize,
                    scheduler, queueDAO);
        }
    }

    // Private methods
    String getOrCreateQueue() {
        List<String> queueUrls = listQueues(queueName);
        if (queueUrls == null || queueUrls.isEmpty()) {
            try {
                CreateQueueRequest createQueueRequest =
                        new CreateQueueRequest().withQueueName(queueName);
                CreateQueueResult result = client.createQueue(createQueueRequest);
                return result.getQueueUrl();
            } catch (Exception e) {
                LOGGER.error("Failed to create aws sqs queue, reason" + e.getMessage() + " with queueName"
                        + queueName);
                return null;
            }
        } else {
            return queueUrls.get(0);
        }
    }

    private String getQueueARN() {
        GetQueueAttributesResult response =
                client.getQueueAttributes(queueURL, Collections.singletonList("QueueArn"));
        return response.getAttributes().get("QueueArn");
    }

    private void addPolicy(List<String> accountsToAuthorize) {
        if (accountsToAuthorize == null || accountsToAuthorize.isEmpty()) {
            LOGGER.info("No additional security policies attached for the queue " + queueName);
            return;
        }
        LOGGER.info("Authorizing " + accountsToAuthorize + " to the queue " + queueName);
        Map<String, String> attributes = new HashMap<>();
        attributes.put("Policy", getPolicy(accountsToAuthorize));
        SetQueueAttributesResult result = client.setQueueAttributes(queueURL, attributes);
        LOGGER.info("policy attachment result: " + result);
        LOGGER.info(
                "policy attachment result: status="
                        + result.getSdkHttpMetadata().getHttpStatusCode());
    }

    private String getPolicy(List<String> accountIds) {
        Policy policy = new Policy("AuthorizedWorkerAccessPolicy");
        Statement stmt = new Statement(Effect.Allow);
        Action action = SQSActions.SendMessage;
        stmt.getActions().add(action);
        stmt.setResources(new LinkedList<>());
        for (String accountId : accountIds) {
            Principal principal = new Principal(accountId);
            stmt.getPrincipals().add(principal);
        }
        stmt.getResources().add(new Resource(getQueueARN()));
        policy.getStatements().add(stmt);
        return policy.toJson();
    }

    private List<String> listQueues(String queueName) {
        ListQueuesRequest listQueuesRequest =
                new ListQueuesRequest().withQueueNamePrefix(queueName);
        ListQueuesResult resultList = client.listQueues(listQueuesRequest);
        return resultList.getQueueUrls().stream()
                .filter(queueUrl -> queueUrl.contains(queueName))
                .collect(Collectors.toList());
    }

    private void publishMessages(List<Message> messages) {
        LOGGER.debug("Sending {} messages to the SQS queue: {}", messages.size(), queueName);
        SendMessageBatchRequest batch = new SendMessageBatchRequest(queueURL);
        messages.forEach(
                msg -> {
                    SendMessageBatchRequestEntry sendr =
                            new SendMessageBatchRequestEntry(msg.getId(), msg.getPayload());
                    batch.getEntries().add(sendr);
                });
        LOGGER.debug("sending {} messages in batch", batch.getEntries().size());
        SendMessageBatchResult result = client.sendMessageBatch(batch);
        LOGGER.debug("send result: {} for SQS queue: {}", result.getFailed().toString(), queueName);
    }

    List<Message> receiveMessages() {
        try {
            ReceiveMessageRequest receiveMessageRequest =
                    new ReceiveMessageRequest()
                            .withQueueUrl(queueURL)
                            .withVisibilityTimeout(visibilityTimeoutInSeconds)
                            .withMaxNumberOfMessages(batchSize);

            ReceiveMessageResult result = client.receiveMessage(receiveMessageRequest);

            List<Message> messages =
                    result.getMessages().stream()
                            .map(
                                    msg ->
                                            new Message(
                                                    msg.getMessageId(),
                                                    msg.getBody(),
                                                    msg.getReceiptHandle()))
                            .collect(Collectors.toList());
            Monitors.recordEventQueueMessagesProcessed(QUEUE_TYPE, this.queueName, messages.size());
            return messages;
        } catch (Exception e) {
            LOGGER.error("Exception while getting messages from SQS", e);
            Monitors.recordObservableQMessageReceivedErrors(QUEUE_TYPE);
        }
        return new ArrayList<>();
    }

    OnSubscribe<Message> getOnSubscribe() {
        return subscriber -> {
            Observable<Message> apiObservable = createApiObservable();
//            Observable<Message> sqsObservable = createSqsObservable();
//            Observable<Message> mergedObservable = Observable.merge(sqsObservable, apiObservable);
//            mergedObservable.subscribe(subscriber::onNext, subscriber::onError);
            apiObservable.subscribe(
                    subscriber::onNext,
                    subscriber::onError
            );
        };
    }

    private List<Message> receiveApiMessages() {
        if (queueDAO == null || queueName == null) {
            return new ArrayList<>();
        }
        try {
            List<Message> messages = queueDAO.pollMessages(queueName, internalSize, (int) pollTimeInMS);
            Monitors.recordEventQueueMessagesProcessed(QUEUE_TYPE, queueName, messages.size());
            Monitors.recordEventQueuePollSize(queueName, messages.size());
            return messages;
        } catch (Exception exception) {
            LOGGER.error("Exception while getting messages from  queueDAO", exception);
            Monitors.recordObservableQMessageReceivedErrors(QUEUE_TYPE);
        }
        return new ArrayList<>();
    }

    private Observable<Message> createApiObservable() {
        return Observable.create(emitter -> {
            Observable<Long> interval = Observable.interval(pollTimeInMS, TimeUnit.MILLISECONDS);
            interval.flatMap(
                    x -> {
                        if (!isRunning()) {
                            LOGGER.debug("Component stopped, skip listening for messages from api redis");
                            return Observable.empty();
                        }
                        List<Message> messages = receiveApiMessages();
                        return Observable.from(messages);
                    }).subscribe(emitter::onNext, emitter::onError);
        });
    }

    private Observable<Message> createSqsObservable() {
        return Observable.create(emitter -> {
            Observable<Long> interval = Observable.interval(pollTimeInMS, TimeUnit.MILLISECONDS);
            interval.flatMap(
                    x -> {
                        if (!isRunning()) {
                            LOGGER.debug(
                                    "Component stopped, skip listening for messages from SQS");
                            return Observable.from(Collections.emptyList());
                        }
                        List<Message> messages = receiveMessages();
                        return Observable.from(messages);
                    }).subscribe(emitter::onNext, emitter::onError);
        });
    }

    private List<String> delete(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (Message msg : messages) {
            queueDAO.ack(queueName, msg.getId());
        }
//        DeleteMessageBatchRequest batch = new DeleteMessageBatchRequest().withQueueUrl(queueURL);
//        List<DeleteMessageBatchRequestEntry> entries = batch.getEntries();
//
//        messages.forEach(
//                m ->
//                        entries.add(
//                                new DeleteMessageBatchRequestEntry()
//                                        .withId(m.getId())
//                                        .withReceiptHandle(m.getReceipt())));
//
//        DeleteMessageBatchResult result = client.deleteMessageBatch(batch);
//        List<String> failures =
//                result.getFailed().stream()
//                        .map(BatchResultErrorEntry::getId)
//                        .collect(Collectors.toList());
//
//        LOGGER.debug("Failed to delete messages from queue: {}: {}", queueName, failures);
//        return failures;
        return null;
    }
}
