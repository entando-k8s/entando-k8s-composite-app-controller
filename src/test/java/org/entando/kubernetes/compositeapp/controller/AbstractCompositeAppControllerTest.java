package org.entando.kubernetes.compositeapp.controller;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.client.DefaultIngressClient;
import org.entando.kubernetes.controller.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.creators.IngressCreator;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.integrationtest.support.FluentIntegrationTesting;
import org.entando.kubernetes.controller.integrationtest.support.TestFixturePreparation;
import org.entando.kubernetes.controller.test.support.FluentTraversals;
import org.entando.kubernetes.controller.test.support.VariableReferenceAssertions;
import org.entando.kubernetes.model.DbmsImageVendor;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppBuilder;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppOperationFactory;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerOperationFactory;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginOperationFactory;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;
import org.junit.jupiter.api.Test;

public abstract class AbstractCompositeAppControllerTest implements FluentIntegrationTesting, FluentTraversals, VariableReferenceAssertions {

    public static final String NAMESPACE = EntandoOperatorTestConfig.calculateNameSpace("e6k8s-capp-test");
    public static final String PLUGIN_NAME = EntandoOperatorTestConfig.calculateName("test-plugin");
    public static final String KEYCLOAK_NAME = EntandoOperatorTestConfig.calculateName("test-keycloak");
    public static final String MY_APP = EntandoOperatorTestConfig.calculateName("my-app");
    private String domainSuffix;

    protected abstract KubernetesClient getKubernetesClient();

    protected abstract void performCreate(EntandoCompositeApp resource);

    @Test
    public void testExecuteControllerPod() throws JsonProcessingException {
        //Given I have a clean namespace
        TestFixturePreparation.prepareTestFixture(getKubernetesClient(), deleteAll(EntandoKeycloakServer.class).fromNamespace(NAMESPACE));
        KubernetesClient client = getKubernetesClient();
        //and the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_NAMESPACE_TO_OBSERVE.getJvmSystemProperty(),
                client.getNamespace());
        //And I have a config map with the Entando KeycloakController's image information
        final String keycloakControllerVersionToExpect = ensureKeycloakControllerVersion();
        final String pluginControllerVersionToExpect = ensurePluginControllerVersion();
        //When I create a new EntandoCompositeApp with an EntandoKeycloakServer and EntandoPlugin component

        EntandoCompositeApp app = new EntandoCompositeAppBuilder()
                .withNewMetadata().withName(MY_APP).withNamespace(client.getNamespace()).endMetadata()
                .withNewSpec()
                .addNewEntandoKeycloakServer()
                .withNewMetadata().withName(KEYCLOAK_NAME).withNamespace(client.getNamespace()).endMetadata()
                .withNewSpec()
                .withDbms(DbmsImageVendor.NONE)
                .withIngressHostName(KEYCLOAK_NAME + "." + getDomainSuffix())
                .endSpec()
                .endEntandoKeycloakServer()
                .addNewEntandoPlugin()
                .withNewMetadata().withName(PLUGIN_NAME).endMetadata().withNewSpec()
                .withImage("entando/entando-avatar-plugin")
                .withDbms(DbmsImageVendor.POSTGRESQL)
                .withReplicas(1)
                .withIngressHostName(PLUGIN_NAME + "." + getDomainSuffix())
                .withHealthCheckPath("/management/health")
                .withIngressPath("/avatarPlugin")
                .withSecurityLevel(PluginSecurityLevel.STRICT)
                .endSpec()
                .endEntandoPlugin()
                .endSpec()
                .build();
        performCreate(app);
        //Then I expect to see the keycloak controller pod
        FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> keycloakControllerList = client.pods()
                .inNamespace(client.getNamespace())
                .withLabel(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, "EntandoKeycloakServer")
                .withLabel("EntandoKeycloakServer", app.getSpec().getComponents().get(0).getMetadata().getName());
        await().ignoreExceptions().atMost(30, TimeUnit.SECONDS).until(() -> keycloakControllerList.list().getItems().size() > 0);
        Pod theKeycloakControllerPod = keycloakControllerList.list().getItems().get(0);
        //and the EntandoKeycloakServer resource has been saved to K8S under the EntandoCompositeApp
        EntandoKeycloakServer entandoKeycloakServer = EntandoKeycloakServerOperationFactory
                .produceAllEntandoKeycloakServers(getKubernetesClient()).inNamespace(NAMESPACE).withName(KEYCLOAK_NAME).get();
        assertThat(entandoKeycloakServer.getMetadata().getOwnerReferences().get(0).getUid(), is(app.getMetadata().getUid()));
        //and the EntandoKeycloakServer resource's identifying information has been passed to the controller Pod
        assertThat(theVariableNamed("ENTANDO_RESOURCE_ACTION").on(thePrimaryContainerOn(theKeycloakControllerPod)),
                is(Action.ADDED.name()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAME").on(thePrimaryContainerOn(theKeycloakControllerPod)),
                is(app.getSpec().getComponents().get(0).getMetadata().getName()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAMESPACE").on(thePrimaryContainerOn(theKeycloakControllerPod)),
                is(app.getMetadata().getNamespace()));
        //With the correct version of the controller image specified
        assertTrue(thePrimaryContainerOn(theKeycloakControllerPod).getImage().endsWith(keycloakControllerVersionToExpect));
        //And its status reflecting on the EntandoCompositeApp
        assertThat(EntandoCompositeAppOperationFactory.produceAllEntandoCompositeApps(client)
                .inNamespace(client.getNamespace()).withName(MY_APP).get().getStatus().forServerQualifiedBy(KEYCLOAK_NAME).get()
                .getPodStatus(), is(notNullValue()));
        //And the plugin controller pod
        FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> pluginControllerList = client.pods()
                .inNamespace(client.getNamespace())
                .withLabel(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, "EntandoPlugin")
                .withLabel("EntandoPlugin", app.getSpec().getComponents().get(1).getMetadata().getName());
        await().ignoreExceptions().atMost(30, TimeUnit.SECONDS).until(() -> pluginControllerList.list().getItems().size() > 0);
        Pod thePluginControllerPod = pluginControllerList.list().getItems().get(0);
        //and the EntandoKeycloakServer resource has been saved to K8S under the EntandoCompositeApp
        EntandoPlugin entandoPlugin = EntandoPluginOperationFactory
                .produceAllEntandoPlugins(getKubernetesClient()).inNamespace(NAMESPACE).withName(PLUGIN_NAME).get();
        assertThat(entandoPlugin.getMetadata().getOwnerReferences().get(0).getUid(), is(app.getMetadata().getUid()));
        //and the EntandoKeycloakServer resource's identifying information has been passed to the controller Pod
        assertThat(theVariableNamed("ENTANDO_RESOURCE_ACTION").on(thePrimaryContainerOn(thePluginControllerPod)), is(Action.ADDED.name()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAME").on(thePrimaryContainerOn(thePluginControllerPod)),
                is(app.getSpec().getComponents().get(1).getMetadata().getName()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAMESPACE").on(thePrimaryContainerOn(thePluginControllerPod)),
                is(app.getMetadata().getNamespace()));
        //With the correct version specified
        assertTrue(thePrimaryContainerOn(thePluginControllerPod).getImage().endsWith(pluginControllerVersionToExpect));
        //And its status reflecting on the EntandoCompositeApp
        assertThat(EntandoCompositeAppOperationFactory.produceAllEntandoCompositeApps(client)
                .inNamespace(client.getNamespace()).withName(MY_APP).get().getStatus().forServerQualifiedBy(PLUGIN_NAME).get()
                .getPodStatus(), is(notNullValue()));
    }

    private String getDomainSuffix() {
        if (domainSuffix == null) {
            domainSuffix = IngressCreator.determineRoutingSuffix(DefaultIngressClient.resolveMasterHostname(this.getKubernetesClient()));
        }
        return domainSuffix;
    }

    protected String ensureKeycloakControllerVersion() throws JsonProcessingException {
        ImageVersionPreparation imageVersionPreparation = new ImageVersionPreparation(getKubernetesClient());
        return imageVersionPreparation.ensureImageVersion("entando-k8s-keycloak-controller", "6.0.33");
    }

    protected String ensurePluginControllerVersion() throws JsonProcessingException {
        ImageVersionPreparation imageVersionPreparation = new ImageVersionPreparation(getKubernetesClient());
        return imageVersionPreparation.ensureImageVersion("entando-k8s-plugin-controller", "6.0.21");
    }

    @Test
    public void testExecuteControllerObject() {
        TestFixturePreparation.prepareTestFixture(getKubernetesClient(), deleteAll(EntandoDatabaseService.class).fromNamespace(NAMESPACE));
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_NAMESPACE_TO_OBSERVE.getJvmSystemProperty(),
                getKubernetesClient().getNamespace());
        EntandoCompositeApp app = new EntandoCompositeAppBuilder()
                .withNewMetadata().withName(MY_APP).withNamespace(getKubernetesClient().getNamespace()).endMetadata()
                .withNewSpec()
                .addNewEntandoDatabaseService()
                .withNewMetadata().withName("test-database").withNamespace(getKubernetesClient().getNamespace()).endMetadata()
                .withNewSpec()
                .withDbms(DbmsImageVendor.ORACLE)
                .withHost("somedatabase.com")
                .withPort(5050)
                .withSecretName("oracle-secret")
                .endSpec()
                .endEntandoDatabaseService()
                .endSpec()
                .build();
        EntandoCompositeAppOperationFactory.produceAllEntandoCompositeApps(getKubernetesClient())
                .inNamespace(getKubernetesClient().getNamespace()).create(app);
        performCreate(app);
        FilterWatchListDeletable<Service, ServiceList, Boolean, Watch, Watcher<Service>> listable = getKubernetesClient()
                .services()
                .inNamespace(getKubernetesClient().getNamespace())
                .withLabel("EntandoDatabaseService", app.getSpec().getComponents().get(0).getMetadata().getName());
        await().ignoreExceptions().atMost(30, TimeUnit.SECONDS).until(() -> listable.list().getItems().size() > 0);
        Service service = listable.list().getItems().get(0);
        assertThat(service.getSpec().getExternalName(), is("somedatabase.com"));
    }

}
