/*
 *
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 *  This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 */

package org.entando.kubernetes.compositeapp.controller;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.Resource;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorComplianceMode;
import org.entando.kubernetes.controller.support.client.impl.DefaultIngressClient;
import org.entando.kubernetes.controller.support.client.impl.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.FluentIntegrationTesting;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.controller.support.common.OperatorProcessingInstruction;
import org.entando.kubernetes.controller.support.creators.IngressCreator;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppBuilder;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;
import org.entando.kubernetes.test.common.FluentTraversals;
import org.entando.kubernetes.test.common.VariableReferenceAssertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@SuppressWarnings("java:S5786")//because it is actually used in subclasses in different packages
public abstract class AbstractCompositeAppControllerTest implements FluentIntegrationTesting, FluentTraversals,
        VariableReferenceAssertions {

    public static final String NAMESPACE = EntandoOperatorTestConfig.calculateNameSpace("e6k8s-capp-test");
    public static final String PLUGIN_NAME = EntandoOperatorTestConfig.calculateName("test-plugin");
    public static final String KEYCLOAK_NAME = EntandoOperatorTestConfig.calculateName("test-keycloak");
    public static final String MY_APP = EntandoOperatorTestConfig.calculateName("my-app");
    private String domainSuffix;

    protected abstract KubernetesClient getKubernetesClient();

    protected abstract EntandoCompositeApp performCreate(EntandoCompositeApp resource);

    protected abstract EntandoPlugin performCreate(EntandoPlugin resource);

    @ParameterizedTest
    @EnumSource(value = EntandoOperatorComplianceMode.class, names = {"COMMUNITY", "REDHAT"})
    public void testExecuteControllerPod(EntandoOperatorComplianceMode mode) throws JsonProcessingException {
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_COMPLIANCE_MODE.getJvmSystemProperty(),
                mode.name().toLowerCase(Locale.ROOT));
        //Given I have a clean namespace
        KubernetesClient client = getKubernetesClient();
        //and the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(),
                client.getNamespace());
        //And I have a config map with the Entando KeycloakController's image information
        final String keycloakControllerVersionToExpect = ensureKeycloakControllerVersion();
        final String pluginControllerVersionToExpect = ensurePluginControllerVersion();
        //When I create a new EntandoCompositeApp with an EntandoKeycloakServer and a ling to an EntandoPlugin
        EntandoPlugin plugin = new EntandoPluginBuilder()
                .withNewMetadata()
                .withName(PLUGIN_NAME)
                .withNamespace(client.getNamespace())
                .endMetadata().withNewSpec()
                .withImage("entando/entando-avatar-plugin")
                .withDbms(DbmsVendor.POSTGRESQL)
                .withReplicas(1)
                .withIngressHostName(PLUGIN_NAME + "." + getDomainSuffix())
                .withHealthCheckPath("/management/health")
                .withIngressPath("/avatarPlugin")
                .withSecurityLevel(PluginSecurityLevel.STRICT)
                .endSpec()
                .build();
        plugin.getMetadata().setAnnotations(new HashMap<>());
        plugin.getMetadata().getAnnotations()
                .put(KubeUtils.PROCESSING_INSTRUCTION_ANNOTATION_NAME,
                        OperatorProcessingInstruction.DEFER.name().toLowerCase(Locale.ROOT));
        performCreate(plugin);
        EntandoCompositeApp appToCreate = new EntandoCompositeAppBuilder()
                .withNewMetadata().withName(MY_APP).withNamespace(client.getNamespace()).endMetadata()
                .withNewSpec()
                .addNewEntandoKeycloakServer()
                .withNewMetadata().withName(KEYCLOAK_NAME)
                .endMetadata()
                .withNewSpec()
                .withDefault(true)
                .withDbms(DbmsVendor.NONE)
                .withIngressHostName(KEYCLOAK_NAME + "." + getDomainSuffix())
                .endSpec()
                .endEntandoKeycloakServer()
                .addNewEntandoCustomResourceReference()
                .withNewMetadata()
                .withName("reference-to-" + PLUGIN_NAME)
                .endMetadata()
                .withNewSpec()
                .withTargetKind("EntandoPlugin")
                .withTargetName(PLUGIN_NAME)
                .endSpec()
                .endEntandoCustomResourceReference()
                .endSpec()
                .build();
        EntandoCompositeApp app = performCreate(appToCreate);
        //Then I expect to see the keycloak controller pod
        FilterWatchListDeletable<Pod, PodList> keycloakControllerList = client.pods()
                .inNamespace(client.getNamespace())
                .withLabel(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, "EntandoKeycloakServer")
                .withLabel("EntandoKeycloakServer", app.getSpec().getComponents().get(0).getMetadata().getName());
        await().ignoreExceptions().atMost(60, TimeUnit.SECONDS).until(() -> keycloakControllerList.list().getItems().size() > 0);
        Pod theKeycloakControllerPod = keycloakControllerList.list().getItems().get(0);
        //and the EntandoKeycloakServer resource has been saved to K8S under the EntandoCompositeApp
        Resource<EntandoKeycloakServer> keycloakGettable = getKubernetesClient().customResources(EntandoKeycloakServer.class)
                .inNamespace(NAMESPACE).withName(KEYCLOAK_NAME);
        await().ignoreExceptions().atMost(15, TimeUnit.SECONDS).until(
                () -> keycloakGettable.get().getMetadata().getOwnerReferences().get(0).getUid().equals(app.getMetadata().getUid())
        );
        //and the EntandoKeycloakServer resource's identifying information has been passed to the controller Pod
        assertThat(theVariableNamed("ENTANDO_RESOURCE_ACTION").on(thePrimaryContainerOn(theKeycloakControllerPod)),
                is(Action.ADDED.name()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAME").on(thePrimaryContainerOn(theKeycloakControllerPod)),
                is(app.getSpec().getComponents().get(0).getMetadata().getName()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAMESPACE").on(thePrimaryContainerOn(theKeycloakControllerPod)),
                is(app.getMetadata().getNamespace()));
        //With the correct controller image specified
        assertThat(thePrimaryContainerOn(theKeycloakControllerPod).getImage(), containsString("entando/entando-k8s-keycloak-controller"));
        //And its status reflecting on the EntandoCompositeApp
        Resource<EntandoCompositeApp> appGettable = client.customResources(EntandoCompositeApp.class)
                .inNamespace(client.getNamespace()).withName(MY_APP);
        await().ignoreExceptions().atMost(180, TimeUnit.SECONDS).until(
                () -> !appGettable.fromServer().get().getStatus().forServerQualifiedBy(KEYCLOAK_NAME).get().getPodPhases().isEmpty()
        );
        //And the plugin controller pod
        FilterWatchListDeletable<Pod, PodList> pluginControllerList = client.pods()
                .inNamespace(client.getNamespace())
                .withLabel(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, "EntandoPlugin")
                .withLabel("EntandoPlugin", PLUGIN_NAME);
        await().ignoreExceptions().atMost(60, TimeUnit.SECONDS).until(() -> pluginControllerList.list().getItems().size() > 0);
        Pod thePluginControllerPod = pluginControllerList.list().getItems().get(0);
        //and the EntandoPlugin resource has NOT been saved to K8S under the EntandoCompositeApp
        Resource<EntandoPlugin> pluginGettable = getKubernetesClient().customResources(EntandoPlugin.class).inNamespace(NAMESPACE)
                .withName(PLUGIN_NAME);
        await().ignoreExceptions().atMost(15, TimeUnit.SECONDS).until(
                () -> pluginGettable.get().getMetadata().getOwnerReferences().isEmpty()
        );
        //and the EntandoPlugin resource's identifying information has been passed to the controller Pod
        assertThat(theVariableNamed("ENTANDO_RESOURCE_ACTION").on(thePrimaryContainerOn(thePluginControllerPod)), is(Action.ADDED.name()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAME").on(thePrimaryContainerOn(thePluginControllerPod)),
                is(PLUGIN_NAME));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAMESPACE").on(thePrimaryContainerOn(thePluginControllerPod)),
                is(app.getMetadata().getNamespace()));
        //With the entando plugin controller image specified
        assertThat(thePrimaryContainerOn(thePluginControllerPod).getImage(), containsString("entando/entando-k8s-plugin-controller"));
        //And its status reflecting on the EntandoCompositeApp
        await().ignoreExceptions().atMost(180, TimeUnit.SECONDS).until(
                () -> !appGettable.fromServer().get().getStatus().forServerQualifiedBy(PLUGIN_NAME).get().getPodPhases().isEmpty()
        );
        //And the EntandoCompositeApp is in a finished state
        await().ignoreExceptions().atMost(30, TimeUnit.SECONDS).until(() -> hasFinished(appGettable));
    }

    private boolean hasFinished(Resource<EntandoCompositeApp> appGettable) {
        EntandoDeploymentPhase phase = appGettable.fromServer().get().getStatus().getPhase();
        return phase == EntandoDeploymentPhase.SUCCESSFUL || phase == EntandoDeploymentPhase.FAILED;
    }

    private String getDomainSuffix() {
        if (domainSuffix == null) {
            domainSuffix = IngressCreator.determineRoutingSuffix(DefaultIngressClient.resolveMasterHostname(this.getKubernetesClient()));
        }
        return domainSuffix;
    }

    protected String ensureKeycloakControllerVersion() throws JsonProcessingException {
        ImageVersionPreparation imageVersionPreparation = new ImageVersionPreparation(getKubernetesClient());
        return imageVersionPreparation.ensureImageVersion("entando-k8s-keycloak-controller", "6.0.0");
    }

    protected String ensurePluginControllerVersion() throws JsonProcessingException {
        ImageVersionPreparation imageVersionPreparation = new ImageVersionPreparation(getKubernetesClient());
        return imageVersionPreparation.ensureImageVersion("entando-k8s-plugin-controller", "6.0.0");
    }

}
