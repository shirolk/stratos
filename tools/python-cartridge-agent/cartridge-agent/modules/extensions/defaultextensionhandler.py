import logging

from ..artifactmgt.git.agentgithandler import AgentGitHandler
from ..artifactmgt.repositoryinformation import RepositoryInformation
from ..config.cartridgeagentconfiguration import CartridgeAgentConfiguration
from ..util import extensionutils, cartridgeagentconstants, cartridgeagentutils
from ..publisher import cartridgeagentpublisher
from ..exception.parameternotfoundexception import ParameterNotFoundException
from ..topology.topologycontext import *


class DefaultExtensionHandler:
    log = None

    def __init__(self):
        logging.basicConfig(level=logging.DEBUG)
        self.log = logging.getLogger(__name__)
        self.wk_members = []

    def on_instance_started_event(self):
        try:
            self.log.debug("Processing instance started event...")
            if CartridgeAgentConfiguration.is_multitenant:
                artifact_source = "%r/repository/deployment/server/" % CartridgeAgentConfiguration.app_path
                artifact_dest = cartridgeagentconstants.SUPERTENANT_TEMP_PATH
                extensionutils.execute_copy_artifact_extension(artifact_source, artifact_dest)

            env_params = {}
            extensionutils.execute_instance_started_extension(env_params)
        except:
            self.log.exception("Error processing instance started event")

    def on_instance_activated_event(self):
        extensionutils.execute_instance_activated_extension()

    def on_artifact_updated_event(self, artifacts_updated_event):
        self.log.info("Artifact update event received: [tenant] %r [cluster] %r [status] %r" %
                      (artifacts_updated_event.tenant_id, artifacts_updated_event.cluster_id,
                       artifacts_updated_event.status))

        cluster_id_event = str(artifacts_updated_event.cluster_id).strip()
        cluster_id_payload = CartridgeAgentConfiguration.cluster_id
        repo_url = str(artifacts_updated_event.repo_url).strip()

        if (repo_url != "") and (cluster_id_payload is not None) and (cluster_id_payload == cluster_id_event):
            local_repo_path = CartridgeAgentConfiguration.app_path

            secret = CartridgeAgentConfiguration.cartridge_key
            repo_password = cartridgeagentutils.decrypt_password(artifacts_updated_event.repo_password, secret)

            repo_username = artifacts_updated_event.repo_username
            tenant_id = artifacts_updated_event.tenant_id
            is_multitenant = CartridgeAgentConfiguration.is_multitenant()
            commit_enabled = artifacts_updated_event.commit_enabled

            self.log.info("Executing git checkout")

            # create repo object
            repo_info = RepositoryInformation(repo_url, repo_username, repo_password, local_repo_path, tenant_id,
                                              is_multitenant, commit_enabled)

            # checkout code
            checkout_result = AgentGitHandler.checkout(repo_info)
            # repo_context = checkout_result["repo_context"]
            # execute artifact updated extension
            env_params = {"STRATOS_ARTIFACT_UPDATED_CLUSTER_ID": artifacts_updated_event.cluster_id,
                          "STRATOS_ARTIFACT_UPDATED_TENANT_ID": artifacts_updated_event.tenant_id,
                          "STRATOS_ARTIFACT_UPDATED_REPO_URL": artifacts_updated_event.repo_url,
                          "STRATOS_ARTIFACT_UPDATED_REPO_PASSWORD": artifacts_updated_event.repo_password,
                          "STRATOS_ARTIFACT_UPDATED_REPO_USERNAME": artifacts_updated_event.repo_username,
                          "STRATOS_ARTIFACT_UPDATED_STATUS": artifacts_updated_event.status}

            extensionutils.execute_artifacts_updated_extension(env_params)

            if checkout_result["subscribe_run"]:
                # publish instanceActivated
                cartridgeagentpublisher.publish_instance_activated_event()

            update_artifacts = CartridgeAgentConfiguration.read_property(cartridgeagentconstants.ENABLE_ARTIFACT_UPDATE)
            update_artifacts = True if str(update_artifacts).strip().lower() == "true" else False
            if update_artifacts:
                auto_commit = CartridgeAgentConfiguration.is_commits_enabled()
                auto_checkout = CartridgeAgentConfiguration.is_checkout_enabled()

                try:
                    update_interval = len(
                        CartridgeAgentConfiguration.read_property(cartridgeagentconstants.ARTIFACT_UPDATE_INTERVAL))
                except ParameterNotFoundException:
                    self.log.exception("Invalid artifact sync interval specified ")
                    update_interval = 10
                except ValueError:
                    self.log.exception("Invalid artifact sync interval specified ")
                    update_interval = 10

                self.log.info("Artifact updating task enabled, update interval: %r seconds" % update_interval)

                self.log.info("Auto Commit is turned %r " % "on" if auto_commit else "off")
                self.log.info("Auto Checkout is turned %r " % "on" if auto_checkout else "off")

                AgentGitHandler.schedule_artifact_update_scheduled_task(repo_info, auto_checkout, auto_commit,
                                                                        update_interval)

    def on_artifact_update_scheduler_event(self, tenant_id):
        env_params = {}
        env_params["STRATOS_ARTIFACT_UPDATED_TENANT_ID"] = tenant_id
        env_params["STRATOS_ARTIFACT_UPDATED_SCHEDULER"] = True
        extensionutils.execute_artifacts_updated_extension(env_params)

    def on_instance_cleanup_cluster_event(self, instanceCleanupClusterEvent):
        self.cleanup()

    def on_instance_cleanup_member_event(self, instanceCleanupMemberEvent):
        self.cleanup()

    def on_member_activated_event(self, member_activated_event):
        self.log.info("Member activated event received: [service] %r [cluster] %r [member] %r"
                      % (
            member_activated_event.service_name, member_activated_event.cluster_id, member_activated_event.member_id))

        consistant = extensionutils.check_topology_consistency(member_activated_event.service_name,
                                                               member_activated_event.cluster_id,
                                                               member_activated_event.member_id)
        if not consistant:
            self.log.error("Topology is inconsistent...failed to execute member activated event")
            return

        topology = TopologyContext.get_topology()
        service = topology.get_service(member_activated_event.service_name)
        cluster = service.get_cluster(member_activated_event.cluster_id)
        member = cluster.get_member(member_activated_event.member_id)
        lb_cluster_id = member.lb_cluster_id

        if extensionutils.is_relevant_member_event(member_activated_event.service_name,
                                                   member_activated_event.cluster_id, lb_cluster_id):
            env_params = {}
            env_params["STRATOS_MEMBER_ACTIVATED_MEMBER_IP"] = member_activated_event.member_ip
            env_params["STRATOS_MEMBER_ACTIVATED_MEMBER_ID"] = member_activated_event.member_id
            env_params["STRATOS_MEMBER_ACTIVATED_CLUSTER_ID"] = member_activated_event.cluster_id
            env_params["STRATOS_MEMBER_ACTIVATED_LB_CLUSTER_ID"] = lb_cluster_id
            env_params["STRATOS_MEMBER_ACTIVATED_NETWORK_PARTITION_ID"] = member_activated_event.network_partition_id
            env_params["STRATOS_MEMBER_ACTIVATED_SERVICE_NAME"] = member_activated_event.service_name

            ports = member_activated_event.port_map.values()
            ports_str = ""
            for port in ports:
                ports_str += port.protocol + "," + port.value + "," + port.proxy + "|"

            env_params["STRATOS_MEMBER_ACTIVATED_PORTS"] = ports_str

            members = cluster.get_members()
            member_list_json = ""
            for member in members:
                member_list_json += member.json_str + ","
            env_params["STRATOS_MEMBER_ACTIVATED_MEMBER_LIST_JSON"] = member_list_json[:-1]  # removing last comma

            member_ips = extensionutils.get_lb_member_ip(lb_cluster_id)
            if member_ips is not None and len(member_ips) > 1:
                env_params["STRATOS_MEMBER_ACTIVATED_LB_IP"] = member_ips[0]
                env_params["STRATOS_MEMBER_ACTIVATED_LB_PUBLIC_IP"] = member_ips[1]

            env_params["STRATOS_TOPOLOGY_JSON"] = topology.json_str

            extensionutils.add_properties(service.properties, env_params, "MEMBER_ACTIVATED_SERVICE_PROPERTY")
            extensionutils.add_properties(cluster.properties, env_params, "MEMBER_ACTIVATED_CLUSTER_PROPERTY")
            extensionutils.add_properties(member.properties, env_params, "MEMBER_ACTIVATED_MEMBER_PROPERTY")

            clustered = CartridgeAgentConfiguration.is_clustered

            if member.properties is not None and member.properties[
                cartridgeagentconstants.CLUSTERING_PRIMARY_KEY] == "true" and clustered is not None and clustered:
                self.log.debug(" If WK member is re-spawned, update axis2.xml ")

                has_wk_ip_changed = True
                for wk_member in self.wk_members:
                    if wk_member.member_ip == member_activated_event.member_ip:
                        has_wk_ip_changed = False

                self.log.debug(" hasWKIpChanged %r" + has_wk_ip_changed)

                min_count = int(CartridgeAgentConfiguration.min_count)
                is_wk_member_grp_ready = self.is_wk_member_group_ready(env_params, min_count)
                self.log.debug("MinCount: %r" % min_count)
                self.log.debug("is_wk_member_grp_ready : %r" % is_wk_member_grp_ready)

                if has_wk_ip_changed and is_wk_member_grp_ready:
                    self.log.debug("Setting env var STRATOS_UPDATE_WK_IP to true")
                    env_params["STRATOS_UPDATE_WK_IP"] = "true"

            self.log.debug("Setting env var STRATOS_CLUSTERING to %r" % clustered)
            env_params["STRATOS_CLUSTERING"] = clustered
            env_params["STRATOS_WK_MEMBER_COUNT"] = CartridgeAgentConfiguration.min_count

            extensionutils.execute_member_activated_extension(env_params)
        else:
            self.log.debug("Member activated event is not relevant...skipping agent extension")

    def onCompleteTopologyEvent(self, completeTopologyEvent):
        pass

    def onCompleteTenantEvent(self, completeTenantEvent):
        pass

    def onMemberTerminatedEvent(self, memberTerminatedEvent):
        pass

    def onMemberSuspendedEvent(self, memberSuspendedEvent):
        pass

    def onMemberStartedEvent(self, memberStartedEvent):
        pass

    def start_server_extension(self):
        raise NotImplementedError
        # extensionutils.wait_for_complete_topology()
        # self.log.info("[start server extension] complete topology event received")
        #
        # service_name_in_payload = CartridgeAgentConfiguration.service_name()
        # cluster_id_in_payload = CartridgeAgentConfiguration.cluster_id()
        # member_id_in_payload = CartridgeAgentConfiguration.member_id()
        #
        # try:
        # consistant = extensionutils.check_topology_consistency(service_name_in_payload, cluster_id_in_payload, member_id_in_payload)
        #
        # if not consistant:
        # self.log.error("Topology is inconsistent...failed to execute start server event")
        # return
        #
        #
        # except:
        # self.log.exception("Error processing start servers event")
        # finally:
        #     pass

    def volume_mount_extension(self, persistence_mappings_payload):
        extensionutils.execute_volume_mount_extension(persistence_mappings_payload)

    def onSubscriptionDomainAddedEvent(self, subscriptionDomainAddedEvent):
        pass

    def onSubscriptionDomainRemovedEvent(self, subscriptionDomainRemovedEvent):
        pass

    def onCopyArtifactsExtension(self, src, des):
        pass

    def onTenantSubscribedEvent(self, tenantSubscribedEvent):
        pass

    def onTenantUnSubscribedEvent(self, tenantUnSubscribedEvent):
        pass

    def cleanup(self):
        self.log.info("Executing cleaning up the data in the cartridge instance...")

        cartridgeagentpublisher.publish_maintenance_mode_event()

        extensionutils.execute_cleanup_extension()
        self.log.info("cleaning up finished in the cartridge instance...")

        self.log.info("publishing ready to shutdown event...")
        cartridgeagentpublisher.publish_instance_ready_to_shutdown_event()

    def is_wk_member_group_ready(self, env_params, min_count):
        topology = TopologyContext.get_topology()
        if topology is None or not topology.initialized:
            return False

        service_group_in_payload = CartridgeAgentConfiguration.service_group
        if service_group_in_payload is not None:
            env_params["STRATOS_SERVICE_GROUP"] = service_group_in_payload

        # clustering logic for apimanager
        if service_group_in_payload is not None and service_group_in_payload == "apim":
            # handle apistore and publisher case
            if CartridgeAgentConfiguration.service_name == "apistore" or \
                    CartridgeAgentConfiguration.service_name == "publisher":

                apistore_cluster_collection = topology.get_service("apistore").get_clusters()
                publisher_cluster_collection = topology.get_service("publisher").get_clusters()

                apistore_member_list = []
                for member in apistore_cluster_collection[0].get_members():
                    if member.status == MemberStatus.Starting or member.status == MemberStatus.Activated:
                        apistore_member_list.append(member)
                        self.wk_members.append(member)

                if len(apistore_member_list) == 0:
                    self.log.debug("API Store members not yet created")
                    return False

                apistore_member = apistore_member_list[0]
                env_params["STRATOS_WK_APISTORE_MEMBER_IP"] = apistore_member.member_ip
                self.log.debug("STRATOS_WK_APISTORE_MEMBER_IP: %r" % apistore_member.member_ip)

                publisher_member_list = []
                for member in publisher_cluster_collection[0].get_members():
                    if member.status == MemberStatus.Starting or member.status == MemberStatus.Activated:
                        publisher_member_list.append(member)
                        self.wk_members.append(member)

                if len(publisher_member_list) == 0:
                    self.log.debug("API Publisher members not yet created")

                publisher_member = publisher_member_list[0]
                env_params["STRATOS_WK_PUBLISHER_MEMBER_IP"] = publisher_member.member_ip
                self.log.debug("STRATOS_WK_PUBLISHER_MEMBER_IP: %r" % publisher_member.member_ip)

                return True

            elif CartridgeAgentConfiguration.service_name == "gatewaymgt" or \
                    CartridgeAgentConfiguration.service_name == "gateway":

                if CartridgeAgentConfiguration.deployment is not None:
                    # check if deployment is Manager Worker separated
                    if CartridgeAgentConfiguration.deployment.lower() == cartridgeagentconstants.DEPLOYMENT_MANAGER.lower() or \
                            CartridgeAgentConfiguration.deployment.lower() == cartridgeagentconstants.DEPLOYMENT_WORKER.lower():

                        self.log.info("Deployment pattern for the node: %r" % CartridgeAgentConfiguration.deployment)
                        env_params["DEPLOYMENT"] = CartridgeAgentConfiguration.deployment
                        # check if WKA members of Manager Worker separated deployment is ready
                        return self.is_manager_worker_WKA_group_ready(env_params)

            elif CartridgeAgentConfiguration.service_name == "keymanager":
                return True

        else:
            if CartridgeAgentConfiguration.deployment is not None:
                # check if deployment is Manager Worker separated
                if CartridgeAgentConfiguration.deployment.lower() == cartridgeagentconstants.DEPLOYMENT_MANAGER.lower() or \
                        CartridgeAgentConfiguration.deployment.lower() == cartridgeagentconstants.DEPLOYMENT_WORKER.lower():

                    self.log.info("Deployment pattern for the node: %r" % CartridgeAgentConfiguration.deployment)
                    env_params["DEPLOYMENT"] = CartridgeAgentConfiguration.deployment
                    # check if WKA members of Manager Worker separated deployment is ready
                    return self.is_manager_worker_WKA_group_ready(env_params)

            service_name_in_payload = CartridgeAgentConfiguration.service_name
            cluster_id_in_payload = CartridgeAgentConfiguration.cluster_id
            service = topology.get_service(service_name_in_payload)
            cluster = service.get_cluster(cluster_id_in_payload)

            wk_members = []
            for member in cluster.get_members():
                if member.properties is not None and \
                        "PRIMARY" in member.properties and member.properties["PRIMARY"].lower() == "true" and \
                        (member.status == MemberStatus.Starting or member.status == MemberStatus.Activated):

                    wk_members.append(member)
                    self.wk_members.append(member)
                    self.log.debug("Found WKA: STRATOS_WK_MEMBER_IP: " + member.member_ip)

            if len(wk_members) >= min_count:
                idx = 0
                for member in wk_members:
                    env_params["STRATOS_WK_MEMBER_" + idx + "_IP"] = member.member_ip
                    self.log.debug("STRATOS_WK_MEMBER_" + idx + "_IP:" + member.member_ip)

                    idx += 1

                return True

        return False

    # generic worker manager separated clustering logic
    def is_manager_worker_WKA_group_ready(self, env_params):

        # for this, we need both manager cluster service name and worker cluster service name
        manager_service_name = CartridgeAgentConfiguration.manager_service_name
        worker_service_name = CartridgeAgentConfiguration.worker_service_name

        # managerServiceName and workerServiceName both should not be null /empty
        if manager_service_name is None or manager_service_name.strip() == "":
            self.log.error("Manager service name [ " + manager_service_name + " ] is invalid")
            return False

        if worker_service_name is None or worker_service_name.strip() == "":
            self.log.error("Worker service name [ " + worker_service_name + " ] is invalid")
            return False

        min_manager_instances_available = False
        min_worker_instances_available = False

        topology = TopologyContext.get_topology()
        manager_service = topology.get_service(manager_service_name)
        worker_service = topology.get_service(worker_service_name)

        if manager_service is None:
            self.log.warn("Service [ " + manager_service_name + " ] is not found")
            return False

        if worker_service is None:
            self.log.warn("Service [ " + worker_service_name + " ] is not found")
            return False

        # manager clusters
        manager_clusters = manager_service.get_clusters()
        if manager_clusters is None or len(manager_clusters) == 0:
            self.log.warn("No clusters found for service [ " + manager_service_name + " ]")
            return False

        manager_min_instance_count = 1
        manager_min_instance_count_found = False

        manager_wka_members = []
        for member in manager_clusters[0].get_members():
            if member.properties is not None and \
                    "PRIMARY" in member.properties and member.properties["PRIMARY"].lower() == "true" and \
                    (member.status == MemberStatus.Starting or member.status == MemberStatus.Activated):

                manager_wka_members.append(member)
                self.wk_members.append(member)

                # get the min instance count
                if not manager_min_instance_count_found:
                    manager_min_instance_count = self.get_min_instance_count_from_member(member)
                    manager_min_instance_count_found = True
                    self.log.info("Manager min instance count: " + manager_min_instance_count)

        if len(manager_wka_members) >= manager_min_instance_count:
            min_manager_instances_available = True
            idx = 0
            for member in manager_wka_members:
                env_params["STRATOS_WK_MANAGER_MEMBER_" + idx + "_IP"] = member.member_ip
                self.log.debug("STRATOS_WK_MANAGER_MEMBER_" + idx + "_IP: " + member.member_ip)
                idx += 1

            env_params["STRATOS_WK_MANAGER_MEMBER_COUNT"] = int(manager_min_instance_count)

        # If all the manager members are non primary and is greate than or equal to mincount,
        # minManagerInstancesAvailable should be true
        all_managers_non_primary = True
        for member in manager_clusters[0].get_members():
            # get the min instance count
            if not manager_min_instance_count_found:
                manager_min_instance_count = self.get_min_instance_count_from_member(member)
                manager_min_instance_count_found = True
                self.log.info(
                    "Manager min instance count when allManagersNonPrimary true : " + manager_min_instance_count)

            if member.properties is not None and "PRIMARY" in member.properties and \
                            member.properties["PRIMARY"].lower() == "true":
                all_managers_non_primary = False
                break

        self.log.debug(
            " allManagerNonPrimary & managerMinInstanceCount [" + all_managers_non_primary +
            "], [" + manager_min_instance_count + "] ")

        if all_managers_non_primary and len(manager_clusters) >= manager_min_instance_count:
            min_manager_instances_available = True

        # worker cluster
        worker_clusters = worker_service.get_clusters()
        if worker_clusters is None or len(worker_clusters) == 0:
            self.log.warn("No clusters found for service [ " + worker_service_name + " ]")
            return False

        worker_min_instance_count = 1
        worker_min_instance_count_found = False

        worker_wka_members = []
        for member in worker_clusters[0].get_members():
            self.log.debug("Checking member : " + member.member_id)

            if member.properties is not None and "PRIMARY" in member.properties and \
                    member.properties["PRIMARY"].lower() == "true" and \
                    (member.status == MemberStatus.Starting or member.status == MemberStatus.Activated):

                self.log.debug("Added worker member " + member.member_id)

                worker_wka_members.append(member)
                self.wk_members.append(member)

                # get the min instance count
                if not worker_min_instance_count_found:
                    worker_min_instance_count = self.get_min_instance_count_from_member(member)
                    worker_min_instance_count_found = True
                    self.log.debug("Worker min instance count: " + worker_min_instance_count)

        if len(worker_wka_members) >= worker_min_instance_count:
            min_worker_instances_available = True
            idx = 0
            for member in worker_wka_members:
                env_params["STRATOS_WK_WORKER_MEMBER_" + idx + "_IP"] = member.member_ip
                self.log.debug("STRATOS_WK_WORKER_MEMBER_" + idx + "_IP: " + member.member_ip)
                idx += 1

            env_params["STRATOS_WK_WORKER_MEMBER_COUNT"] = int(worker_min_instance_count)

        self.log.debug(
            " Returnning values minManagerInstancesAvailable && minWorkerInstancesAvailable [" +
            min_manager_instances_available + "],  [" + min_worker_instances_available + "] ")

        return min_manager_instances_available and min_worker_instances_available

    def get_min_instance_count_from_member(self, member):
        if "MIN_COUNT" in member.properties:
            return int(member.properties["MIN_COUNT"])

        return 1