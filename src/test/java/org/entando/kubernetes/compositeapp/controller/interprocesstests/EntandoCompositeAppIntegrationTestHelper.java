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

package org.entando.kubernetes.compositeapp.controller.interprocesstests;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import org.entando.kubernetes.model.compositeapp.DoneableEntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppList;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppOperationFactory;
import org.entando.kubernetes.test.e2etest.helpers.E2ETestHelperBase;

public class EntandoCompositeAppIntegrationTestHelper extends
        E2ETestHelperBase<EntandoCompositeApp, EntandoCompositeAppList, DoneableEntandoCompositeApp> {

    public EntandoCompositeAppIntegrationTestHelper(DefaultKubernetesClient client) {
        super(client, EntandoCompositeAppOperationFactory::produceAllEntandoCompositeApps);
    }
}
