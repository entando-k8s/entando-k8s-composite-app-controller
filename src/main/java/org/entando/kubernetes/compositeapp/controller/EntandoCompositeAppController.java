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

package org.entando.kubernetes.compositeapp.controller;

import static java.lang.String.format;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.logging.Level;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import org.entando.kubernetes.controller.AbstractDbAwareController;
import org.entando.kubernetes.controller.EntandoControllerException;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.PodResult;
import org.entando.kubernetes.controller.common.ControllerExecutor;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.entando.kubernetes.model.EntandoDeploymentPhase;
import org.entando.kubernetes.model.WebServerStatus;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCustomResourceReference;

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

    @Override
    @SuppressWarnings("unchecked")
    protected void synchronizeDeploymentState(EntandoCompositeApp newCompositeApp) {
        ControllerExecutor executor = new ControllerExecutor(namespace, k8sClient);
        for (EntandoBaseCustomResource component : newCompositeApp.getSpec().getComponents()) {
            if (component instanceof EntandoCustomResourceReference) {
                EntandoCustomResourceReference ref = (EntandoCustomResourceReference) component;
                component = k8sClient.entandoResources().load(resolveType(ref.getSpec().getTargetKind()),
                        ref.getSpec().getTargetNamespace().orElse(newCompositeApp.getMetadata().getNamespace()),
                        ref.getSpec().getTargetName());

            } else {
                if (component.getMetadata().getNamespace() == null) {
                    component.getMetadata().setNamespace(newCompositeApp.getMetadata().getNamespace());
                }
                //TODO relax buildOwnerReference
                component.getMetadata().setOwnerReferences(
                        Collections.singletonList(KubeUtils.buildOwnerReference((EntandoBaseCustomResource) newCompositeApp)));
            }
            component.getStatus().setEntandoDeploymentPhase(EntandoDeploymentPhase.STARTED);
            EntandoBaseCustomResource storedComponent = k8sClient.entandoResources().createOrPatchEntandoResource(component);
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

    @SuppressWarnings("unchecked")
    protected Class<? extends EntandoBaseCustomResource> resolveType(String kind) {
        return (Class<? extends EntandoBaseCustomResource>) Arrays
                .stream(EntandoBaseCustomResource.class.getAnnotation(JsonSubTypes.class).value())
                .filter(type -> type.name().equals(kind))
                .findAny()
                .orElseThrow(IllegalArgumentException::new)
                .value();
    }
}

