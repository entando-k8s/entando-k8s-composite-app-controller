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

import static org.entando.kubernetes.controller.support.client.impl.EntandoOperatorTestConfig.*;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.entando.kubernetes.compositeapp.controller.AbstractCompositeAppControllerTest;
import org.entando.kubernetes.compositeapp.controller.EntandoCompositeAppController;
import org.entando.kubernetes.controller.support.client.impl.DefaultSimpleK8SClient;
import org.entando.kubernetes.controller.support.client.impl.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.TestFixturePreparation;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
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
        if (getTestTarget() == TestTarget.K8S) {
            myHelper.listenAndRespondWithImageVersionUnderTest(NAMESPACE);
        } else {
            EntandoCompositeAppController controller = new EntandoCompositeAppController(new DefaultSimpleK8SClient(getKubernetesClient()));
            myHelper.listenAndRun(NAMESPACE, controller);
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
        return getKubernetesClient().customResources(EntandoCompositeApp.class)
                .inNamespace(NAMESPACE)
                .create(resource);
    }

    @Override
    protected EntandoPlugin performCreate(EntandoPlugin resource) {
        return getKubernetesClient().customResources(EntandoPlugin.class)
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

}
