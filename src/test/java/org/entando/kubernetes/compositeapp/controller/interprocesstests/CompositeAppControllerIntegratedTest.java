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

package org.entando.kubernetes.compositeapp.controller.interprocesstests;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.entando.kubernetes.client.DefaultSimpleK8SClient;
import org.entando.kubernetes.client.EntandoOperatorTestConfig;
import org.entando.kubernetes.client.EntandoOperatorTestConfig.TestTarget;
import org.entando.kubernetes.client.integrationtesthelpers.TestFixturePreparation;
import org.entando.kubernetes.compositeapp.controller.AbstractCompositeAppControllerTest;
import org.entando.kubernetes.compositeapp.controller.EntandoCompositeAppController;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.model.EntandoCustomResource;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppOperationFactory;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginOperationFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;

@Tags({@Tag("end-to-end"), @Tag("inter-process"), @Tag("smoke-test"), @Tag("post-deployment")})
class CompositeAppControllerIntegratedTest extends AbstractCompositeAppControllerTest {

    private DefaultKubernetesClient client;
    private EntandoCompositeAppIntegrationTestHelper myHelper;

    @Override
    protected KubernetesClient getKubernetesClient() {
        return client;
    }

    @BeforeEach
    public void cleanup() {
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_IMAGE_PULL_SECRETS.getJvmSystemProperty(), "redhat-registry");
        this.client = (DefaultKubernetesClient) TestFixturePreparation.newClient().inNamespace(NAMESPACE);
        this.myHelper = new EntandoCompositeAppIntegrationTestHelper(client);
        clearNamespace();
        registerListeners();
    }

    private void registerListeners() {
        if (EntandoOperatorTestConfig.getTestTarget() == TestTarget.K8S) {
            myHelper.listenAndRespondWithImageVersionUnderTest(NAMESPACE);
        } else {
            EntandoCompositeAppController controller = new EntandoCompositeAppController(getKubernetesClient(), false);
            myHelper.listenAndRespondWithStartupEvent(NAMESPACE, controller::onStartup);
        }
    }

    @AfterEach
    public void stopListening() {
        myHelper.afterTest();
        client.close();
        System.clearProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_COMPLIANCE_MODE.getJvmSystemProperty());
    }

    @Override
    protected EntandoCompositeApp performCreate(EntandoCompositeApp resource) {
        return EntandoCompositeAppOperationFactory.produceAllEntandoCompositeApps(getKubernetesClient())
                .inNamespace(NAMESPACE)
                .create(resource);
    }

    @Override
    protected EntandoPlugin performCreate(EntandoPlugin resource) {
        return EntandoPluginOperationFactory.produceAllEntandoPlugins(getKubernetesClient())
                .inNamespace(NAMESPACE)
                .create(resource);
    }

    protected void clearNamespace() {
        TestFixturePreparation.prepareTestFixture(getKubernetesClient(),
                deleteAll(EntandoCompositeApp.class).fromNamespace(NAMESPACE)
                        .deleteAll(EntandoDatabaseService.class).fromNamespace(NAMESPACE)
                        .deleteAll(EntandoPlugin.class).fromNamespace(NAMESPACE)
                        .deleteAll(EntandoKeycloakServer.class).fromNamespace(NAMESPACE));
    }








//    String ENTANDO_RESOURCE_ACTION = "entando.resource.action";
//    String TEST_CONTROLLER_POD = "test-controller-pod";
//
//    void runControllerAgainstCustomResource(EntandoCustomResource entandoCustomResource, KubernetesClient client) {
//        try {
//            final var simpleK8SClient = new DefaultSimpleK8SClient(client);
//
//            ///attachKubernetesResource(entandoCustomResource.getKind(), entandoCustomResource);
//            System.setProperty(ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
//            System.setProperty(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAME.getJvmSystemProperty(),
//                    entandoCustomResource.getMetadata().getName());
//            System.setProperty(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAMESPACE.getJvmSystemProperty(),
//                    entandoCustomResource.getMetadata().getNamespace());
//            System.setProperty(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_KIND.getJvmSystemProperty(),
//                    entandoCustomResource.getKind());
//            System.setProperty(EntandoOperatorSpiConfigProperty.ENTANDO_CONTROLLER_POD_NAME.getJvmSystemProperty(),
//                    TEST_CONTROLLER_POD);
//            simpleK8SClient.entandoResources().createOrPatchEntandoResource(entandoCustomResource);
//            final SimpleKeycloakClient keycloakClient = new DefaultKeycloakClient();
//            final var commandStream = new InProcessCommandStream(simpleK8SClient, keycloakClient);
//
//            Runnable controller = new EntandoCompositeAppController(client);
//            controller.run();
//        } catch (RuntimeException e) {
//            Logger.getLogger(getClass().getName()).log(Level.WARNING, e, e::getMessage);
//            throw e;
//        }
//    }
//
//    public EntandoCompositeAppController createController() {
//        return new EntandoCompositeAppController(client);
//    }
}
