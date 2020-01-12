package org.entando.kubernetes.compositeapp.controller;

import static java.lang.String.format;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import java.util.Collections;
import java.util.logging.Level;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import org.entando.kubernetes.controller.AbstractDbAwareController;
import org.entando.kubernetes.controller.EntandoControllerException;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.PodResult;
import org.entando.kubernetes.controller.common.ControllerExecutor;
import org.entando.kubernetes.controller.database.EntandoDatabaseServiceController;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.entando.kubernetes.model.WebServerStatus;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;

public class EntandoCompositeAppController extends AbstractDbAwareController<EntandoCompositeApp> {

    private final String namespace;

    @Inject
    public EntandoCompositeAppController(KubernetesClient kubernetesClient) {
        this(kubernetesClient, true);
    }

    /**
     * This constructor is intended for in-process tests where we do not want the controller to exit automatically.
     */
    public EntandoCompositeAppController(KubernetesClient kubernetesClient, boolean exitAutomatically) {
        super(kubernetesClient, exitAutomatically);
        this.namespace = kubernetesClient.getNamespace();
    }

    public SimpleK8SClient<?> getClient() {
        return super.k8sClient;
    }

    public void onStartup(@Observes StartupEvent event) {
        processCommand();
    }

    protected void processAddition(EntandoCompositeApp newCompositeApp) {
        ControllerExecutor executor = new ControllerExecutor(namespace, k8sClient);
        for (EntandoBaseCustomResource component : newCompositeApp.getSpec().getComponents()) {
            if (component.getMetadata().getNamespace() == null) {
                component.getMetadata().setNamespace(newCompositeApp.getMetadata().getNamespace());
            }
            component.getMetadata().setOwnerReferences(Collections.singletonList(KubeUtils.buildOwnerReference(newCompositeApp)));
            EntandoBaseCustomResource storedComponent = k8sClient.entandoResources().putEntandoCustomResource(component);
            if (storedComponent instanceof EntandoDatabaseService) {
                new EntandoDatabaseServiceController(k8sClient).processEvent(Action.ADDED, (EntandoDatabaseService) storedComponent);
            } else {
                Pod pod = executor.runControllerFor(
                        Action.ADDED,
                        storedComponent,
                        executor.resolveLatestImageFor(storedComponent.getClass()).orElseThrow(IllegalStateException::new)
                );
                WebServerStatus webServerStatus = new WebServerStatus(storedComponent.getMetadata().getName());
                webServerStatus.setPodStatus(pod.getStatus());
                getClient().entandoResources().updateStatus(newCompositeApp, webServerStatus);
                if (PodResult.of(pod).hasFailed()) {
                    String message = format("Unexpected exception occurred while adding %s %s/%s", storedComponent.getKind(),
                            storedComponent.getMetadata().getNamespace(),
                            storedComponent.getMetadata().getName());
                    this.logger.log(Level.SEVERE, message);
                    throw new EntandoControllerException(message);
                }
            }
        }
    }
}

