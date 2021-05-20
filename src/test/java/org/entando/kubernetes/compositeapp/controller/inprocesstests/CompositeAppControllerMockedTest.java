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

package org.entando.kubernetes.compositeapp.controller.inprocesstests;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import java.io.Serializable;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.RandomStringUtils;
import org.entando.kubernetes.compositeapp.controller.AbstractCompositeAppControllerTest;
import org.entando.kubernetes.compositeapp.controller.EntandoCompositeAppController;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorComplianceMode;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty;
import org.entando.kubernetes.controller.support.client.PodWaitingClient;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.client.impl.DefaultSimpleK8SClient;
import org.entando.kubernetes.controller.support.client.impl.PodWatcher;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoBaseCustomResource;
import org.entando.kubernetes.model.common.EntandoCustomResourceStatus;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppBuilder;
import org.entando.kubernetes.model.compositeapp.EntandoCustomResourceReference;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.test.common.PodBehavior;
import org.junit.Rule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport;

@Tags({@Tag("in-process"), @Tag("component"), @Tag("pre-deployment")})
@EnableRuleMigrationSupport
class CompositeAppControllerMockedTest extends AbstractCompositeAppControllerTest implements PodBehavior {

    @Rule
    public KubernetesServer server = new KubernetesServer(false, true);
    private String componentNameToFail;
    private EntandoCompositeAppController entandoCompositeAppController;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

    @AfterEach
    void shutdown() {
        PodWaitingClient.ENQUEUE_POD_WATCH_HOLDERS.set(false);
        getClient().pods().getPodWatcherQueue().clear();
        scheduler.shutdownNow();
    }

    public synchronized SimpleK8SClient<?> getClient() {
        return entandoCompositeAppController.getClient();
    }

    @Override
    protected KubernetesClient getKubernetesClient() {
        return server.getClient().inNamespace(AbstractCompositeAppControllerTest.NAMESPACE);
    }

    private void ensureNamespace(KubernetesClient client, String namespace) {
        if (client.namespaces().withName(namespace).get() == null) {
            client.namespaces().create(new NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build());
        }
    }

    @Override
    protected EntandoCompositeApp performCreate(EntandoCompositeApp resource) {
        EntandoCompositeApp created = generateUidAndCreate(resource);
        prepareSystemProperties(created);
        emulatePodBehavior(created.getSpec().getComponents());
        startController();
        return created;
    }

    @Override
    protected EntandoPlugin performCreate(EntandoPlugin resource) {
        return getClient().entandoResources().createOrPatchEntandoResource(resource);
    }

    @BeforeEach
    void setUp() {
        EntandoOperatorConfig.getEntandoDockerImageInfoNamespace().ifPresent(s -> ensureNamespace(getKubernetesClient(), s));
        entandoCompositeAppController = new EntandoCompositeAppController(new DefaultSimpleK8SClient(getKubernetesClient()));
    }

    @AfterEach
    void cleanup() {
        System.clearProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_GC_CONTROLLER_PODS.getJvmSystemProperty());
    }

    @Test
    void testFailure() throws JsonProcessingException {
        //Given that EntandoPlugin deployments will fail
        this.componentNameToFail = "reference-to-" + PLUGIN_NAME;
        //When I deploy the EntandoCompositeApp
        super.testExecuteControllerPod(EntandoOperatorComplianceMode.COMMUNITY);
        //Its overall status is reflected as failed
        assertThat(
                getClient().entandoResources().load(EntandoCompositeApp.class, NAMESPACE, MY_APP).getStatus().getPhase(),
                is(EntandoDeploymentPhase.FAILED));
        //And the WebServerStatus associated with the Plugin has failed
        assertThat(getKubernetesClient().customResources(EntandoCompositeApp.class)
                .inNamespace(getKubernetesClient().getNamespace()).withName(MY_APP).get().getStatus().forServerQualifiedBy(PLUGIN_NAME)
                .get().hasFailed(), is(true));

    }

    @Test
    void shouldRemoveSuccessfullyCompletedPods() throws JsonProcessingException {
        //Given that I have configured the operator to garbage collect controller pods
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_GC_CONTROLLER_PODS.getJvmSystemProperty(), "true");
        //When I create an EntandoCompositeApp with a EntandoKeycloakServer as a component
        KubernetesClient client = getKubernetesClient();
        EntandoCompositeApp appToCreate = new EntandoCompositeAppBuilder()
                .withNewMetadata().withName(MY_APP).withNamespace(client.getNamespace()).endMetadata()
                .withNewSpec()
                .addNewEntandoKeycloakServer()
                .withNewMetadata().withName(KEYCLOAK_NAME)
                .endMetadata()
                .withNewSpec()
                .withDefault(true)
                .withDbms(DbmsVendor.NONE)
                .withIngressHostName(KEYCLOAK_NAME + ".test.com")
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
        //Then I expect the keycloak controller pod to be removed automatically
        FilterWatchListDeletable<Pod, PodList> keycloakControllerList = client.pods()
                .inNamespace(client.getNamespace())
                .withLabel(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, "EntandoKeycloakServer")
                .withLabel("EntandoKeycloakServer", app.getSpec().getComponents().get(0).getMetadata().getName());
        await().ignoreExceptions().atMost(60, TimeUnit.SECONDS).until(() -> keycloakControllerList.list().getItems().size() == 0);
    }

    private void startController() {
        entandoCompositeAppController.run();
    }

    private void prepareSystemProperties(EntandoCompositeApp resource) {
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAMESPACE.getJvmSystemProperty(),
                resource.getMetadata().getNamespace());
        System.setProperty(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAME.getJvmSystemProperty(), resource.getMetadata().getName());
    }

    private EntandoCompositeApp generateUidAndCreate(EntandoCompositeApp resource) {
        if (resource.getMetadata().getUid() == null) {
            resource.getMetadata().setUid(RandomStringUtils.randomAlphanumeric(8));
        }
        return getKubernetesClient().customResources(EntandoCompositeApp.class)
                .inNamespace(getKubernetesClient().getNamespace()).create(resource);
    }

    protected void emulatePodBehavior(List<EntandoBaseCustomResource<? extends Serializable, EntandoCustomResourceStatus>> components) {
        PodWaitingClient.ENQUEUE_POD_WATCH_HOLDERS.set(true);
        scheduler.schedule(() -> {
            try {
                //Now we take the podWatchers from the queue in the correct sequence.
                for (EntandoBaseCustomResource<? extends Serializable, EntandoCustomResourceStatus> resource : components) {
                    //The deletion of possible previous pods won't require events as there will be none
                    getClient().pods().getPodWatcherQueue().take();
                    //Now we send an event to the resulting controller PodWatcher
                    final PodWatcher controllerPodWatcher = getClient().pods().getPodWatcherQueue().take();
                    String kind = resource.getKind();
                    String name = resource.getMetadata().getName();
                    if (resource instanceof EntandoCustomResourceReference) {
                        EntandoCustomResourceReference ref = (EntandoCustomResourceReference) resource;
                        kind = ref.getSpec().getTargetKind();
                        name = ref.getSpec().getTargetName();
                    }
                    Pod pod = this.getClient().pods().loadPod(getKubernetesClient().getNamespace(), kind, name);
                    if (resource.getMetadata().getName().equals(componentNameToFail)) {
                        pod.setStatus(new PodStatusBuilder().withPhase("Failed").build());
                        controllerPodWatcher.eventReceived(Action.MODIFIED, pod);
                    } else {
                        controllerPodWatcher.eventReceived(Action.MODIFIED,
                                getKubernetesClient().pods().inNamespace(pod.getMetadata().getNamespace())
                                        .withName(pod.getMetadata().getName()).patch(podWithSucceededStatus(pod)));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 100, TimeUnit.MILLISECONDS);

    }

}
