package enmasse.controller.address;

import enmasse.controller.common.*;
import enmasse.controller.instance.api.InstanceApi;
import enmasse.controller.model.Instance;
import io.fabric8.openshift.client.OpenShiftClient;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Watches addresses
 */
public class AddressController extends AbstractVerticle implements Watcher<Instance> {
    private static final Logger log = LoggerFactory.getLogger(AddressController.class);
    private final Kubernetes kubernetes;
    private final InstanceApi instanceApi;
    private final OpenShiftClient client;
    private final Map<Instance, String> addressSpaceMap = new HashMap<>();
    private Watch watch;

    public AddressController(InstanceApi instanceApi, Kubernetes kubernetes, OpenShiftClient client) {
        this.instanceApi = instanceApi;
        this.kubernetes = kubernetes;
        this.client = client;
    }

    @Override
    public void start(Future<Void> startPromise) throws Exception {
        vertx.executeBlocking((Future<Watch> promise) -> {
            try {
                promise.complete(instanceApi.watchInstances(this));
            } catch (Exception e) {
                promise.fail(e);
            }
        }, result -> {
            if (result.succeeded()) {
                this.watch = result.result();
                startPromise.complete();
            } else {
                startPromise.fail(result.cause());
            }
        });
    }

    @Override
    public void stop(Future<Void> stopFuture) throws Exception {
        vertx.executeBlocking(promise -> {
            try {
                if (watch != null) {
                    watch.close();
                }
                promise.complete();
            } catch (Exception e) {
                promise.fail(e);
            }
        }, result -> {
            if (result.succeeded()) {
                stopFuture.complete();
            } else {
                stopFuture.fail(result.cause());
            }
        });
    }

    @Override
    public synchronized void resourcesUpdated(Set<Instance> instances) throws Exception {
        log.debug("Check instances in address controller: " + instances);
        for (Instance instance : instances) {
            if (!addressSpaceMap.containsKey(instance)) {
                AddressClusterGenerator clusterGenerator = new TemplateAddressClusterGenerator(instance, kubernetes);
                AddressSpaceController addressSpaceController = new AddressSpaceController(
                        instanceApi.withInstance(instance.id()),
                        kubernetes.withInstance(instance.id()),
                        clusterGenerator);
                log.info("Deploying address space controller for " + instance.id());
                vertx.deployVerticle(addressSpaceController, result -> {
                    if (result.succeeded()) {
                        addressSpaceMap.put(instance, result.result());
                    } else {
                        log.warn("Unable to deploy address controller for " + instance.id());
                    }
                });
            }
        }

        Iterator<Map.Entry<Instance, String>> it = addressSpaceMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Instance, String> entry = it.next();
            if (!instances.contains(entry.getKey())) {
                vertx.undeploy(entry.getValue());
                it.remove();
            }
        }
    }
}
