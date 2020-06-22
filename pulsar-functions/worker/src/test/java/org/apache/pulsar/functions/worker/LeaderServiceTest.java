/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.functions.worker;

import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.ConsumerEventListener;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.impl.ConsumerImpl;
import org.apache.pulsar.client.impl.MessageIdImpl;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.apache.pulsar.functions.runtime.thread.ThreadRuntimeFactory;
import org.apache.pulsar.functions.runtime.thread.ThreadRuntimeFactoryConfig;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class LeaderServiceTest {

    private final WorkerConfig workerConfig;
    private LeaderService leaderService;
    private PulsarClientImpl mockClient;
    AtomicReference<ConsumerEventListener> listenerHolder;
    private ConsumerImpl mockConsumer;
    private FunctionAssignmentTailer functionAssignmentTailer;
    private SchedulerManager schedulerManager;

    public LeaderServiceTest() {
        this.workerConfig = new WorkerConfig();
        workerConfig.setWorkerId("worker-1");
        workerConfig.setFunctionRuntimeFactoryClassName(ThreadRuntimeFactory.class.getName());
        workerConfig.setFunctionRuntimeFactoryConfigs(
                ObjectMapperFactory.getThreadLocal().convertValue(
                        new ThreadRuntimeFactoryConfig().setThreadGroupName("test"), Map.class));
        workerConfig.setPulsarServiceUrl("pulsar://localhost:6650");
        workerConfig.setStateStorageServiceUrl("foo");
        workerConfig.setWorkerPort(1234);
    }

    @BeforeMethod
    public void setup() throws PulsarClientException {
        mockClient = mock(PulsarClientImpl.class);

        mockConsumer = mock(ConsumerImpl.class);
        ConsumerBuilder<byte[]> mockConsumerBuilder = mock(ConsumerBuilder.class);

        when(mockConsumerBuilder.topic(anyString())).thenReturn(mockConsumerBuilder);
        when(mockConsumerBuilder.subscriptionName(anyString())).thenReturn(mockConsumerBuilder);
        when(mockConsumerBuilder.subscriptionType(any(SubscriptionType.class))).thenReturn(mockConsumerBuilder);
        when(mockConsumerBuilder.property(anyString(), anyString())).thenReturn(mockConsumerBuilder);
        when(mockConsumerBuilder.consumerName(anyString())).thenReturn(mockConsumerBuilder);

        when(mockConsumerBuilder.subscribe()).thenReturn(mockConsumer);
        WorkerService workerService = mock(WorkerService.class);
        doReturn(workerConfig).when(workerService).getWorkerConfig();

        listenerHolder = new AtomicReference<>();
        when(mockConsumerBuilder.consumerEventListener(any(ConsumerEventListener.class))).thenAnswer(invocationOnMock -> {

            ConsumerEventListener listener = invocationOnMock.getArgument(0);
            listenerHolder.set(listener);

            return mockConsumerBuilder;
        });

        when(mockClient.newConsumer()).thenReturn(mockConsumerBuilder);

        schedulerManager = mock(SchedulerManager.class);


        functionAssignmentTailer = mock(FunctionAssignmentTailer.class);
        when(functionAssignmentTailer.triggerReadToTheEndAndExit()).thenReturn(CompletableFuture.completedFuture(null));

        leaderService = spy(new LeaderService(workerService, mockClient, functionAssignmentTailer, schedulerManager, ErrorNotifier.getDefaultImpl()));
        leaderService.start();
    }

    @Test
    public void testLeaderService() throws Exception {
        MessageId messageId = new MessageIdImpl(1, 2, -1);
        when(schedulerManager.getLastMessageProduced()).thenReturn(messageId);

        assertFalse(leaderService.isLeader());
        verify(mockClient, times(1)).newConsumer();

        listenerHolder.get().becameActive(mockConsumer, 0);
        assertTrue(leaderService.isLeader());

        verify(functionAssignmentTailer, times(1)).triggerReadToTheEndAndExit();
        verify(functionAssignmentTailer, times(1)).close();
        verify(schedulerManager, times((1))).initialize();

        listenerHolder.get().becameInactive(mockConsumer, 0);
        assertFalse(leaderService.isLeader());

        verify(functionAssignmentTailer, times(1)).startFromMessage(messageId);
        verify(schedulerManager, times(1)).close();
    }

    @Test
    public void testLeaderServiceNoNewScheduling() throws Exception {
        when(schedulerManager.getLastMessageProduced()).thenReturn(null);

        assertFalse(leaderService.isLeader());
        verify(mockClient, times(1)).newConsumer();

        listenerHolder.get().becameActive(mockConsumer, 0);
        assertTrue(leaderService.isLeader());

        verify(functionAssignmentTailer, times(1)).triggerReadToTheEndAndExit();
        verify(functionAssignmentTailer, times(1)).close();
        verify(schedulerManager, times((1))).initialize();

        listenerHolder.get().becameInactive(mockConsumer, 0);
        assertFalse(leaderService.isLeader());

        verify(functionAssignmentTailer, times(1)).start();
        verify(schedulerManager, times(1)).close();
    }
}