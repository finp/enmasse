/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.ability;

import io.enmasse.systemtest.*;
import io.enmasse.systemtest.timemeasuring.TimeMeasuringSystem;
import io.enmasse.systemtest.utils.AddressSpaceUtils;
import io.enmasse.systemtest.utils.IoTUtils;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.Logger;

import java.io.File;


public class ExecutionListener implements TestExecutionListener {
    private static final Logger log = CustomLogger.getLogger();

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        Environment env = Environment.getInstance();
        if (!env.skipCleanup()) {
            Kubernetes kube = Kubernetes.getInstance();
            GlobalLogCollector logCollector = new GlobalLogCollector(kube, new File(env.testLogDir()));
            try {
                kube.getAddressSpaceClient().inAnyNamespace().list().getItems().forEach((addrSpace) -> {
                    log.info("address space '{}' will be removed", addrSpace);
                    try {
                        AddressSpaceUtils.deleteAddressSpaceAndWait(addrSpace, logCollector);
                    } catch (Exception e) {
                        log.warn("Cleanup failed or no clean is needed");
                    }
                });
            } catch (Exception e) {
                log.warn("Cleanup failed or no clean is needed");
            }
            if (IoTUtils.isIoTInstalled(kube)) {
                try {
                    kube.getNonNamespacedIoTProjectClient().list().getItems().forEach(project -> {
                        log.info("iot project '{}' will be removed", project.getMetadata().getName());
                        try {
                            IoTUtils.deleteIoTProjectAndWait(kube, project);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    var iotConfigClient = kube.getIoTConfigClient();
                    iotConfigClient.list().getItems().forEach(config -> {
                        log.info("iot config '{}' will be removed", config.getMetadata().getName());
                        try {
                            IoTUtils.deleteIoTConfigAndWait(kube, config);
                        } catch ( Exception e ) {
                            log.warn("Cleanup failed or no clean is needed");
                        }
                    });
                    log.info("Infinispan server will be removed");
                    SystemtestsKubernetesApps.deleteInfinispanServer(kube.getInfraNamespace());
                } catch (Exception e) {
                    log.warn("Cleanup failed or no clean is needed");
                }
            }
        } else {
            log.warn("Remove address spaces when test run finished - SKIPPED!");
        }
        TimeMeasuringSystem.printAndSaveResults();
    }
}
