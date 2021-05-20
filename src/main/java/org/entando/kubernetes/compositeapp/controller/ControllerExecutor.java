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

import static java.util.Optional.ofNullable;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.Watcher.Action;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.common.ResourceUtils;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.common.EntandoImageResolver;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.model.common.EntandoCustomResource;

public class ControllerExecutor {

    private final SimpleK8SClient<?> client;
    private final EntandoImageResolver imageResolver;
    private final String controllerNamespace;
    private final ControllerImageResolver controllerImageResolver;

    public ControllerExecutor(String controllerNamespace, SimpleK8SClient<?> client, ControllerImageResolver controllerImageResolver) {
        this.controllerNamespace = controllerNamespace;
        this.client = client;
        this.imageResolver = new EntandoImageResolver(client.entandoResources().loadDockerImageInfoConfigMap());
        this.controllerImageResolver = controllerImageResolver;
    }

    public Pod runControllerFor(Action action, EntandoCustomResource resource, String imageVersionToUse) {
        removeObsoleteControllerPods(resource);
        Pod pod = buildControllerPod(action, resource, imageVersionToUse);
        return client.pods().runToCompletion(pod);
    }

    private void removeObsoleteControllerPods(EntandoCustomResource resource) {
        this.client.pods().removeAndWait(controllerNamespace, Map.of(
                KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, resource.getKind(),
                KubeUtils.ENTANDO_RESOURCE_NAMESPACE_LABEL_NAME, resource.getMetadata().getNamespace(),
                resource.getKind(), resource.getMetadata().getName()));
    }

    private Pod buildControllerPod(Action action, EntandoCustomResource resource, String imageVersionToUse) {
        return new PodBuilder().withNewMetadata()
                .withName(resource.getMetadata().getName() + "-deployer-" + NameUtils.randomNumeric(4).toLowerCase())
                .withNamespace(this.controllerNamespace)
                .addToOwnerReferences(ResourceUtils.buildOwnerReference(resource))
                .addToLabels(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, resource.getKind())
                .addToLabels(KubeUtils.ENTANDO_RESOURCE_NAMESPACE_LABEL_NAME, resource.getMetadata().getNamespace())
                .addToLabels(resource.getKind(), resource.getMetadata().getName())
                .endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .withServiceAccountName(determineServiceAccountName())
                .addNewContainer()
                .withName("deployer")
                .withImage(determineControllerImage(resource, imageVersionToUse))
                .withImagePullPolicy("IfNotPresent")
                .withEnv(buildEnvVars(action, resource))
                .endContainer()
                .endSpec()
                .build();
    }

    private String determineServiceAccountName() {
        return EntandoOperatorConfig.getOperatorServiceAccount().orElse("default");
    }

    private String determineControllerImage(EntandoCustomResource resource, String imageVersionToUse) {
        return this.imageResolver.determineImageUri(
                controllerImageResolver.getControllerImageFor(resource) + ofNullable(imageVersionToUse).map(s -> ":" + s)
                        .orElse(""));
    }

    private void addTo(Map<String, EnvVar> result, EnvVar envVar) {
        result.put(envVar.getName(), envVar);
    }

    private List<EnvVar> buildEnvVars(Action action, EntandoCustomResource resource) {
        Map<String, EnvVar> result = new HashMap<>();
        System.getProperties().entrySet().stream()
                .filter(this::matchesKnownSystemProperty).forEach(objectObjectEntry -> addTo(result,
                new EnvVar(objectObjectEntry.getKey().toString().toUpperCase(Locale.ROOT).replace(".", "_").replace("-", "_"),
                        objectObjectEntry.getValue().toString(), null)));
        System.getenv().entrySet().stream()
                .filter(this::matchesKnownEnvironmentVariable)
                .forEach(objectObjectEntry -> addTo(result, new EnvVar(objectObjectEntry.getKey(),
                        objectObjectEntry.getValue(), null)));
        //Make sure we overwrite previously set resource info
        addTo(result, new EnvVar("ENTANDO_RESOURCE_ACTION", action.name(), null));
        addTo(result, new EnvVar("ENTANDO_RESOURCE_NAMESPACE", resource.getMetadata().getNamespace(), null));
        addTo(result, new EnvVar("ENTANDO_RESOURCE_NAME", resource.getMetadata().getName(), null));
        return new ArrayList<>(result.values());
    }

    private boolean matchesKnownEnvironmentVariable(Map.Entry<String, String> objectObjectEntry) {
        return objectObjectEntry.getKey().startsWith("RELATED_IMAGE") || objectObjectEntry.getKey().startsWith("ENTANDO_");
    }

    private boolean matchesKnownSystemProperty(Map.Entry<Object, Object> objectObjectEntry) {
        String propertyName = objectObjectEntry.getKey().toString().toLowerCase(Locale.ROOT).replace("_", ".");
        return propertyName.startsWith("related.image") || propertyName.startsWith("entando.");
    }

}
