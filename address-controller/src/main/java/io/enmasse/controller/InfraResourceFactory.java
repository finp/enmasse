/*
 * Copyright 2017-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.controller;

import io.enmasse.address.model.AddressSpace;
import io.fabric8.kubernetes.api.model.KubernetesList;

public interface InfraResourceFactory {
    KubernetesList createResourceList(AddressSpace addressSpace);
}
