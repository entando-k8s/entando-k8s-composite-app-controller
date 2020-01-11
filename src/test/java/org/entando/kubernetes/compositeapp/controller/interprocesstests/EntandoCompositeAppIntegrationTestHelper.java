package org.entando.kubernetes.compositeapp.controller.interprocesstests;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import org.entando.kubernetes.controller.integrationtest.support.IntegrationTestHelperBase;
import org.entando.kubernetes.model.compositeapp.DoneableEntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppList;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppOperationFactory;

public class EntandoCompositeAppIntegrationTestHelper extends
        IntegrationTestHelperBase<EntandoCompositeApp, EntandoCompositeAppList, DoneableEntandoCompositeApp> {

    public EntandoCompositeAppIntegrationTestHelper(DefaultKubernetesClient client) {
        super(client, EntandoCompositeAppOperationFactory::produceAllEntandoCompositeApps);
    }
}
