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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.runtime.StartupEvent;
import java.io.Serializable;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.RandomStringUtils;
import org.entando.kubernetes.client.PodWatcher;
import org.entando.kubernetes.compositeapp.controller.AbstractCompositeAppControllerTest;
import org.entando.kubernetes.compositeapp.controller.EntandoCompositeAppController;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.PodClientDouble;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorComplianceMode;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.controller.test.support.PodBehavior;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.entando.kubernetes.model.EntandoDeploymentPhase;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppOperationFactory;
import org.entando.kubernetes.model.compositeapp.EntandoCustomResourceReference;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
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
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private final BlockingQueue<PodWatcher> queue = new ArrayBlockingQueue<>(5);

    @AfterEach
    void shutdown() {
        scheduler.shutdownNow();
    }

    @Override
    public synchronized SimpleK8SClient<?> getClient() {
        return entandoCompositeAppController.getClient();
    }

    @Override
    protected KubernetesClient getKubernetesClient() {
        return server.getClient().inNamespace(AbstractCompositeAppControllerTest.NAMESPACE);
    }

    private void ensureNamespace(KubernetesClient client, String namespace) {
        if (client.namespaces().withName(namespace).get() == null) {
            client.namespaces().createNew().withNewMetadata().withName(namespace).endMetadata().done();
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
        EntandoOperatorConfig.getOperatorConfigMapNamespace().ifPresent(s -> ensureNamespace(getKubernetesClient(), s));
        entandoCompositeAppController = new EntandoCompositeAppController(getKubernetesClient(), false);
    }

    @Test
    void testFailure() throws JsonProcessingException {
        //Given that EntandoPlugin deployments will fail
        this.componentNameToFail = "reference-to-" + PLUGIN_NAME;
        //When I deploy the EntandoCompositeApp
        super.testExecuteControllerPod(EntandoOperatorComplianceMode.COMMUNITY);
        //Its overall status is reflected as failed
        assertThat(
                getClient().entandoResources().load(EntandoCompositeApp.class, NAMESPACE, MY_APP).getStatus().getEntandoDeploymentPhase(),
                is(EntandoDeploymentPhase.FAILED));
        //And the WebServerStatus associated with the Plugin has failed
        assertThat(EntandoCompositeAppOperationFactory.produceAllEntandoCompositeApps(getKubernetesClient())
                .inNamespace(getKubernetesClient().getNamespace()).withName(MY_APP).get().getStatus().forServerQualifiedBy(PLUGIN_NAME)
                .get().hasFailed(), is(true));

    }

    private void startController() {
        entandoCompositeAppController.onStartup(new StartupEvent());
    }

    private void prepareSystemProperties(EntandoCompositeApp resource) {
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAMESPACE, resource.getMetadata().getNamespace());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAME, resource.getMetadata().getName());
    }

    private EntandoCompositeApp generateUidAndCreate(EntandoCompositeApp resource) {
        if (resource.getMetadata().getUid() == null) {
            resource.getMetadata().setUid(RandomStringUtils.randomAlphanumeric(8));
        }
        return EntandoCompositeAppOperationFactory.produceAllEntandoCompositeApps(getKubernetesClient())
                .inNamespace(getKubernetesClient().getNamespace()).create(resource);
    }

    protected void emulatePodBehavior(List<EntandoBaseCustomResource<? extends Serializable>> components) {
        PodClientDouble.setEmulatePodWatching(true);
        scheduler.scheduleAtFixedRate(() -> {
            //whenever we find a podWatcher, we offer it to the queue in the correct sequence
            //TODO move this logic to PodWaitingClient
            PodWatcher podWatcher = this.getClient().pods().getPodWatcherHolder().getAndSet(null);
            if (podWatcher != null) {
                queue.offer(podWatcher);
            }
        }, 0, 10, TimeUnit.MILLISECONDS);
        scheduler.schedule(() -> {
            try {
                //Now we take the podWatchers from the queue in the correct sequence.
                for (EntandoBaseCustomResource<? extends Serializable> resource : components) {
                    System.out.println("1  waiting for " + resource.getKind() + ":" + resource.getMetadata().getName());
                    final PodWatcher deletionWatcher = queue.take();
                    System.out.println("2  waiting for " + resource.getKind() + ":" + resource.getMetadata().getName());
                    final PodWatcher podWatcher = queue.take();
                    System.out.println("3  waiting for " + resource.getKind() + ":" + resource.getMetadata().getName());
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
                        podWatcher.eventReceived(Action.MODIFIED, pod);
                    } else {
                        podWatcher.eventReceived(Action.MODIFIED, podWithSucceededStatus(pod));
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }, 100, TimeUnit.MILLISECONDS);

    }

}
