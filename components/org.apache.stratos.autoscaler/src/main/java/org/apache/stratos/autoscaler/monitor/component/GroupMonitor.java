/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.stratos.autoscaler.monitor.component;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.autoscaler.algorithm.AutoscaleAlgorithm;
import org.apache.stratos.autoscaler.applications.ApplicationHolder;
import org.apache.stratos.autoscaler.applications.dependency.context.ApplicationChildContext;
import org.apache.stratos.autoscaler.applications.dependency.context.GroupChildContext;
import org.apache.stratos.autoscaler.applications.topic.ApplicationBuilder;
import org.apache.stratos.autoscaler.context.AutoscalerContext;
import org.apache.stratos.autoscaler.context.group.GroupInstanceContext;
import org.apache.stratos.autoscaler.context.partition.network.GroupLevelNetworkPartitionContext;
import org.apache.stratos.autoscaler.exception.application.DependencyBuilderException;
import org.apache.stratos.autoscaler.exception.application.MonitorNotFoundException;
import org.apache.stratos.autoscaler.exception.application.TopologyInConsistentException;
import org.apache.stratos.autoscaler.monitor.Monitor;
import org.apache.stratos.autoscaler.monitor.events.GroupStatusEvent;
import org.apache.stratos.autoscaler.monitor.events.MonitorScalingEvent;
import org.apache.stratos.autoscaler.monitor.events.MonitorStatusEvent;
import org.apache.stratos.autoscaler.monitor.events.builder.MonitorStatusEventBuilder;
import org.apache.stratos.autoscaler.context.partition.GroupLevelPartitionContext;
import org.apache.stratos.autoscaler.context.partition.PartitionContext;
import org.apache.stratos.autoscaler.pojo.policy.PolicyManager;
import org.apache.stratos.autoscaler.pojo.policy.deployment.ChildPolicy;
import org.apache.stratos.autoscaler.pojo.policy.deployment.DeploymentPolicy;
import org.apache.stratos.autoscaler.pojo.policy.deployment.partition.network.ChildLevelNetworkPartition;
import org.apache.stratos.autoscaler.util.ServiceReferenceHolder;
import org.apache.stratos.autoscaler.pojo.policy.deployment.partition.network.ChildLevelPartition;
import org.apache.stratos.cloud.controller.stub.domain.Partition;
import org.apache.stratos.messaging.domain.applications.Application;
import org.apache.stratos.messaging.domain.applications.ApplicationStatus;
import org.apache.stratos.messaging.domain.applications.Group;
import org.apache.stratos.messaging.domain.applications.GroupStatus;
import org.apache.stratos.messaging.domain.instance.GroupInstance;
import org.apache.stratos.messaging.domain.instance.Instance;
import org.apache.stratos.messaging.domain.topology.ClusterStatus;
import org.apache.stratos.messaging.domain.topology.lifecycle.LifeCycleState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This is GroupMonitor to monitor the group which consists of
 * groups and clusters
 */
public class GroupMonitor extends ParentComponentMonitor implements Runnable {

    private static final Log log = LogFactory.getLog(GroupMonitor.class);

    //whether groupScaling enabled or not
    private boolean groupScalingEnabled;
    //network partition contexts
    private Map<String, GroupLevelNetworkPartitionContext> networkPartitionCtxts;
    //Whether the monitor is destroyed or not
    private boolean isDestroyed;
    
    private List<GroupLevelPartitionContext> partitionContexts;
    private Map<String, GroupInstance> groupInstanceIdMap;

    //monitoring interval of the monitor
    private int monitoringIntervalMilliseconds = 60000;     //TODO get this from config file

    /**
     * Constructor of GroupMonitor
     *
     * @param group Takes the group from the Topology
     * @throws DependencyBuilderException    throws when couldn't build the Topology
     * @throws TopologyInConsistentException throws when topology is inconsistent
     */
    public GroupMonitor(Group group, String appId, List<String> parentInstanceId) throws DependencyBuilderException,
            TopologyInConsistentException {
        super(group);
        this.appId = appId;
        networkPartitionCtxts = new HashMap<String, GroupLevelNetworkPartitionContext>();
        partitionContexts = new ArrayList<GroupLevelPartitionContext>();
    }

    @Override
    public void run() {
        while (!isDestroyed) {
            try {

                if (log.isDebugEnabled()) {
                    log.debug("Group monitor is running : " + this.toString());
                }
                monitor();
            } catch (Exception e) {
                log.error("Group monitor failed : " + this.toString(), e);
            }
            try {
                Thread.sleep(monitoringIntervalMilliseconds);
            } catch (InterruptedException ignore) {
            }
        }


    }


    public void monitor() {

        Runnable monitoringRunnable = new Runnable() {
            @Override
            public void run() {
                //TODO implement group monitor

            }
        };
        monitoringRunnable.run();
    }


    /**
     * Will set the status of the monitor based on Topology Group status/child status like scaling
     *
     * @param status status of the group
     */
    public void setStatus(GroupStatus status, String instanceId) {
        ((GroupInstance)this.instanceIdToInstanceMap.get(instanceId)).setStatus(status);

        if (status == GroupStatus.Inactive && !this.hasStartupDependents) {
            log.info("[Group] " + this.id + "is not notifying the parent, " +
                    "since it is identified as the independent unit");
        } else {
            // notify parent
            log.info("[Group] " + this.id + "is notifying the [parent] " + this.parent.getId());
            if (this.isGroupScalingEnabled()) {
                ApplicationHolder.acquireReadLock();
                try {
                    Application application = ApplicationHolder.getApplications().
                            getApplication(this.appId);
                    if (application != null) {
                        //Notifying the parent using parent's instance Id,
                        // as it has group scaling enabled.
                        Group group = application.getGroupRecursively(this.id);
                        if (group != null) {
                            GroupInstance context = group.getInstanceContexts(instanceId);
                            MonitorStatusEventBuilder.handleGroupStatusEvent(this.parent,
                                    status, this.id, context.getParentId());

                        }
                    }
                } finally {
                    ApplicationHolder.releaseReadLock();
                }
            } else {
                MonitorStatusEventBuilder.handleGroupStatusEvent(this.parent,
                        status, this.id, instanceId);
            }
        }
        //notify the children about the state change
        try {
            MonitorStatusEventBuilder.notifyChildren(this, new GroupStatusEvent(status, this.id, instanceId));
        } catch (MonitorNotFoundException e) {
            log.error("Error while notifying the children from the [group] " + this.id, e);
            //TODO revert siblings
        }
    }

    @Override
    public void onChildStatusEvent(MonitorStatusEvent statusEvent) {
        String id = statusEvent.getId();
        String instanceId = statusEvent.getInstanceId();
        LifeCycleState status1 = statusEvent.getStatus();
        //Events coming from parent are In_Active(in faulty detection), Scaling events, termination
        if (status1 == ClusterStatus.Active || status1 == GroupStatus.Active) {
            onChildActivatedEvent(id, instanceId);

        } else if (status1 == ClusterStatus.Inactive || status1 == GroupStatus.Inactive) {
            this.markMonitorAsInactive(instanceId);
            onChildInactiveEvent(id, instanceId);

        } else if (status1 == ClusterStatus.Created || status1 == GroupStatus.Created) {
            if (this.terminatingMonitorsList.contains(instanceId)) {
                this.terminatingMonitorsList.remove(instanceId);
                this.aliasToActiveMonitorsMap.remove(instanceId);
                if (AutoscalerContext.getInstance().getClusterMonitors().containsKey(id)) {
                    AutoscalerContext.getInstance().removeClusterMonitor(id);
                }
            }
        } else if (status1 == ClusterStatus.Terminating || status1 == GroupStatus.Terminating) {
            //mark the child monitor as inActive in the map
            this.markMonitorAsTerminating(instanceId);

        } else if (status1 == ClusterStatus.Terminated || status1 == GroupStatus.Terminated) {
            //Check whether all dependent goes Terminated and then start them in parallel.
            if (this.terminatingMonitorsList.contains(id)) {
                this.terminatingMonitorsList.remove(id);
                this.aliasToActiveMonitorsMap.remove(id);
            } else {
                log.warn("[monitor] " + id + " cannot be found in the inActive monitors list");
            }
            //If cluster monitor, need to terminate the existing one
            ApplicationHolder.releaseReadLock();
            GroupStatus instanceStatus;
            try {
                Group group = ApplicationHolder.getApplications().
                        getApplication(appId).getGroupRecursively(this.id);
                instanceStatus = group.getInstanceContexts(instanceId).getStatus();

            } finally {
                ApplicationHolder.releaseReadLock();
            }

            if (instanceStatus == GroupStatus.Terminating) {
                ServiceReferenceHolder.getInstance().getGroupStatusProcessorChain().process(this.id,
                        appId, instanceId);
            } else {
                onChildTerminatedEvent(id, instanceId);
            }
        }
    }

    @Override
    public void onParentStatusEvent(MonitorStatusEvent statusEvent)
            throws MonitorNotFoundException {
        String instanceId = statusEvent.getInstanceId();
        // send the ClusterTerminating event
        if (statusEvent.getStatus() == GroupStatus.Terminating ||
                statusEvent.getStatus() == ApplicationStatus.Terminating) {
            //Get all the instances which related to this instanceId
            GroupInstance instance = (GroupInstance) this.instanceIdToInstanceMap.get(instanceId);
            if(instance != null) {
                ApplicationBuilder.handleGroupTerminatingEvent(appId, id, instanceId);
            } else {
                //Using parentId need to get the children
                List<String> instanceIds = this.getInstancesByParentInstanceId(instanceId);
                if(!instanceIds.isEmpty()) {
                    for(String instanceId1 : instanceIds) {
                        ApplicationBuilder.handleGroupTerminatingEvent(appId, id, instanceId1);
                    }
                }

            }
        } else if (statusEvent.getStatus() == ClusterStatus.Created ||
                statusEvent.getStatus() == GroupStatus.Created) {
            Application application = ApplicationHolder.getApplications().getApplication(this.appId);
            Group group = application.getGroupRecursively(statusEvent.getId());
            //starting a new instance of this monitor
            createInstanceAndStartDependency(group, statusEvent.getInstanceId());
        }
    }

    @Override
    public void onChildScalingEvent(MonitorScalingEvent scalingEvent) {

        if (hasGroupScalingDependent) {

            //notify parent
            parent.onChildScalingEvent(scalingEvent);
        }

        if (log.isDebugEnabled()) {
            log.debug("Child scaling event received to [group]: " + this.getId()
                    + ", [network partition]: " + scalingEvent.getNetworkPartitionId()
                    + ", [event] " + scalingEvent.getId() + ", [group instance] " + scalingEvent.getInstanceId());
        }

        //find the child context of this group, from scaling dependency tree
        GroupChildContext currentChildContextInScalingTree =
                (GroupChildContext) scalingDependencyTree.findApplicationContextWithIdInScalingDependencyTree(id);

        //Notifying children, if this group has scaling dependencies
        if (currentChildContextInScalingTree.isGroupScalingEnabled()) {
            for (ApplicationChildContext applicationChildContext :
                    currentChildContextInScalingTree.getApplicationChildContextList()) {

                //Get group monitor so that it can notify it's children
                Monitor monitor = aliasToActiveMonitorsMap.get(applicationChildContext.getId());

                if (monitor instanceof GroupMonitor || monitor instanceof ApplicationMonitor) {

                    monitor.onParentScalingEvent(scalingEvent);
                }
            }
        }
    }

    @Override
    public void onParentScalingEvent(MonitorScalingEvent scalingEvent) {

        //Notify all children about scaling
    }

    @Override
    public void onEvent(MonitorScalingEvent scalingEvent) {

    }

    public boolean isGroupScalingEnabled() {
        return groupScalingEnabled;
    }

    public void setGroupScalingEnabled(boolean groupScalingEnabled) {
        this.groupScalingEnabled = groupScalingEnabled;
    }

    /**
     * This will start the minimum required dependency instances
     * based on the given parent instance ids
     *
     * @param group             blue print of the instance to be started
     * @param parentInstanceIds parent instanceIds used to start the child instance
     * @return whether first app startup or not
     * @throws TopologyInConsistentException
     */
    public boolean startMinimumDependencies(Group group, List<String> parentInstanceIds)
            throws TopologyInConsistentException {
        boolean initialStartup = false;
        int min = group.getGroupMinInstances();
        if (group.getInstanceContextCount() >= min) {
            startDependency(group);
        } else {
            if (group.getInstanceContextCount() > 0) {
                List<String> instanceIds = new ArrayList<String>();
                for (String parentInstanceId : parentInstanceIds) {
                    List<Instance> contexts1 = group.getInstanceContextsWithParentId(parentInstanceId);
                    //Finding the non startable instance ids
                    if (group.getInstanceContexts(parentInstanceId) == null || contexts1.isEmpty() ||
                            contexts1.size() == 0) {
                        instanceIds.add(parentInstanceId);
                    }
                }
                if (instanceIds.size() > 0) {
                    createInstanceAndStartDependency(group, instanceIds);
                } else {
                    startDependency(group);
                }
            } else {
                //No available instances in the Applications. Need to start them all
                createInstanceAndStartDependency(group, parentInstanceIds);
                initialStartup = true;
            }
        }
        return initialStartup;
    }

    /**
     * This will create the required instance and start the dependency
     * This method will be called when a new Application is deployed
     *
     * @param group             blue print of the instance to be started
     * @param parentInstanceIds parent instanceIds used to start the child instance
     * @throws TopologyInConsistentException
     */

    public void createInstanceAndStartDependency(Group group, List<String> parentInstanceIds)
            throws TopologyInConsistentException {
        List<String> instanceIdstoStart = new ArrayList<String>();
        String deploymentPolicyName = group.getDeploymentPolicy();

        String instanceId;
         
        for (String parentInstanceId : parentInstanceIds) {
            Application application = ApplicationHolder.getApplications().getApplication(this.appId);
        
            // Get parent instance context
            Instance parentInstanceContext;
            if (this.id.equals(appId)) {
                parentInstanceContext = application.getInstanceContexts(parentInstanceId);
            } else {
                Group parentGroup = application.getGroupRecursively(this.parent.getId());
                parentInstanceContext = parentGroup.getInstanceContexts(parentInstanceId);
            }
            
            // Get existing or create new GroupLevelNetwokPartitionContext
            GroupLevelNetworkPartitionContext groupLevelNetworkPartitionContext;
            if (this.networkPartitionCtxts.containsKey(parentInstanceContext)) {
                groupLevelNetworkPartitionContext = this.networkPartitionCtxts.
                        get(parentInstanceContext.getNetworkPartitionId());
            } else {
                groupLevelNetworkPartitionContext = new GroupLevelNetworkPartitionContext(
                        parentInstanceContext.getNetworkPartitionId(),
                        null, null);
                this.addNetworkPartitionContext(groupLevelNetworkPartitionContext);
            }
            
            // Determine partitionContext
            PartitionContext partitionContext = null;
            String networkPartitionId = parentInstanceContext.getNetworkPartitionId();
            List<PartitionContext> childParitionContexts = new ArrayList<PartitionContext>();
            String partitionId = null;
            
            if (deploymentPolicyName != null) {
                DeploymentPolicy deploymentPolicy = PolicyManager.getInstance()
                        .getDeploymentPolicy(deploymentPolicyName);
                ChildLevelNetworkPartition networkPartition = deploymentPolicy.
                        getChildLevelNetworkPartition(parentInstanceContext.getNetworkPartitionId());

                // Create childPartitionContexts for all possibilities
                ChildLevelPartition[] childLevelPartitions = networkPartition.getChildLevelPartitions();
                for (ChildLevelPartition childLevelPartition : childLevelPartitions) {
                	partitionId = childLevelPartition.getPartitionId();
                	partitionContext = new GroupLevelPartitionContext(null, childLevelPartition, childLevelPartition.getMax()); 
                	childParitionContexts.add(partitionContext);
                }
                
                // Get partitionContext to create instance in
                AutoscaleAlgorithm algorithm = this.getAutoscaleAlgorithm(networkPartition.getPartitionAlgo());
                partitionContext = algorithm.getNextScaleUpPartitionContext((PartitionContext[]) childParitionContexts.toArray());
                this.addPartitionContext((GroupLevelPartitionContext) partitionContext);
                partitionId = partitionContext.getPartitionId();
            }

            // Create GroupInstanceContext for partition instance and add to required contexts
            instanceId = createGroupInstance(group, parentInstanceId, partitionId, networkPartitionId);
            GroupInstanceContext groupInstanceContext = new GroupInstanceContext(instanceId);
            groupInstanceContext.addPartitionContext((GroupLevelPartitionContext)partitionContext);
            groupLevelNetworkPartitionContext.addInstanceContext(groupInstanceContext);
            
            instanceIdstoStart.add(instanceId);
        }
        startDependency(group, instanceIdstoStart);
    }

    /**
     * This will start the group instance based on the given parent instanceId
     *
     * @param group
     * @param parentInstanceId
     * @throws org.apache.stratos.autoscaler.exception.application.MonitorNotFoundException
     */
    public void createInstanceAndStartDependency(Group group, String parentInstanceId)
            throws MonitorNotFoundException {
        String deploymentPolicyName = group.getDeploymentPolicy();

        String instanceId;
        Application application = ApplicationHolder.getApplications().getApplication(this.appId);
        Instance parentInstanceContext;
        if (this.id.equals(appId)) {
            parentInstanceContext = application.getInstanceContexts(parentInstanceId);
        } else {
            Group group1 = application.getGroupRecursively(this.parent.getId());
            parentInstanceContext = group1.getInstanceContexts(parentInstanceId);
        }

        GroupLevelNetworkPartitionContext groupLevelNetworkPartitionContext;
        if (this.networkPartitionCtxts.containsKey(parentInstanceContext)) {
            groupLevelNetworkPartitionContext = this.networkPartitionCtxts.
                    get(parentInstanceContext.getNetworkPartitionId());
        } else {
            groupLevelNetworkPartitionContext = new GroupLevelNetworkPartitionContext(
                    parentInstanceContext.getNetworkPartitionId(),
                    null, null);
            this.addNetworkPartitionContext(groupLevelNetworkPartitionContext);
        }
        String partitionId = null;
        String networkPartitionId = parentInstanceContext.getNetworkPartitionId();

        ChildPolicy policy = PolicyManager.getInstance().
                getDeploymentPolicyByApplication(group.getApplicationId()).
                getChildPolicy(group.getUniqueIdentifier());

        ChildLevelNetworkPartition networkPartition = policy.
                getChildLevelNetworkPartition(parentInstanceContext.getNetworkPartitionId());

        if(policy != null) {
            AutoscaleAlgorithm algorithm = this.getAutoscaleAlgorithm(networkPartition.getPartitionAlgo());
            /*Partition partition = algorithm.getNextScaleUpPartitionContext(networkPartition.getChildLevelPartitions());
            //TODO need to find the partition. partitionId=? */
        }


        instanceId = createGroupInstance(group, parentInstanceId, partitionId, networkPartitionId);
        startDependency(group, instanceId);
    }

    /**
     * This will create the group instance in the applications Topology
     *
     * @param group
     * @param parentInstanceId
     * @param partitionId
     * @param networkPartitionId
     * @return
     */
    private String createGroupInstance(Group group, String parentInstanceId, String partitionId,
                                       String networkPartitionId) {
        String instanceId = parentInstanceId;
        int minGroupInstances = group.getGroupMinInstances();
        int maxGroupInstances = group.getGroupMaxInstances();
        /*
        * When min != 1 or max != 1, we need to generate
        * instance ids as it is having more than one group instances
        */
        if (minGroupInstances > 1 || maxGroupInstances > 1) {
            instanceId = this.generateInstanceId(group);
        }
        ApplicationBuilder.handleGroupInstanceCreatedEvent(appId, group.getUniqueIdentifier(),
                instanceId, parentInstanceId,
                networkPartitionId, partitionId);
        return instanceId;
    }

    public Map<String, GroupLevelNetworkPartitionContext> getNetworkPartitionCtxts() {
        return networkPartitionCtxts;
    }

    public void setNetworkPartitionCtxts(Map<String, GroupLevelNetworkPartitionContext> networkPartitionCtxts) {
        this.networkPartitionCtxts = networkPartitionCtxts;
    }

    public void addNetworkPartitionContext(GroupLevelNetworkPartitionContext clusterLevelNetworkPartitionContext) {
        this.networkPartitionCtxts.put(clusterLevelNetworkPartitionContext.getId(), clusterLevelNetworkPartitionContext);
    }

    public void setDestroyed(boolean isDestroyed) {
        this.isDestroyed = isDestroyed;
    }

    public GroupInstance getGroupInstance(String instanceId) {
        return this.groupInstanceIdMap.get(instanceId);
    }
    
    public List<GroupLevelPartitionContext> getPartitionCtxts() {

        return partitionContexts;
    }

    public GroupLevelPartitionContext getPartitionCtxt(String partitionId) {


        for(GroupLevelPartitionContext partitionContext : partitionContexts){
            if(partitionContext.getPartitionId().equals(partitionId)){
                return partitionContext;
            }
        }
        return null;
    }

    public void addPartitionContext(GroupLevelPartitionContext partitionContext) {
    	partitionContexts.add(partitionContext);
    }

    public int getNonTerminatedMemberCountOfPartition(String partitionId) {

        for(GroupLevelPartitionContext partitionContext : partitionContexts){
            if(partitionContext.getPartitionId().equals(partitionId)){
                return partitionContext.getNonTerminatedInstanceCount();
            }
        }
        return 0;
    }

    public int getActiveMemberCount(String currentPartitionId) {

        for(GroupLevelPartitionContext partitionContext : partitionContexts){
            if(partitionContext.getPartitionId().equals(currentPartitionId)){
                return partitionContext.getActiveInstanceCount();
            }
        }
        return 0;
    }
}
