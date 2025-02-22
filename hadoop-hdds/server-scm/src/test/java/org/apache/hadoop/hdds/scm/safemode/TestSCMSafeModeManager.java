/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdds.scm.safemode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.client.RatisReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos;
import org.apache.hadoop.hdds.scm.HddsTestUtils;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.container.MockNodeManager;
import org.apache.hadoop.hdds.scm.events.SCMEvents;
import org.apache.hadoop.hdds.scm.exceptions.SCMException;
import org.apache.hadoop.hdds.scm.ha.SCMHAManagerStub;
import org.apache.hadoop.hdds.scm.ha.SCMContext;
import org.apache.hadoop.hdds.scm.ha.SCMServiceManager;
import org.apache.hadoop.hdds.scm.metadata.SCMMetadataStore;
import org.apache.hadoop.hdds.scm.metadata.SCMMetadataStoreImpl;
import org.apache.hadoop.hdds.scm.pipeline.MockRatisPipelineProvider;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.scm.pipeline.PipelineManager;
import org.apache.hadoop.hdds.scm.pipeline.PipelineProvider;
import org.apache.hadoop.hdds.scm.pipeline.PipelineManagerImpl;
import org.apache.hadoop.hdds.scm.server.SCMDatanodeHeartbeatDispatcher;
import org.apache.hadoop.hdds.server.events.EventHandler;
import org.apache.hadoop.hdds.server.events.EventPublisher;
import org.apache.hadoop.hdds.server.events.EventQueue;
import org.apache.ozone.test.GenericTestUtils;

import org.junit.After;
import org.junit.Assert;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;
import org.mockito.Mockito;

/** Test class for SCMSafeModeManager.
 */
public class TestSCMSafeModeManager {

  private EventQueue queue;
  private SCMContext scmContext;
  private SCMServiceManager serviceManager;
  private SCMSafeModeManager scmSafeModeManager;
  private OzoneConfiguration config;
  private List<ContainerInfo> containers = Collections.emptyList();

  @Rule
  public Timeout timeout = Timeout.seconds(300);

  @Rule
  public final TemporaryFolder tempDir = new TemporaryFolder();

  private SCMMetadataStore scmMetadataStore;

  @Before
  public void setUp() {
    queue = new EventQueue();
    scmContext = SCMContext.emptyContext();
    serviceManager = new SCMServiceManager();
    config = new OzoneConfiguration();
    config.setBoolean(HddsConfigKeys.HDDS_SCM_SAFEMODE_PIPELINE_CREATION,
        false);
  }

  @Before
  public void initDbStore() throws IOException {
    config.set(HddsConfigKeys.OZONE_METADATA_DIRS,
        tempDir.newFolder().getAbsolutePath());
    scmMetadataStore = new SCMMetadataStoreImpl(config);
  }

  @After
  public void destroyDbStore() throws Exception {
    if (scmMetadataStore.getStore() != null) {
      scmMetadataStore.getStore().close();
    }
  }

  @Test
  public void testSafeModeState() throws Exception {
    // Test 1: test for 0 containers
    testSafeMode(0);

    // Test 2: test for 20 containers
    testSafeMode(20);
  }

  @Test
  public void testSafeModeStateWithNullContainers() {
    new SCMSafeModeManager(config, Collections.emptyList(),
        null, null,  queue, serviceManager, scmContext);
  }

  private void testSafeMode(int numContainers) throws Exception {
    containers = new ArrayList<>();
    containers.addAll(HddsTestUtils.getContainerInfo(numContainers));

    // Currently only considered containers which are not in open state.
    for (ContainerInfo container : containers) {
      container.setState(HddsProtos.LifeCycleState.CLOSED);
    }
    scmSafeModeManager = new SCMSafeModeManager(
        config, containers, null, null, queue,
        serviceManager, scmContext);

    assertTrue(scmSafeModeManager.getInSafeMode());
    queue.fireEvent(SCMEvents.NODE_REGISTRATION_CONT_REPORT,
        HddsTestUtils.createNodeRegistrationContainerReport(containers));

    long cutOff = (long) Math.ceil(numContainers * config.getDouble(
        HddsConfigKeys.HDDS_SCM_SAFEMODE_THRESHOLD_PCT,
        HddsConfigKeys.HDDS_SCM_SAFEMODE_THRESHOLD_PCT_DEFAULT));

    Assert.assertEquals(cutOff, scmSafeModeManager.getSafeModeMetrics()
        .getNumContainerWithOneReplicaReportedThreshold().value());

    GenericTestUtils.waitFor(() -> {
      return !scmSafeModeManager.getInSafeMode();
    }, 100, 1000 * 5);

    Assert.assertEquals(cutOff, scmSafeModeManager.getSafeModeMetrics()
        .getCurrentContainersWithOneReplicaReportedCount().value());

  }

  @Test
  public void testSafeModeExitRule() throws Exception {
    containers = new ArrayList<>();
    int numContainers = 100;
    containers.addAll(HddsTestUtils.getContainerInfo(numContainers));
    // Assign open state to containers to be included in the safe mode
    // container list
    for (ContainerInfo container : containers) {
      container.setState(HddsProtos.LifeCycleState.CLOSED);
    }
    scmSafeModeManager = new SCMSafeModeManager(
        config, containers, null, null, queue,
        serviceManager, scmContext);

    long cutOff = (long) Math.ceil(numContainers * config.getDouble(
        HddsConfigKeys.HDDS_SCM_SAFEMODE_THRESHOLD_PCT,
        HddsConfigKeys.HDDS_SCM_SAFEMODE_THRESHOLD_PCT_DEFAULT));

    Assert.assertEquals(cutOff, scmSafeModeManager.getSafeModeMetrics()
        .getNumContainerWithOneReplicaReportedThreshold().value());

    assertTrue(scmSafeModeManager.getInSafeMode());

    testContainerThreshold(containers.subList(0, 25), 0.25);
    Assert.assertEquals(25, scmSafeModeManager.getSafeModeMetrics()
        .getCurrentContainersWithOneReplicaReportedCount().value());
    assertTrue(scmSafeModeManager.getInSafeMode());
    testContainerThreshold(containers.subList(25, 50), 0.50);
    Assert.assertEquals(50, scmSafeModeManager.getSafeModeMetrics()
        .getCurrentContainersWithOneReplicaReportedCount().value());
    assertTrue(scmSafeModeManager.getInSafeMode());
    testContainerThreshold(containers.subList(50, 75), 0.75);
    Assert.assertEquals(75, scmSafeModeManager.getSafeModeMetrics()
        .getCurrentContainersWithOneReplicaReportedCount().value());
    assertTrue(scmSafeModeManager.getInSafeMode());
    testContainerThreshold(containers.subList(75, 100), 1.0);
    Assert.assertEquals(100, scmSafeModeManager.getSafeModeMetrics()
        .getCurrentContainersWithOneReplicaReportedCount().value());

    GenericTestUtils.waitFor(() -> {
      return !scmSafeModeManager.getInSafeMode();
    }, 100, 1000 * 5);
  }


  private OzoneConfiguration createConf(double healthyPercent,
      double oneReplicaPercent) throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(HddsConfigKeys.OZONE_METADATA_DIRS,
        tempDir.newFolder().toString());
    conf.setBoolean(
        HddsConfigKeys.HDDS_SCM_SAFEMODE_PIPELINE_AVAILABILITY_CHECK,
        true);
    conf.setDouble(HddsConfigKeys.
        HDDS_SCM_SAFEMODE_HEALTHY_PIPELINE_THRESHOLD_PCT, healthyPercent);
    conf.setDouble(HddsConfigKeys.
        HDDS_SCM_SAFEMODE_ONE_NODE_REPORTED_PIPELINE_PCT, oneReplicaPercent);
    conf.setBoolean(HddsConfigKeys.HDDS_SCM_SAFEMODE_PIPELINE_CREATION, false);
    return conf;
  }

  @Test
  public void testSafeModeExitRuleWithPipelineAvailabilityCheck1()
      throws Exception {
    testSafeModeExitRuleWithPipelineAvailabilityCheck(100, 30, 8, 0.90, 1);
  }

  @Test
  public void testSafeModeExitRuleWithPipelineAvailabilityCheck2()
      throws Exception {
    testSafeModeExitRuleWithPipelineAvailabilityCheck(100, 90, 22, 0.10, 0.9);
  }

  @Test
  public void testSafeModeExitRuleWithPipelineAvailabilityCheck3()
      throws Exception {
    testSafeModeExitRuleWithPipelineAvailabilityCheck(100, 30, 8, 0, 0.9);
  }

  @Test
  public void testSafeModeExitRuleWithPipelineAvailabilityCheck4()
      throws Exception {
    testSafeModeExitRuleWithPipelineAvailabilityCheck(100, 90, 22, 0, 0);
  }

  @Test
  public void testSafeModeExitRuleWithPipelineAvailabilityCheck5()
      throws Exception {
    testSafeModeExitRuleWithPipelineAvailabilityCheck(100, 90, 22, 0, 0.5);
  }

  @Test
  public void testFailWithIncorrectValueForHealthyPipelinePercent()
      throws Exception {
    try {
      OzoneConfiguration conf = createConf(100,
          0.9);
      MockNodeManager mockNodeManager = new MockNodeManager(true, 10);
      PipelineManager pipelineManager =
          PipelineManagerImpl.newPipelineManager(
              conf,
              SCMHAManagerStub.getInstance(true),
              mockNodeManager,
              scmMetadataStore.getPipelineTable(),
              queue,
              scmContext,
              serviceManager);
      scmSafeModeManager = new SCMSafeModeManager(
          conf, containers, null, pipelineManager, queue, serviceManager,
          scmContext);
      fail("testFailWithIncorrectValueForHealthyPipelinePercent");
    } catch (IllegalArgumentException ex) {
      GenericTestUtils.assertExceptionContains("value should be >= 0.0 and <=" +
          " 1.0", ex);
    }
  }

  @Test
  public void testFailWithIncorrectValueForOneReplicaPipelinePercent()
      throws Exception {
    try {
      OzoneConfiguration conf = createConf(0.9,
          200);
      MockNodeManager mockNodeManager = new MockNodeManager(true, 10);
      PipelineManager pipelineManager =
          PipelineManagerImpl.newPipelineManager(
              conf,
              SCMHAManagerStub.getInstance(true),
              mockNodeManager,
              scmMetadataStore.getPipelineTable(),
              queue,
              scmContext,
              serviceManager);
      scmSafeModeManager = new SCMSafeModeManager(
          conf, containers, null, pipelineManager, queue, serviceManager,
          scmContext);
      fail("testFailWithIncorrectValueForOneReplicaPipelinePercent");
    } catch (IllegalArgumentException ex) {
      GenericTestUtils.assertExceptionContains("value should be >= 0.0 and <=" +
          " 1.0", ex);
    }
  }

  @Test
  public void testFailWithIncorrectValueForSafeModePercent() throws Exception {
    try {
      OzoneConfiguration conf = createConf(0.9, 0.1);
      conf.setDouble(HddsConfigKeys.HDDS_SCM_SAFEMODE_THRESHOLD_PCT, -1.0);
      MockNodeManager mockNodeManager = new MockNodeManager(true, 10);
      PipelineManager pipelineManager =
          PipelineManagerImpl.newPipelineManager(
              conf,
              SCMHAManagerStub.getInstance(true),
              mockNodeManager,
              scmMetadataStore.getPipelineTable(),
              queue,
              scmContext,
              serviceManager);
      scmSafeModeManager = new SCMSafeModeManager(
          conf, containers, null, pipelineManager, queue, serviceManager,
          scmContext);
      fail("testFailWithIncorrectValueForSafeModePercent");
    } catch (IllegalArgumentException ex) {
      GenericTestUtils.assertExceptionContains("value should be >= 0.0 and <=" +
          " 1.0", ex);
    }
  }


  public void testSafeModeExitRuleWithPipelineAvailabilityCheck(
      int containerCount, int nodeCount, int pipelineCount,
      double healthyPipelinePercent, double oneReplicaPercent)
      throws Exception {

    OzoneConfiguration conf = createConf(healthyPipelinePercent,
        oneReplicaPercent);

    containers = new ArrayList<>();
    containers.addAll(HddsTestUtils.getContainerInfo(containerCount));

    MockNodeManager mockNodeManager = new MockNodeManager(true, nodeCount);
    PipelineManagerImpl pipelineManager =
        PipelineManagerImpl.newPipelineManager(
            conf,
            SCMHAManagerStub.getInstance(true),
            mockNodeManager,
            scmMetadataStore.getPipelineTable(),
            queue,
            scmContext,
            serviceManager);
    PipelineProvider mockRatisProvider =
        new MockRatisPipelineProvider(mockNodeManager,
            pipelineManager.getStateManager(), config);
    pipelineManager.setPipelineProvider(HddsProtos.ReplicationType.RATIS,
        mockRatisProvider);
    pipelineManager.getBackgroundPipelineCreator().stop();

    for (int i = 0; i < pipelineCount; i++) {
      // Create pipeline
      Pipeline pipeline = pipelineManager.createPipeline(
          RatisReplicationConfig.getInstance(
              ReplicationFactor.THREE));

      pipelineManager.openPipeline(pipeline.getId());
      // Mark pipeline healthy
      pipeline = pipelineManager.getPipeline(pipeline.getId());
      MockRatisPipelineProvider.markPipelineHealthy(pipeline);
    }

    for (ContainerInfo container : containers) {
      container.setState(HddsProtos.LifeCycleState.CLOSED);
    }

    scmSafeModeManager = new SCMSafeModeManager(
        conf, containers, null, pipelineManager, queue, serviceManager,
        scmContext);

    assertTrue(scmSafeModeManager.getInSafeMode());
    testContainerThreshold(containers, 1.0);

    List<Pipeline> pipelines = pipelineManager.getPipelines();

    int healthyPipelineThresholdCount =
        scmSafeModeManager.getHealthyPipelineSafeModeRule()
            .getHealthyPipelineThresholdCount();
    int oneReplicaThresholdCount =
        scmSafeModeManager.getOneReplicaPipelineSafeModeRule()
            .getThresholdCount();

    Assert.assertEquals(healthyPipelineThresholdCount,
        scmSafeModeManager.getSafeModeMetrics()
            .getNumHealthyPipelinesThreshold().value());

    Assert.assertEquals(oneReplicaThresholdCount,
        scmSafeModeManager.getSafeModeMetrics()
            .getNumPipelinesWithAtleastOneReplicaReportedThreshold().value());

    // Because even if no pipelines are there, and threshold we set to zero,
    // we shall a get an event when datanode is registered. In that case,
    // validate will return true, and add this to validatedRules.
    if (Math.max(healthyPipelinePercent, oneReplicaThresholdCount) == 0) {
      firePipelineEvent(pipelineManager, pipelines.get(0));
    }

    for (int i = 0; i < Math.max(healthyPipelineThresholdCount,
        Math.min(oneReplicaThresholdCount, pipelines.size())); i++) {
      firePipelineEvent(pipelineManager, pipelines.get(i));

      if (i < healthyPipelineThresholdCount) {
        checkHealthy(i + 1);
        Assert.assertEquals(i + 1,
            scmSafeModeManager.getSafeModeMetrics()
                .getCurrentHealthyPipelinesCount().value());
      }

      if (i < oneReplicaThresholdCount) {
        checkOpen(i + 1);
        Assert.assertEquals(i + 1,
            scmSafeModeManager.getSafeModeMetrics()
                .getCurrentPipelinesWithAtleastOneReplicaCount().value());
      }
    }

    Assert.assertEquals(healthyPipelineThresholdCount,
        scmSafeModeManager.getSafeModeMetrics()
            .getCurrentHealthyPipelinesCount().value());

    Assert.assertEquals(oneReplicaThresholdCount,
        scmSafeModeManager.getSafeModeMetrics()
            .getCurrentPipelinesWithAtleastOneReplicaCount().value());


    GenericTestUtils.waitFor(() -> {
      return !scmSafeModeManager.getInSafeMode();
    }, 100, 1000 * 5);
  }

  private void checkHealthy(int expectedCount) throws Exception {
    GenericTestUtils.waitFor(() -> scmSafeModeManager
            .getHealthyPipelineSafeModeRule()
            .getCurrentHealthyPipelineCount() == expectedCount,
        100,  5000);
  }

  private void checkOpen(int expectedCount) throws Exception {
    GenericTestUtils.waitFor(() -> scmSafeModeManager
            .getOneReplicaPipelineSafeModeRule()
            .getCurrentReportedPipelineCount() == expectedCount,
        1000,  5000);
  }

  private void firePipelineEvent(PipelineManager pipelineManager,
      Pipeline pipeline) throws Exception {
    pipelineManager.openPipeline(pipeline.getId());
    queue.fireEvent(SCMEvents.OPEN_PIPELINE,
        pipelineManager.getPipeline(pipeline.getId()));

    for (DatanodeDetails dn : pipeline.getNodes()) {
      List<StorageContainerDatanodeProtocolProtos.
              PipelineReport> reports = new ArrayList<>();
      HddsProtos.PipelineID pipelineID = pipeline.getId().getProtobuf();
      reports.add(StorageContainerDatanodeProtocolProtos
              .PipelineReport.newBuilder()
              .setPipelineID(pipelineID)
              .setIsLeader(true)
              .setBytesWritten(0)
              .build());
      StorageContainerDatanodeProtocolProtos
              .PipelineReportsProto.Builder pipelineReportsProto =
              StorageContainerDatanodeProtocolProtos
                      .PipelineReportsProto.newBuilder();
      pipelineReportsProto.addAllPipelineReport(reports);
      queue.fireEvent(SCMEvents.PIPELINE_REPORT, new
              SCMDatanodeHeartbeatDispatcher
                      .PipelineReportFromDatanode(dn,
              pipelineReportsProto.build()));
    }
  }


  @Test
  public void testDisableSafeMode() throws IOException {
    OzoneConfiguration conf = new OzoneConfiguration(config);
    conf.setBoolean(HddsConfigKeys.HDDS_SCM_SAFEMODE_ENABLED, false);
    PipelineManager pipelineManager = Mockito.mock(PipelineManager.class);
    scmSafeModeManager = new SCMSafeModeManager(
        conf, containers, null, pipelineManager, queue, serviceManager,
        scmContext);
    assertFalse(scmSafeModeManager.getInSafeMode());
  }

  @Test
  public void testSafeModeDataNodeExitRule() throws Exception {
    containers = new ArrayList<>();
    testSafeModeDataNodes(0);
    testSafeModeDataNodes(3);
    testSafeModeDataNodes(5);
  }

  /**
   * Check that containers in Allocated state are not considered while
   * computing percentage of containers with at least 1 reported replica in
   * safe mode exit rule.
   */
  @Test
  public void testContainerSafeModeRule() throws Exception {
    containers = new ArrayList<>();
    // Add 100 containers to the list of containers in SCM
    containers.addAll(HddsTestUtils.getContainerInfo(25 * 4));
    // Assign CLOSED state to first 25 containers and OPEM state to rest
    // of the containers
    for (ContainerInfo container : containers.subList(0, 25)) {
      container.setState(HddsProtos.LifeCycleState.CLOSED);
    }
    for (ContainerInfo container : containers.subList(25, 100)) {
      container.setState(HddsProtos.LifeCycleState.OPEN);
    }

    scmSafeModeManager = new SCMSafeModeManager(
        config, containers, null, null, queue, serviceManager, scmContext);

    assertTrue(scmSafeModeManager.getInSafeMode());

    // When 10 CLOSED containers are reported by DNs, the computed container
    // threshold should be 10/25 as there are only 25 CLOSED containers.
    // Containers in OPEN state should not contribute towards list of
    // containers while calculating container threshold in SCMSafeNodeManager
    testContainerThreshold(containers.subList(0, 10), 0.4);
    assertTrue(scmSafeModeManager.getInSafeMode());

    // When remaining 15 OPEN containers are reported by DNs, the container
    // threshold should be (10+15)/25.
    testContainerThreshold(containers.subList(10, 25), 1.0);

    GenericTestUtils.waitFor(() -> {
      return !scmSafeModeManager.getInSafeMode();
    }, 100, 1000 * 5);
  }

  private void testSafeModeDataNodes(int numOfDns) throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration(config);
    conf.setInt(HddsConfigKeys.HDDS_SCM_SAFEMODE_MIN_DATANODE, numOfDns);
    scmSafeModeManager = new SCMSafeModeManager(
        conf, containers, null, null, queue,
        serviceManager, scmContext);

    // Assert SCM is in Safe mode.
    assertTrue(scmSafeModeManager.getInSafeMode());

    // Register all DataNodes except last one and assert SCM is in safe mode.
    for (int i = 0; i < numOfDns - 1; i++) {
      queue.fireEvent(SCMEvents.NODE_REGISTRATION_CONT_REPORT,
          HddsTestUtils.createNodeRegistrationContainerReport(containers));
      assertTrue(scmSafeModeManager.getInSafeMode());
      assertTrue(scmSafeModeManager.getCurrentContainerThreshold() == 1);
    }

    if (numOfDns == 0) {
      GenericTestUtils.waitFor(() -> {
        return scmSafeModeManager.getInSafeMode();
      }, 10, 1000 * 10);
      return;
    }
    // Register last DataNode and check that SCM is out of Safe mode.
    queue.fireEvent(SCMEvents.NODE_REGISTRATION_CONT_REPORT,
        HddsTestUtils.createNodeRegistrationContainerReport(containers));
    GenericTestUtils.waitFor(() -> {
      return !scmSafeModeManager.getInSafeMode();
    }, 10, 1000 * 10);
  }

  private void testContainerThreshold(List<ContainerInfo> dnContainers,
      double expectedThreshold)
      throws Exception {
    queue.fireEvent(SCMEvents.NODE_REGISTRATION_CONT_REPORT,
        HddsTestUtils.createNodeRegistrationContainerReport(dnContainers));
    GenericTestUtils.waitFor(() -> {
      double threshold = scmSafeModeManager.getCurrentContainerThreshold();
      return threshold == expectedThreshold;
    }, 100, 2000 * 9);
  }

  @Test
  public void testSafeModePipelineExitRule() throws Exception {
    containers = new ArrayList<>();
    containers.addAll(HddsTestUtils.getContainerInfo(25 * 4));
    String storageDir = GenericTestUtils.getTempPath(
        TestSCMSafeModeManager.class.getName() + UUID.randomUUID());
    try {
      MockNodeManager nodeManager = new MockNodeManager(true, 3);
      config.set(HddsConfigKeys.OZONE_METADATA_DIRS, storageDir);
      // enable pipeline check
      config.setBoolean(
          HddsConfigKeys.HDDS_SCM_SAFEMODE_PIPELINE_AVAILABILITY_CHECK, true);

      PipelineManagerImpl pipelineManager =
          PipelineManagerImpl.newPipelineManager(
              config,
              SCMHAManagerStub.getInstance(true),
              nodeManager,
              scmMetadataStore.getPipelineTable(),
              queue,
              scmContext,
              serviceManager);

      PipelineProvider mockRatisProvider =
          new MockRatisPipelineProvider(nodeManager,
              pipelineManager.getStateManager(), config);
      pipelineManager.setPipelineProvider(HddsProtos.ReplicationType.RATIS,
          mockRatisProvider);

      Pipeline pipeline = pipelineManager.createPipeline(
          RatisReplicationConfig.getInstance(
              ReplicationFactor.THREE));

      pipeline = pipelineManager.getPipeline(pipeline.getId());
      MockRatisPipelineProvider.markPipelineHealthy(pipeline);

      scmSafeModeManager = new SCMSafeModeManager(
          config, containers, null, pipelineManager, queue, serviceManager,
          scmContext);

      queue.fireEvent(SCMEvents.NODE_REGISTRATION_CONT_REPORT,
          HddsTestUtils.createNodeRegistrationContainerReport(containers));
      assertTrue(scmSafeModeManager.getInSafeMode());

      firePipelineEvent(pipelineManager, pipeline);

      GenericTestUtils.waitFor(() -> {
        return !scmSafeModeManager.getInSafeMode();
      }, 100, 1000 * 10);
      pipelineManager.close();
    } finally {
      config.setBoolean(
          HddsConfigKeys.HDDS_SCM_SAFEMODE_PIPELINE_AVAILABILITY_CHECK,
          false);
      FileUtil.fullyDelete(new File(storageDir));
    }
  }

  @Test
  @Ignore("The test is failing, enable after fixing it")
  public void testPipelinesNotCreatedUntilPreCheckPasses()
      throws Exception {
    int numOfDns = 5;
    // enable pipeline check
    config.setBoolean(
        HddsConfigKeys.HDDS_SCM_SAFEMODE_PIPELINE_AVAILABILITY_CHECK, true);
    config.setInt(HddsConfigKeys.HDDS_SCM_SAFEMODE_MIN_DATANODE, numOfDns);
    config.setBoolean(HddsConfigKeys.HDDS_SCM_SAFEMODE_PIPELINE_CREATION,
        true);

    MockNodeManager nodeManager = new MockNodeManager(true, numOfDns);
    String storageDir = GenericTestUtils.getTempPath(
        TestSCMSafeModeManager.class.getName() + UUID.randomUUID());
    config.set(HddsConfigKeys.OZONE_METADATA_DIRS, storageDir);
    // enable pipeline check
    config.setBoolean(
        HddsConfigKeys.HDDS_SCM_SAFEMODE_PIPELINE_AVAILABILITY_CHECK, true);

    PipelineManagerImpl pipelineManager =
        PipelineManagerImpl.newPipelineManager(
            config,
            SCMHAManagerStub.getInstance(true),
            nodeManager,
            scmMetadataStore.getPipelineTable(),
            queue,
            scmContext,
            serviceManager);

    PipelineProvider mockRatisProvider =
        new MockRatisPipelineProvider(nodeManager,
            pipelineManager.getStateManager(), config);
    pipelineManager.setPipelineProvider(HddsProtos.ReplicationType.RATIS,
        mockRatisProvider);

    SafeModeEventHandler smHandler = new SafeModeEventHandler();
    queue.addHandler(SCMEvents.SAFE_MODE_STATUS, smHandler);
    scmSafeModeManager = new SCMSafeModeManager(
        config, containers, null, pipelineManager, queue, serviceManager,
        scmContext);

    // Assert SCM is in Safe mode.
    assertTrue(scmSafeModeManager.getInSafeMode());

    // Register all DataNodes except last one and assert SCM is in safe mode.
    for (int i = 0; i < numOfDns - 1; i++) {
      queue.fireEvent(SCMEvents.NODE_REGISTRATION_CONT_REPORT,
          HddsTestUtils.createNodeRegistrationContainerReport(containers));
      assertTrue(scmSafeModeManager.getInSafeMode());
      assertFalse(scmSafeModeManager.getPreCheckComplete());
    }
    queue.processAll(5000);
    Assert.assertEquals(0, smHandler.getInvokedCount());

    // Register last DataNode and check that the SafeModeEvent gets fired, but
    // safemode is still enabled with preCheck completed.
    queue.fireEvent(SCMEvents.NODE_REGISTRATION_CONT_REPORT,
        HddsTestUtils.createNodeRegistrationContainerReport(containers));
    queue.processAll(5000);

    Assert.assertEquals(1, smHandler.getInvokedCount());
    Assert.assertEquals(true, smHandler.getPreCheckComplete());
    Assert.assertEquals(true, smHandler.getIsInSafeMode());

    /* There is a race condition where the background pipeline creation
     * task creates the pipeline before the following create call.
     * So wrapping it with try..catch.
     */
    Pipeline pipeline;
    try {
      pipeline = pipelineManager.createPipeline(
          RatisReplicationConfig.getInstance(
              ReplicationFactor.THREE));
    } catch (SCMException ex) {
      pipeline = pipelineManager.getPipelines(
          RatisReplicationConfig.getInstance(
              ReplicationFactor.THREE)).get(0);
    }

    // Mark pipeline healthy
    pipeline = pipelineManager.getPipeline(pipeline.getId());
    MockRatisPipelineProvider.markPipelineHealthy(pipeline);

    firePipelineEvent(pipelineManager, pipeline);

    queue.processAll(5000);
    Assert.assertEquals(2, smHandler.getInvokedCount());
    Assert.assertEquals(true, smHandler.getPreCheckComplete());
    Assert.assertEquals(false, smHandler.getIsInSafeMode());
  }

  private static class SafeModeEventHandler
      implements EventHandler<SCMSafeModeManager.SafeModeStatus> {

    private AtomicInteger invokedCount = new AtomicInteger(0);
    private AtomicBoolean preCheckComplete = new AtomicBoolean(false);
    private AtomicBoolean isInSafeMode = new AtomicBoolean(true);

    public int getInvokedCount() {
      return invokedCount.get();
    }

    public boolean getPreCheckComplete() {
      return preCheckComplete.get();
    }

    public boolean getIsInSafeMode() {
      return isInSafeMode.get();
    }

    @Override
    public void onMessage(SCMSafeModeManager.SafeModeStatus safeModeStatus,
        EventPublisher publisher) {
      invokedCount.incrementAndGet();
      preCheckComplete.set(safeModeStatus.isPreCheckComplete());
      isInSafeMode.set(safeModeStatus.isInSafeMode());
    }
  }
}