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
import io.fabric8.kubernetes.client.Watcher.Action;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.inject.Inject;
import org.entando.kubernetes.controller.spi.common.EntandoControllerException;
import org.entando.kubernetes.controller.spi.common.PodResult;
import org.entando.kubernetes.controller.spi.common.ResourceUtils;
import org.entando.kubernetes.controller.support.client.EntandoResourceClient;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.controller.support.common.OperatorProcessingInstruction;
import org.entando.kubernetes.model.common.EntandoBaseCustomResource;
import org.entando.kubernetes.model.common.EntandoBaseFluent;
import org.entando.kubernetes.model.common.EntandoCustomResourceStatus;
import org.entando.kubernetes.model.common.EntandoIngressingDeploymentBaseFluent;
import org.entando.kubernetes.model.common.ExposedServerStatus;
import org.entando.kubernetes.model.common.NestedIngressingDeploymentSpecFluent;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppSpecFluent;
import org.entando.kubernetes.model.compositeapp.EntandoCustomResourceReference;
import picocli.CommandLine;

@CommandLine.Command()
public class EntandoCompositeAppController implements Runnable {

    private final String namespace;
    private final SimpleK8SClient<EntandoResourceClient> client;
    private final Logger logger = Logger.getLogger(EntandoCompositeAppController.class.getName());

    @Inject
    @SuppressWarnings("unchecked")
    public EntandoCompositeAppController(SimpleK8SClient<?> client) {
        this.client = (SimpleK8SClient<EntandoResourceClient>) client;
        this.namespace = client.entandoResources().getNamespace();
    }

    public SimpleK8SClient<EntandoResourceClient> getClient() {
        return this.client;
    }

    @Override
    public void run() {
        EntandoCompositeApp newCompositeApp = (EntandoCompositeApp) getClient().entandoResources()
                .resolveCustomResourceToProcess(Collections.singletonList(EntandoCompositeApp.class));
        try {
            ControllerExecutor executor = new ControllerExecutor(namespace, getClient(), new DefaultControllerImageResolver());
            for (EntandoBaseCustomResource<?, EntandoCustomResourceStatus> resource : newCompositeApp.getSpec().getComponents()) {
                if (resource instanceof EntandoCustomResourceReference) {
                    resource = prepareReference(newCompositeApp, resource);
                } else {
                    resource = prepareComponent(newCompositeApp, resource);
                }
                Pod pod = processResource(newCompositeApp, executor, resource);
                if (PodResult.of(pod).hasFailed()) {
                    String message = logFailure(resource);
                    throw new EntandoControllerException(message);
                } else {
                    if (KubeUtils.resolveProcessingInstruction(resource) == OperatorProcessingInstruction.DEFER) {
                        removeDeferInstruction(resource);
                    }
                    if (EntandoOperatorConfig.garbageCollectSuccessfullyCompletedPods()) {
                        getClient().pods()
                                .removeSuccessfullyCompletedPods(namespace,
                                        Map.of(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, resource.getKind(),
                                                KubeUtils.ENTANDO_RESOURCE_NAMESPACE_LABEL_NAME, resource.getMetadata().getNamespace(),
                                                resource.getKind(), resource.getMetadata().getName()));
                    }
                }
            }
        } catch (Exception e) {
            getClient().entandoResources().deploymentFailed(newCompositeApp, e);
            throw new CommandLine.ExecutionException(new CommandLine(this), e.getMessage());

        }
    }

    private void removeDeferInstruction(EntandoBaseCustomResource<?, ?> resource) {
        final EntandoBaseCustomResource<?, ?> reloaded = client.entandoResources().reload(resource);
        reloaded.getMetadata().getAnnotations().remove(KubeUtils.PROCESSING_INSTRUCTION_ANNOTATION_NAME);
        client.entandoResources().createOrPatchEntandoResource(reloaded);
    }

    private String logFailure(EntandoBaseCustomResource<?, EntandoCustomResourceStatus> resource) {
        String message = format("Unexpected exception occurred while adding %s %s/%s", resource.getKind(),
                resource.getMetadata().getNamespace(),
                resource.getMetadata().getName());
        this.logger.log(Level.SEVERE, message);
        return message;
    }

    private Pod processResource(EntandoCompositeApp newCompositeApp, ControllerExecutor executor,
            EntandoBaseCustomResource<?, ?> resource) {
        Pod pod = executor.runControllerFor(Action.ADDED, resource, null);
        ExposedServerStatus webServerStatus = new ExposedServerStatus(resource.getMetadata().getName());
        webServerStatus.putPodPhase(pod.getMetadata().getName(), pod.getStatus().getPhase());
        getClient().entandoResources().updateStatus(newCompositeApp, webServerStatus);
        return pod;
    }

    @SuppressWarnings("unchecked")
    private <S extends Serializable, C extends EntandoBaseCustomResource<S, EntandoCustomResourceStatus>> C prepareComponent(
            EntandoCompositeApp newCompositeApp,
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
        return getClient().entandoResources().createOrPatchEntandoResource(component);
    }

    private <S extends Serializable, T extends EntandoBaseCustomResource<S, EntandoCustomResourceStatus>> T prepareReference(
            EntandoCompositeApp newCompositeApp,
            T component) {
        EntandoCustomResourceReference ref = (EntandoCustomResourceReference) component;
        return getClient().entandoResources().load(this.<S, T>resolveType(ref.getSpec().getTargetKind()),
                ref.getSpec().getTargetNamespace().orElse(newCompositeApp.getMetadata().getNamespace()),
                ref.getSpec().getTargetName());
    }

    @SuppressWarnings("unchecked")
    protected <S extends Serializable, T extends EntandoBaseCustomResource<S, EntandoCustomResourceStatus>> Class<T> resolveType(
            String kind) {
        return (Class<T>) Arrays
                .stream(EntandoBaseCustomResource.class.getAnnotation(JsonSubTypes.class).value())
                .filter(type -> type.name().equals(kind))
                .findAny()
                .orElseThrow(IllegalArgumentException::new)
                .value();
    }
}

