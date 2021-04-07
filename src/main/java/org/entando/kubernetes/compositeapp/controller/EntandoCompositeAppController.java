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
import io.fabric8.kubernetes.api.builder.Builder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Level;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import org.entando.kubernetes.controller.spi.common.PodResult;
import org.entando.kubernetes.controller.spi.common.ResourceUtils;
import org.entando.kubernetes.controller.support.client.EntandoResourceClient;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.controller.support.controller.AbstractDbAwareController;
import org.entando.kubernetes.controller.support.controller.ControllerExecutor;
import org.entando.kubernetes.controller.support.controller.DefaultControllerImageResolver;
import org.entando.kubernetes.controller.support.controller.EntandoControllerException;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.entando.kubernetes.model.EntandoBaseFluent;
import org.entando.kubernetes.model.EntandoIngressingDeploymentBaseFluent;
import org.entando.kubernetes.model.NestedIngressingDeploymentSpecFluent;
import org.entando.kubernetes.model.WebServerStatus;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppSpec;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppSpecFluent;
import org.entando.kubernetes.model.compositeapp.EntandoCustomResourceReference;
import org.jetbrains.annotations.NotNull;

public class EntandoCompositeAppController extends AbstractDbAwareController<EntandoCompositeAppSpec, EntandoCompositeApp> {

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

    //We know this won't ever break.
    @SuppressWarnings("unchecked")
    public SimpleK8SClient<EntandoResourceClient> getClient() {
        return (SimpleK8SClient<EntandoResourceClient>) super.k8sClient;
    }

    public void onStartup(@Observes StartupEvent event) {
        processCommand();
    }

    @Override
    protected void synchronizeDeploymentState(EntandoCompositeApp newCompositeApp) {
        ControllerExecutor executor = new ControllerExecutor(namespace, k8sClient, new DefaultControllerImageResolver());
        for (EntandoBaseCustomResource<?> resource : newCompositeApp.getSpec().getComponents()) {
            if (resource instanceof EntandoCustomResourceReference) {
                resource = prepareReference(newCompositeApp, resource);
            } else {
                resource = prepareComponent(newCompositeApp, resource);
            }
            Pod pod = processResource(newCompositeApp, executor, resource);
            if (PodResult.of(pod).hasFailed()) {
                String message = logFailure(resource);
                throw new EntandoControllerException(message);
            } else if (EntandoOperatorConfig.garbageCollectSuccessfullyCompletedPods()) {
                getClient().pods()
                        .removeSuccessfullyCompletedPods(namespace, Map.of(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, resource.getKind(),
                                KubeUtils.ENTANDO_RESOURCE_NAMESPACE_LABEL_NAME, resource.getMetadata().getNamespace(),
                                resource.getKind(), resource.getMetadata().getName()));
            }
        }
    }

    private String logFailure(EntandoBaseCustomResource<?> resource) {
        String message = format("Unexpected exception occurred while adding %s %s/%s", resource.getKind(),
                resource.getMetadata().getNamespace(),
                resource.getMetadata().getName());
        this.logger.log(Level.SEVERE, message);
        return message;
    }

    @NotNull
    private Pod processResource(EntandoCompositeApp newCompositeApp, ControllerExecutor executor, EntandoBaseCustomResource<?> resource) {
        Pod pod = executor.runControllerFor(Action.ADDED, resource, null);
        WebServerStatus webServerStatus = new WebServerStatus(resource.getMetadata().getName());
        webServerStatus.setPodStatus(pod.getStatus());
        getClient().entandoResources().updateStatus(newCompositeApp, webServerStatus);
        return pod;
    }

    @SuppressWarnings("unchecked")
    private <S extends Serializable, C extends EntandoBaseCustomResource<S>> C prepareComponent(EntandoCompositeApp newCompositeApp,
            C component) {
        EntandoBaseFluent<?> componentBuilder = EntandoCompositeAppSpecFluent.newBuilderFrom(component);
        if (component.getMetadata().getNamespace() == null) {
            componentBuilder = componentBuilder.editMetadata().withNamespace(newCompositeApp.getMetadata().getNamespace()).endMetadata();
        }
        if (componentBuilder instanceof EntandoIngressingDeploymentBaseFluent) {
            NestedIngressingDeploymentSpecFluent<?, ?> eidsbf = ((EntandoIngressingDeploymentBaseFluent<?, ?>) componentBuilder).editSpec();
            newCompositeApp.getSpec().getDbmsOverride().ifPresent(eidsbf::withDbms);
            newCompositeApp.getSpec().getIngressHostNameOverride().ifPresent(eidsbf::withIngressHostName);
            newCompositeApp.getSpec().getTlsSecretNameOverride().ifPresent(eidsbf::withTlsSecretName);
            componentBuilder = eidsbf.endSpec();
        }
        componentBuilder = componentBuilder.editMetadata()
                .withOwnerReferences(Collections.singletonList(ResourceUtils.buildOwnerReference(newCompositeApp)))
                .endMetadata();
        component = ((Builder<C>) componentBuilder).build();
        return k8sClient.entandoResources().createOrPatchEntandoResource(component);
    }

    private <S extends Serializable, T extends EntandoBaseCustomResource<S>> T prepareReference(EntandoCompositeApp newCompositeApp,
            T component) {
        EntandoCustomResourceReference ref = (EntandoCustomResourceReference) component;
        return k8sClient.entandoResources().load(this.<S, T>resolveType(ref.getSpec().getTargetKind()),
                ref.getSpec().getTargetNamespace().orElse(newCompositeApp.getMetadata().getNamespace()),
                ref.getSpec().getTargetName());
    }

    @SuppressWarnings("unchecked")
    protected <S extends Serializable, T extends EntandoBaseCustomResource<S>> Class<T> resolveType(String kind) {
        return (Class<T>) Arrays
                .stream(EntandoBaseCustomResource.class.getAnnotation(JsonSubTypes.class).value())
                .filter(type -> type.name().equals(kind))
                .findAny()
                .orElseThrow(IllegalArgumentException::new)
                .value();
    }
}

