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
package org.apache.stratos.autoscaler.applications.topic;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.autoscaler.context.AutoscalerContext;
import org.apache.stratos.autoscaler.applications.ApplicationHolder;
import org.apache.stratos.autoscaler.applications.pojo.ApplicationClusterContext;
import org.apache.stratos.autoscaler.client.CloudControllerClient;
import org.apache.stratos.autoscaler.event.publisher.ClusterStatusEventPublisher;
import org.apache.stratos.autoscaler.monitor.component.ApplicationMonitor;
import org.apache.stratos.autoscaler.monitor.component.GroupMonitor;
import org.apache.stratos.autoscaler.pojo.policy.PolicyManager;
import org.apache.stratos.autoscaler.pojo.policy.deployment.*;
import org.apache.stratos.messaging.domain.applications.*;
import org.apache.stratos.messaging.domain.instance.ApplicationInstance;
import org.apache.stratos.messaging.domain.instance.ClusterInstance;
import org.apache.stratos.messaging.domain.instance.GroupInstance;
import org.apache.stratos.messaging.domain.topology.Cluster;
import org.apache.stratos.messaging.domain.topology.Service;
import org.apache.stratos.messaging.message.receiver.topology.TopologyManager;

import java.util.Collection;
import java.util.Set;

/**
 * This will build the application.
 */
public class ApplicationBuilder {
    private static final Log log = LogFactory.getLog(ApplicationBuilder.class);

    public static synchronized void handleCompleteApplication(Applications applications) {
        if (log.isDebugEnabled()) {
            log.debug("Handling complete application event");
        }

        ApplicationHolder.acquireReadLock();
        try {
            ApplicationsEventPublisher.sendCompleteApplicationsEvent(applications);
        } finally {
            ApplicationHolder.releaseReadLock();
        }
    }

    public static synchronized void handleApplicationCreated(Application application,
                                                             Set<ApplicationClusterContext> appClusterContexts) {
        if (log.isDebugEnabled()) {
            log.debug("Handling application creation event: [application-id] " +
                    application.getUniqueIdentifier());
        }

        ApplicationHolder.acquireWriteLock();
        try {
            Applications applications = ApplicationHolder.getApplications();
            if (applications.getApplication(application.getUniqueIdentifier()) == null) {
                CloudControllerClient.getInstance().createApplicationClusters(application.getUniqueIdentifier(),
                        appClusterContexts);
                ApplicationHolder.persistApplication(application);
            } else {
                log.warn("Application already exists: [application-id] " + application.getUniqueIdentifier());
            }
        } finally {
            ApplicationHolder.releaseWriteLock();
        }
        ApplicationsEventPublisher.sendApplicationCreatedEvent(application);
    }

    public static void handleApplicationInstanceCreatedEvent(String appId, String instanceId,
                                                             String networkPartitionId) {
        if (log.isDebugEnabled()) {
            log.debug("Handling application activation event: [application-id] " + appId);
        }

        Applications applications = ApplicationHolder.getApplications();
        Application application = applications.getApplication(appId);
        //update the status of the Group
        if (application == null) {
            log.warn(String.format("Application does not exist: [application-id] %s",
                    appId));
            return;
        }

        ApplicationStatus status = ApplicationStatus.Created;


        if (!application.containsInstanceContext(instanceId)) {
            //setting the status, persist and publish
            ApplicationInstance context = new ApplicationInstance(appId, instanceId);
            context.setStatus(status);
            context.setNetworkPartitionId(networkPartitionId);
            application.addInstanceContext(instanceId, context);
            //updateApplicationMonitor(appId, status);
            ApplicationHolder.persistApplication(application);
            //ApplicationsEventPublisher.sendApplicationActivatedEvent(appId);
        } else {
            log.warn(String.format("Application Instance Context already exists" +
                    " [appId] %s [ApplicationInstanceId] %s", appId, instanceId));
        }
    }

    public static void handleApplicationActivatedEvent(String appId, String instanceId) {
        if (log.isDebugEnabled()) {
            log.debug("Handling application activation event: [application-id] " + appId);
        }

        Applications applications = ApplicationHolder.getApplications();
        Application application = applications.getApplication(appId);
        //update the status of the Group
        if (application == null) {
            log.warn(String.format("Application does not exist: [application-id] %s",
                    appId));
            return;
        }

        ApplicationStatus status = ApplicationStatus.Active;
        ApplicationInstance context = application.getInstanceContexts(instanceId);
        if (context.isStateTransitionValid(status)) {
            //setting the status, persist and publish
            application.setStatus(status, instanceId);
            updateApplicationMonitor(appId, status, instanceId);
            ApplicationHolder.persistApplication(application);
            ApplicationsEventPublisher.sendApplicationActivatedEvent(appId, instanceId);
        } else {
            log.warn(String.format("Application state transition is not valid: [application-id] %s " +
                    " [instance-id] %s [current-status] %s [status-requested] %s",
                    appId, instanceId, context.getStatus(), status));
        }
    }

    public static void handleApplicationUndeployed(String appId) {
        if (log.isDebugEnabled()) {
            log.debug("Handling application unDeployment for [application-id] " + appId);
        }
        Set<ClusterDataHolder> clusterData;
        ApplicationHolder.acquireWriteLock();
        try {
            Applications applications = ApplicationHolder.getApplications();
            Application application = applications.getApplication(appId);
            //update the status of the Group
            if (application == null) {
                log.warn(String.format("Application does not exist: [application-id] %s",
                        appId));
                return;
            } else {
                org.apache.stratos.autoscaler.pojo.policy.deployment.DeploymentPolicy policy =
                        PolicyManager.getInstance().getDeploymentPolicyByApplication(appId);
                if(policy != null) {
                    log.warn(String.format("Application has been found in the ApplicationsTopology" +
                                    ": [application-id] %s, Please unDeploy the Application Policy.",
                            appId));
                }
            }
            ApplicationHolder.removeApplication(appId);

        } finally {
            ApplicationHolder.releaseWriteLock();
        }

        log.info("[Application] " + appId + " has been successfully undeployed");
    }

    public static boolean handleApplicationPolicyUndeployed(String appId) {
        if (log.isDebugEnabled()) {
            log.debug("Handling application terminating event: [application-id] " + appId);
        }
        Set<ClusterDataHolder> clusterData;
        ApplicationHolder.acquireWriteLock();
        try {
            Applications applications = ApplicationHolder.getApplications();
            Application application = applications.getApplication(appId);
            //update the status of the Group
            if (application == null) {
                log.warn(String.format("Application does not exist: [application-id] %s",
                        appId));
                return false;
            }
            clusterData = application.getClusterDataRecursively();
            Collection<ApplicationInstance> context = application.
                    getInstanceIdToInstanceContextMap().values();
            ApplicationStatus status = ApplicationStatus.Terminating;
            for (ApplicationInstance context1 : context) {
                if (context1.isStateTransitionValid(status)) {
                    //setting the status, persist and publish
                    application.setStatus(status, context1.getInstanceId());
                    updateApplicationMonitor(appId, status, context1.getInstanceId());
                    ApplicationHolder.persistApplication(application);
                    ApplicationsEventPublisher.sendApplicationTerminatingEvent(appId, context1.getInstanceId());
                } else {
                    log.warn(String.format("Application Instance state transition is not valid: [application-id] %s " +
                                    " [instance-id] %s [current-status] %s [status-requested] %s", appId,
                            context1.getInstanceId() + context1.getStatus(), status));
                }
            }
        } finally {
            ApplicationHolder.releaseWriteLock();
        }

        // if monitors is not found for any cluster, assume cluster is not there and send cluster terminating event.
        // this assumes the cluster monitor will not fail after creating members, but will only fail before
        for (ClusterDataHolder aClusterData : clusterData) {
            if (AutoscalerContext.getInstance().getClusterMonitor(aClusterData.getClusterId()) == null) {
                TopologyManager.acquireReadLockForCluster(aClusterData.getServiceType(),
                        aClusterData.getClusterId());
                try {
                    Service service = TopologyManager.getTopology().getService(aClusterData.getServiceType());
                    if (service != null) {
                        Cluster cluster = service.getCluster(aClusterData.getClusterId());
                        if (cluster != null) {
                            for(ClusterInstance instance : cluster.getInstanceIdToInstanceContextMap().values()) {
                                ClusterStatusEventPublisher.sendClusterTerminatingEvent(appId,
                                                                    aClusterData.getServiceType(),
                                                                    aClusterData.getClusterId(),
                                                                    instance.getInstanceId());
                            }
                        }
                    }
                } finally {
                    TopologyManager.releaseReadLockForCluster(aClusterData.getServiceType(),
                            aClusterData.getClusterId());
                }

            }
        }
        return true;
    }

    public static void handleApplicationTerminatedEvent(String appId, String instanceId) {
        if (log.isDebugEnabled()) {
            log.debug("Handling application terminated event: [application-id] " + appId);
        }

        Applications applications = ApplicationHolder.getApplications();

        if (!applications.applicationExists(appId)) {
            log.warn("Application does not exist: [application-id] " + appId);
        } else {
            Application application = applications.getApplication(appId);
            Set<ClusterDataHolder> clusterData = application.getClusterDataRecursively();

            ApplicationStatus status = ApplicationStatus.Terminated;
            if (application.isStateTransitionValid(status, instanceId)) {
                //setting the status, persist and publish
                application.setStatus(status, instanceId);
                updateApplicationMonitor(appId, status, instanceId);
                //removing the monitor
                AutoscalerContext.getInstance().removeAppMonitor(appId);
                //Removing the application from memory and registry
                ApplicationHolder.removeApplication(appId);
                log.info("Application is removed: [application-id] " + appId);

                ApplicationsEventPublisher.sendApplicationTerminatedEvent(appId, clusterData);
            } else {
                log.warn(String.format("Application state transition is not valid: [application-id] %s " +
                        " [current-status] %s [status-requested] %s", appId,
                        application.getInstanceContexts(instanceId).getStatus(),
                        status));
            }
        }
    }

    public static void handleGroupTerminatedEvent(String appId, String groupId, String instanceId) {
        if (log.isDebugEnabled()) {
            log.debug("Handling group terminated event: [group-id] " + groupId +
                    " [application-id] " + appId);
        }

        Applications applications = ApplicationHolder.getApplications();
        Application application = applications.getApplication(appId);
        //update the status of the Group
        if (application == null) {
            log.warn(String.format("Application does not exist: [application-id] %s",
                    appId));
            return;
        }

        Group group = application.getGroupRecursively(groupId);
        if (group == null) {
            log.warn(String.format("Group does not exist: [group-id] %s",
                    groupId));
            return;
        }

        GroupInstance context = group.getInstanceContexts(instanceId);
        GroupStatus status = GroupStatus.Terminated;
        if (context != null) {
            if (context.isStateTransitionValid(status)) {
                //setting the status, persist and publish
                updateGroupMonitor(appId, groupId, status, instanceId);
                ApplicationHolder.persistApplication(application);
                ApplicationsEventPublisher.sendGroupTerminatedEvent(appId, groupId, instanceId);
            } else {
                log.warn("Group state transition is not valid: [group-id] " + groupId +
                        " [instance-id] " + instanceId + " [current-state] " + context.getStatus()
                        + "[requested-state] " + status);
            }

        } else {
            log.warn("Group Context is not found for [group-id] " + groupId +
                    " [instance-id] " + instanceId);
        }

    }

    public static void handleGroupActivatedEvent(String appId, String groupId, String instanceId) {
        if (log.isDebugEnabled()) {
            log.debug("Handling group activation for the [group-id]: " + groupId +
                    " in the [application-id] " + appId);
        }

        Applications applications = ApplicationHolder.getApplications();
        Application application = applications.getApplication(appId);
        //update the status of the Group
        if (application == null) {
            log.warn(String.format("Application does not exist: [application-id] %s",
                    appId));
            return;
        }

        Group group = application.getGroupRecursively(groupId);
        if (group == null) {
            log.warn(String.format("Group does not exist: [group-id] %s",
                    groupId));
            return;
        }

        GroupInstance context = group.getInstanceContexts(instanceId);
        GroupStatus status = GroupStatus.Active;
        if (context != null) {
            if (context.isStateTransitionValid(status)) {
                //setting the status, persist and publish
                updateGroupMonitor(appId, groupId, status, instanceId);
                ApplicationHolder.persistApplication(application);
                ApplicationsEventPublisher.sendGroupActivatedEvent(appId, groupId, instanceId);
            } else {
                log.warn("Group state transition is not valid: [group-id] " + groupId +
                        " [instance-id] " + instanceId + " [current-state] " + context.getStatus()
                        + "[requested-state] " + status);
            }

        } else {
            log.warn("Group Context is not found for [group-id] " + groupId +
                    " [instance-id] " + instanceId);
        }
    }

    public static void handleGroupCreatedEvent(String appId, String groupId, String instanceId) {
        if (log.isDebugEnabled()) {
            log.debug("Handling Group creation for the [group]: " + groupId +
                    " in the [application] " + appId);
        }

        Applications applications = ApplicationHolder.getApplications();
        Application application = applications.getApplication(appId);
        //update the status of the Group
        if (application == null) {
            log.warn(String.format("Application %s does not exist",
                    appId));
            return;
        }

        Group group = application.getGroupRecursively(groupId);
        if (group == null) {
            log.warn(String.format("Group %s does not exist",
                    groupId));
            return;
        }

        GroupStatus status = GroupStatus.Created;
        if (group.isStateTransitionValid(status, null)) {
            //setting the status, persist and publish
            updateGroupMonitor(appId, groupId, status, instanceId);
            ApplicationHolder.persistApplication(application);
            ApplicationsEventPublisher.sendGroupCreatedEvent(appId, groupId, instanceId);
        } else {
            log.warn("Group state transition is not valid: [group-id] " + groupId + " [current-state] " + group.getStatus(null)
                    + "[requested-state] " + status);
        }
    }

    public static GroupInstance handleGroupInstanceCreatedEvent(String appId, String groupId, String instanceId,
                                                       String parentId, String partitionId,
                                                       String networkPartitionId) {
    	GroupInstance groupInstance = null;
    	if (log.isDebugEnabled()) {
            log.debug("Handling Group creation for the [group]: " + groupId +
                    " in the [application] " + appId);
        }

        Applications applications = ApplicationHolder.getApplications();
        Application application = applications.getApplication(appId);
        //update the status of the Group
        if (application == null) {
            log.warn(String.format("Application %s does not exist",
                    appId));
            return groupInstance;
        }

        Group group = application.getGroupRecursively(groupId);
        if (group == null) {
            log.warn(String.format("Group %s does not exist",
                    groupId));
            return groupInstance;
        }

        GroupStatus status = GroupStatus.Created;

        if (!group.containsInstanceContext(instanceId)) {
            //setting the status, persist and publish
            GroupInstance context = new GroupInstance(groupId, instanceId);
            context.setParentId(parentId);
            context.setStatus(status);
            group.addInstanceContext(instanceId, context);
            //updateGroupMonitor(appId, groupId, status);
            ApplicationHolder.persistApplication(application);
            //ApplicationsEventPublisher.sendGroupCreatedEvent(appId, groupId);
        } else {
            log.warn("Group Instance Context already exists: [group-id] " + groupId +
                    " [Group-Instance-Id] " + instanceId);
        }
        
        return groupInstance;
    }


    public static void handleGroupInActivateEvent(String appId, String groupId, String instanceId) {
        if (log.isDebugEnabled()) {
            log.debug("Handling group in-active event: [group]: " + groupId +
                    " [application-id] " + appId);
        }

        Applications applications = ApplicationHolder.getApplications();
        Application application = applications.getApplication(appId);
        //update the status of the Group
        if (application == null) {
            log.warn(String.format("Application does not exist: [application-id] %s",
                    appId));
            return;
        }

        Group group = application.getGroupRecursively(groupId);
        if (group == null) {
            log.warn(String.format("Group does not exist: [group-id] %s",
                    groupId));
            return;
        }

        GroupInstance context = group.getInstanceContexts(instanceId);
        GroupStatus status = GroupStatus.Inactive;
        if (context != null) {
            if (context.isStateTransitionValid(status)) {
                //setting the status, persist and publish
                updateGroupMonitor(appId, groupId, status, instanceId);
                ApplicationHolder.persistApplication(application);
                ApplicationsEventPublisher.sendGroupInActivateEvent(appId, groupId, instanceId);
            } else {
                log.warn("Group state transition is not valid: [group-id] " + groupId +
                        " [instance-id] " + instanceId + " [current-state] " + context.getStatus()
                        + "[requested-state] " + status);
            }

        } else {
            log.warn("Group Context is not found for [group-id] " + groupId +
                    " [instance-id] " + instanceId);
        }
    }

    public static void handleGroupTerminatingEvent(String appId, String groupId, String instanceId) {
        if (log.isDebugEnabled()) {
            log.debug("Handling group terminating: [group-id] " + groupId +
                    " [application-id] " + appId);
        }

        Applications applications = ApplicationHolder.getApplications();
        Application application = applications.getApplication(appId);
        //update the status of the Group
        if (application == null) {
            log.warn(String.format("Application does not exist: [application-id] %s",
                    appId));
            return;
        }

        Group group = application.getGroupRecursively(groupId);
        if (group == null) {
            log.warn(String.format("Group does not exist: [group-id] %s",
                    groupId));
            return;
        }

        try {
            ApplicationHolder.acquireWriteLock();
            GroupInstance context = group.getInstanceContexts(instanceId);
            GroupStatus status = GroupStatus.Terminating;
            if (context != null) {
                if (context.isStateTransitionValid(status)) {
                    //setting the status, persist and publish
                    updateGroupMonitor(appId, groupId, status, instanceId);
                    ApplicationHolder.persistApplication(application);
                    ApplicationsEventPublisher.sendGroupTerminatingEvent(appId, groupId, instanceId);
                } else {
                    log.warn("Group state transition is not valid: [group-id] " + groupId +
                            " [instance-id] " + instanceId + " [current-state] " + context.getStatus()
                            + "[requested-state] " + status);
                }

            } else {
                log.warn("Group Context is not found for [group-id] " + groupId +
                        " [instance-id] " + instanceId);
            }
        } finally {
            ApplicationHolder.releaseWriteLock();
        }
    }

    private static void updateGroupStatusesRecursively(GroupStatus groupStatus, Collection<Group> groups) {

        for (Group group : groups) {
            if (!group.isStateTransitionValid(groupStatus, null)) {
                log.error("Invalid state transfer from " + group.getStatus(null) + " to " + groupStatus);
            }
            // force update for now
            //group.setStatus(groupStatus, null);

            // go recursively and update
            if (group.getGroups() != null) {
                updateGroupStatusesRecursively(groupStatus, group.getGroups());
            }
        }
    }

    private static void updateApplicationMonitor(String appId, ApplicationStatus status, String instanceId) {
        //Updating the Application Monitor
        ApplicationMonitor applicationMonitor = AutoscalerContext.getInstance().getAppMonitor(appId);
        if (applicationMonitor != null) {
            applicationMonitor.setStatus(status, instanceId);
        } else {
            log.warn("Application monitor cannot be found: [application-id] " + appId);
        }

    }

    private static void updateGroupMonitor(String appId, String groupId, GroupStatus status, String instanceId) {
        //Updating the Application Monitor
        ApplicationMonitor applicationMonitor = AutoscalerContext.getInstance().getAppMonitor(appId);
        if (applicationMonitor != null) {
            GroupMonitor monitor = (GroupMonitor) applicationMonitor.findGroupMonitorWithId(groupId);
            if (monitor != null) {
                monitor.setStatus(status, instanceId);
            } else {
                log.warn("Group monitor cannot be found: [group-id] " + groupId +
                        " [application-id] " + appId);
            }
        }
    }
}
