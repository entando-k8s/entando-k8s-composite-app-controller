package org.entando.kubernetes.compositeapp.controller;

import static java.lang.String.format;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.Map;
import org.entando.kubernetes.controller.EntandoOperatorConfig;

public class ImageVersionPreparation {

    private final KubernetesClient kubernetesClient;

    public ImageVersionPreparation(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    public String ensureImageVersion(String imageName, String fallbackVersion) throws JsonProcessingException {
        ConfigMap versionConfigMap = getKubernetesClient().configMaps().inNamespace(getConfigMapNamespace())
                .withName(getVersionsConfigMapName())
                .fromServer().get();
        if (versionConfigMap == null) {
            getKubernetesClient().configMaps().inNamespace(getConfigMapNamespace()).createNew().withNewMetadata().withName(
                    getVersionsConfigMapName()).endMetadata()
                    .addToData(imageName, format(
                            "{\"version\":\"%s\"}",
                            fallbackVersion)).done();
            return fallbackVersion;
        } else {
            return ensureImageInfoPresent(versionConfigMap, imageName, fallbackVersion);
        }
    }

    private KubernetesClient getKubernetesClient() {
        return kubernetesClient;
    }

    private String getVersionsConfigMapName() {
        return EntandoOperatorConfig.getEntandoDockerImageVersionsConfigMap();
    }

    private String getConfigMapNamespace() {
        return EntandoOperatorConfig.getOperatorConfigMapNamespace().orElse(AbstractCompositeAppControllerTest.NAMESPACE);
    }

    private String ensureImageInfoPresent(ConfigMap versionConfigMap, String imageName, String fallbackVersion)
            throws JsonProcessingException {
        String imageInfo = versionConfigMap.getData().get(imageName);
        if (imageInfo == null) {
            getKubernetesClient().configMaps().inNamespace(versionConfigMap.getMetadata().getNamespace())
                    .withName(versionConfigMap.getMetadata().getName()).edit()
                    .addToData(imageName, format(
                            "{\"version\":\"%s\"}",
                            fallbackVersion)).done();
            return fallbackVersion;
        } else {
            return ensureVersionAttributePresent(versionConfigMap, imageInfo, imageName, fallbackVersion);
        }
    }

    @SuppressWarnings("unchecked")
    private String ensureVersionAttributePresent(ConfigMap versionConfigMap, String imageInfo, String imageName, String fallbackVersion)
            throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> map = mapper.readValue(imageInfo, Map.class);
        String version = (String) map.get("version");
        if (version == null) {
            map.put("version", fallbackVersion);
            getKubernetesClient().configMaps().inNamespace(versionConfigMap.getMetadata().getNamespace())
                    .withName(versionConfigMap.getMetadata().getName()).edit()
                    .addToData(imageName, mapper.writeValueAsString(map)).done();
            return fallbackVersion;
        } else {
            return version;
        }
    }
}
