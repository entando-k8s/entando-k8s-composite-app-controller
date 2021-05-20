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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.entando.kubernetes.model.common.EntandoCustomResource;

public class DefaultControllerImageResolver implements ControllerImageResolver {

    private static final Map<String, String> resourceKindToImageNames = buildImageMap();

    private static Map<String, String> buildImageMap() {
        Map<String, String> map = new ConcurrentHashMap<>();
        map.put("EntandoKeycloakServer", "entando-k8s-keycloak-controller");
        map.put("EntandoPlugin", "entando-k8s-plugin-controller");
        map.put("EntandoApp", "entando-k8s-app-controller");
        map.put("EntandoAppPluginLink", "entando-k8s-app-plugin-link-controller");
        map.put("EntandoCompositeApp", "entando-k8s-composite-app-controller");
        map.put("EntandoDatabaseService", "entando-k8s-database-service-controller");
        return map;
    }

    @Override
    public String getControllerImageFor(EntandoCustomResource resource) {
        return "entando/" + resourceKindToImageNames.get(resource.getKind());
    }
}
