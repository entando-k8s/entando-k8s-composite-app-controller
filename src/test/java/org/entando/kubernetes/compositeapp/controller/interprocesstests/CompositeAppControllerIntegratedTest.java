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
import java.util.Locale;
import org.entando.kubernetes.compositeapp.controller.AbstractCompositeAppControllerTest;
import org.entando.kubernetes.compositeapp.controller.EntandoCompositeAppController;
import org.entando.kubernetes.controller.EntandoOperatorComplianceMode;
import org.entando.kubernetes.controller.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig.TestTarget;
import org.entando.kubernetes.controller.integrationtest.support.TestFixturePreparation;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppOperationFactory;
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

}
