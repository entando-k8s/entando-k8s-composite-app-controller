package org.entando.kubernetes.compositeapp.controller.interprocesstests;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.entando.kubernetes.compositeapp.controller.AbstractCompositeAppControllerTest;
import org.entando.kubernetes.compositeapp.controller.EntandoCompositeAppController;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig.TestTarget;
import org.entando.kubernetes.controller.integrationtest.support.TestFixturePreparation;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppOperationFactory;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

@Tag("inter-process")
public class CompositeAppControllerIntegratedTest extends AbstractCompositeAppControllerTest {

    private final DefaultKubernetesClient client = new DefaultKubernetesClient();
    private final EntandoCompositeAppIntegrationTestHelper myHelper = new EntandoCompositeAppIntegrationTestHelper(client);

    @Override
    protected KubernetesClient getKubernetesClient() {
        return client.inNamespace(NAMESPACE);
    }

    @BeforeEach
    public void cleanup() {
        TestFixturePreparation.prepareTestFixture(getKubernetesClient(),
                deleteAll(EntandoCompositeApp.class).fromNamespace(NAMESPACE)
                        .deleteAll(EntandoDatabaseService.class).fromNamespace(NAMESPACE)
                        .deleteAll(EntandoPlugin.class).fromNamespace(NAMESPACE)
                        .deleteAll(EntandoKeycloakServer.class).fromNamespace(NAMESPACE));
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
    }

    @Override
    protected EntandoCompositeApp performCreate(EntandoCompositeApp resource) {
        return EntandoCompositeAppOperationFactory.produceAllEntandoCompositeApps(getKubernetesClient())
                .inNamespace(NAMESPACE)
                .create(resource);
    }

}
