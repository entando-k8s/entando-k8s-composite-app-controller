package org.entando.kubernetes.compositeapp.controller.interprocesstests;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.quarkus.runtime.StartupEvent;
import org.entando.kubernetes.compositeapp.controller.AbstractCompositeAppControllerTest;
import org.entando.kubernetes.compositeapp.controller.EntandoCompositeAppController;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig.TestTarget;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

@Tag("inter-process")
public class CompositeAppControllerIntegratedTest extends AbstractCompositeAppControllerTest {

    private NamespacedKubernetesClient client;

    private static NamespacedKubernetesClient newClient() {
        return new DefaultKubernetesClient().inNamespace(NAMESPACE);
    }

    @BeforeAll
    public static void prepareController() {
        if (EntandoOperatorTestConfig.getTestTarget() == TestTarget.STANDALONE) {
            new EntandoCompositeAppController(newClient()).onStartup(new StartupEvent());
        } else {
            //Should be installed by helm chart in pipeline
        }
    }

    protected KubernetesClient getKubernetesClient() {
        if (this.client == null) {
            this.client = newClient();
        }
        return this.client;
    }

    @Override
    protected void performCreate(EntandoCompositeApp resource) {

    }

}
