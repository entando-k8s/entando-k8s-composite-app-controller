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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang3.RandomStringUtils;
import org.entando.kubernetes.client.PodWatcher;
import org.entando.kubernetes.compositeapp.controller.AbstractCompositeAppControllerTest;
import org.entando.kubernetes.compositeapp.controller.EntandoCompositeAppController;
import org.entando.kubernetes.controller.EntandoOperatorConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.PodClientDouble;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.controller.test.support.PodBehavior;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.entando.kubernetes.model.EntandoDeploymentPhase;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppOperationFactory;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.junit.Rule;
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
    private Class<? extends EntandoBaseCustomResource> classToFail;
    private EntandoCompositeAppController entandoCompositeAppController;

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

    @BeforeEach
    void setUp() {
        EntandoOperatorConfig.getOperatorConfigMapNamespace().ifPresent(s -> ensureNamespace(getKubernetesClient(),s));
        clearNamespace();
        entandoCompositeAppController = new EntandoCompositeAppController(getKubernetesClient(), false);
    }

    @Test
    void testFailure() throws JsonProcessingException {
        //Given that EntandoPlugin deployments will fail
        this.classToFail = EntandoPlugin.class;
        //When I deploy the EntandoCompositeApp
        super.testExecuteControllerPod();
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

    protected void emulatePodBehavior(List<EntandoBaseCustomResource> components) {
        PodClientDouble.setEmulatePodWatching(true);
        new Thread(() -> {
            for (EntandoBaseCustomResource resource : components) {
                if (!(resource instanceof EntandoDatabaseService)) {
                    AtomicReference<PodWatcher> podWatcherHolder = getClient().pods().getPodWatcherHolder();
                    await().atMost(30, TimeUnit.SECONDS).until(() -> podWatcherHolder.get() != null);
                    Pod pod = this.getClient().pods()
                            .loadPod(getKubernetesClient().getNamespace(), resource.getKind(), resource.getMetadata().getName());
                    if (classToFail == resource.getClass()) {
                        pod.setStatus(new PodStatusBuilder().withPhase("Failed").build());
                        podWatcherHolder.getAndSet(null).eventReceived(Action.MODIFIED, pod);
                    } else {
                        podWatcherHolder.getAndSet(null).eventReceived(Action.MODIFIED, podWithSucceededStatus(pod));
                    }
                }
            }
        }).start();

    }

}
