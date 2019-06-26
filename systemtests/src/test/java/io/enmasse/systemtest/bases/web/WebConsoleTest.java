/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.bases.web;


import io.enmasse.address.model.*;
import io.enmasse.systemtest.AddressSpaceType;
import io.enmasse.systemtest.AddressType;
import io.enmasse.systemtest.*;
import io.enmasse.systemtest.amqp.AmqpClient;
import io.enmasse.systemtest.bases.TestBaseWithShared;
import io.enmasse.systemtest.messagingclients.AbstractClient;
import io.enmasse.systemtest.messagingclients.rhea.RheaClientConnector;
import io.enmasse.systemtest.selenium.ISeleniumProvider;
import io.enmasse.systemtest.selenium.page.ConsoleWebPage;
import io.enmasse.systemtest.selenium.resources.AddressWebItem;
import io.enmasse.systemtest.selenium.resources.ConnectionWebItem;
import io.enmasse.systemtest.selenium.resources.FilterType;
import io.enmasse.systemtest.selenium.resources.SortType;
import io.enmasse.systemtest.utils.AddressSpaceUtils;
import io.enmasse.systemtest.utils.AddressUtils;
import io.enmasse.systemtest.utils.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.CoreMatchers.either;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.*;

public abstract class WebConsoleTest extends TestBaseWithShared implements ISeleniumProvider {

    private static Logger log = CustomLogger.getLogger();
    private List<AbstractClient> clientsList;


    private ConsoleWebPage consoleWebPage;

    @BeforeEach
    public void setUpWebConsoleTests() throws Exception {
        super.deleteAddresses(sharedAddressSpace);
    }

    @AfterEach
    public void tearDownWebConsoleTests() {
        if (clientsList != null) {
            stopClients(clientsList);
            clientsList.clear();
        }
    }

    //============================================================================================
    //============================ do test methods ===============================================
    //============================================================================================

    protected void doTestCreateDeleteAddress(Address... destinations) throws Exception {
        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        for (Address dest : destinations) {
            consoleWebPage.createAddressWebConsole(dest, true);
            consoleWebPage.deleteAddressWebConsole(dest);
        }
        assertWaitForValue(0, () -> consoleWebPage.getResultsCount(), new TimeoutBudget(20, TimeUnit.SECONDS));
    }

    protected void doTestCreateDeleteDurableSubscription(Address... destinations) throws Exception {
        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        consoleWebPage.openAddressesPageWebConsole();


        for (Address dest : destinations) {
            //create topic
            consoleWebPage.createAddressWebConsole(dest);

            //create subscription
            Address subscription = new AddressBuilder()
                    .withNewMetadata()
                    .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                    .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, dest.getSpec().getAddress() + "-subscriber"))
                    .endMetadata()
                    .withNewSpec()
                    .withType("subscription")
                    .withAddress(dest.getSpec().getAddress() + "-subscriber")
                    .withTopic(dest.getSpec().getAddress())
                    .withPlan(DestinationPlan.STANDARD_LARGE_SUBSCRIPTION)
                    .endSpec()
                    .build();
            consoleWebPage.createAddressWebConsole(subscription);
            assertWaitForValue(2, () -> consoleWebPage.getResultsCount(), new TimeoutBudget(120, TimeUnit.SECONDS));

            //delete topic and sub
            consoleWebPage.deleteAddressWebConsole(subscription);
            consoleWebPage.deleteAddressWebConsole(dest);
        }
        assertWaitForValue(0, () -> consoleWebPage.getResultsCount(), new TimeoutBudget(20, TimeUnit.SECONDS));
    }

    protected void doTestAddressStatus(Address destination) throws Exception {
        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        consoleWebPage.createAddressWebConsole(destination, false);
        assertThat("Console failed, expected PENDING or READY state",
                consoleWebPage.getAddressItem(destination).getStatus(),
                either(is(AddressStatus.PENDING)).or(is(AddressStatus.READY)));

        AddressUtils.waitForDestinationsReady(new TimeoutBudget(5, TimeUnit.MINUTES), destination);

        assertEquals(AddressStatus.READY, consoleWebPage.getAddressItem(destination).getStatus(),
                "Console failed, expected READY state");
    }

    protected void doTestFilterAddressesByType() throws Exception {
        int addressCount = 4;
        ArrayList<Address> addresses = generateQueueTopicList(sharedAddressSpace, "via-web", IntStream.range(0, addressCount));

        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        consoleWebPage.createAddressesWebConsole(addresses.toArray(new Address[0]));
        assertThat(String.format("Console failed, does not contain %d addresses", addressCount),
                consoleWebPage.getAddressItems().size(), is(addressCount));

        consoleWebPage.addAddressesFilter(FilterType.TYPE, AddressType.QUEUE.toString());
        List<AddressWebItem> items = consoleWebPage.getAddressItems();
        assertThat(String.format("Console failed, does not contain %d addresses", addressCount / 2),
                items.size(), is(addressCount / 2)); //assert correct count
        assertAddressType("Console failed, does not contains only address type queue",
                items, AddressType.QUEUE); //assert correct type

        consoleWebPage.removeFilterByType(AddressType.QUEUE.toString());
        assertThat(String.format("Console failed, does not contain %d addresses", addressCount),
                consoleWebPage.getAddressItems().size(), is(addressCount));

        consoleWebPage.addAddressesFilter(FilterType.TYPE, AddressType.TOPIC.toString());
        items = consoleWebPage.getAddressItems();
        assertThat(String.format("Console failed, does not contain %d addresses", addressCount / 2),
                items.size(), is(addressCount / 2)); //assert correct count
        assertAddressType("Console failed, does not contains only address type topic",
                items, AddressType.TOPIC); //assert correct type

        consoleWebPage.removeFilterByType(AddressType.TOPIC.toString());
        assertThat(String.format("Console failed, does not contain %d addresses", addressCount),
                consoleWebPage.getAddressItems().size(), is(addressCount));
    }

    protected void doTestFilterAddressesByName() throws Exception {
        int addressCount = 4;
        ArrayList<Address> addresses = generateQueueTopicList(sharedAddressSpace, "via-web", IntStream.range(0, addressCount));

        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        consoleWebPage.createAddressesWebConsole(addresses.toArray(new Address[0]));

        String subText = "web";
        consoleWebPage.addAddressesFilter(FilterType.NAME, subText);
        List<AddressWebItem> items = consoleWebPage.getAddressItems();
        assertEquals(addressCount, items.size(),
                String.format("Console failed, does not contain %d addresses", addressCount));
        assertAddressName("Console failed, does not contain addresses contain " + subText, items, subText);

        subText = "via";
        consoleWebPage.addAddressesFilter(FilterType.NAME, subText);
        items = consoleWebPage.getAddressItems();
        assertEquals(addressCount, items.size(),
                String.format("Console failed, does not contain %d addresses", addressCount));
        assertAddressName("Console failed, does not contain addresses contain " + subText, items, subText);

        subText = "web";
        consoleWebPage.removeFilterByName(subText);
        items = consoleWebPage.getAddressItems();
        assertEquals(addressCount, items.size(),
                String.format("Console failed, does not contain %d addresses", addressCount));
        assertAddressName("Console failed, does not contain addresses contain " + subText, items, subText);

        subText = "queue";
        consoleWebPage.addAddressesFilter(FilterType.NAME, subText);
        items = consoleWebPage.getAddressItems();
        assertEquals(addressCount / 2, items.size(),
                String.format("Console failed, does not contain %d addresses", addressCount / 2));
        assertAddressName("Console failed, does not contain addresses contain " + subText, items, subText);

        consoleWebPage.clearAllFilters();
        assertEquals(addressCount, consoleWebPage.getAddressItems().size(),
                String.format("Console failed, does not contain %d addresses", addressCount));
    }

    protected void doTestDeleteFilteredAddress() throws Exception {
        String testString = "addressName";
        List<AddressWebItem> items;
        int addressTotal = 2;

        Address destQueue = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, testString + "queue"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress(testString + "queue")
                .withPlan(getDefaultPlan(AddressType.QUEUE))
                .endSpec()
                .build();

        Address destTopic = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, testString + "topic"))
                .endMetadata()
                .withNewSpec()
                .withType("topic")
                .withAddress(testString + "topic")
                .withPlan(getDefaultPlan(AddressType.TOPIC))
                .endSpec()
                .build();

        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        consoleWebPage.createAddressWebConsole(destQueue);
        consoleWebPage.createAddressWebConsole(destTopic);

        consoleWebPage.addAddressesFilter(FilterType.NAME, "queue");
        items = consoleWebPage.getAddressItems();

        assertEquals(addressTotal / 2, items.size(),
                String.format("Console failed, filter does not contain %d addresses", addressTotal / 2));

        assertAddressName("Console failed, filter does not contain addresses", items, "queue");

        consoleWebPage.deleteAddressWebConsole(destQueue);
        items = consoleWebPage.getAddressItems();
        assertEquals(0, items.size());
        log.info("filtered address has been deleted and no longer present in filter");

        consoleWebPage.clearAllFilters();
        items = consoleWebPage.getAddressItems();
        assertEquals(addressTotal / 2, items.size());
    }

    protected void doTestFilterAddressWithRegexSymbols() throws Exception {
        int addressCount = 4;
        ArrayList<Address> addresses = generateQueueTopicList(sharedAddressSpace, "via-web", IntStream.range(0, addressCount));

        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        consoleWebPage.createAddressesWebConsole(addresses.toArray(new Address[0]));
        assertThat(String.format("Console failed, does not contain %d addresses", addressCount),
                consoleWebPage.getAddressItems().size(), is(addressCount));

        //valid filter, will show 2 results
        String subText = "topic";
        consoleWebPage.addAddressesFilter(FilterType.NAME, subText);
        List<AddressWebItem> items = consoleWebPage.getAddressItems();
        assertEquals(addressCount / 2, items.size(),
                String.format("Console failed, does not contain %d addresses", addressCount / 2));
        assertAddressName("Console failed, does not contain addresses contain " + subText, items, subText);
        consoleWebPage.clearAllFilters();

        //invalid filter (not regex), error message is shown
        subText = "*";
        consoleWebPage.addAddressesFilter(FilterType.NAME, subText);
        WebElement regexAlert = selenium.getWebElement(() -> selenium.getDriver().findElement(By.className("pficon-error-circle-o")));
        assertTrue(regexAlert.isDisplayed());

        //valid regex filter (.*), will show 4 results
        subText = ".*";
        consoleWebPage.addAddressesFilter(FilterType.NAME, subText);
        items = consoleWebPage.getAddressItems();
        assertEquals(addressCount, items.size(),
                String.format("Console failed, does not contain %d addresses", addressCount));
        consoleWebPage.clearAllFilters();

        //valid regex filter ([0-9]\d*$) = any address ending with a number, will show 4 results
        subText = "[0-9]\\d*$";
        consoleWebPage.addAddressesFilter(FilterType.NAME, subText);
        items = consoleWebPage.getAddressItems();
        assertEquals(addressCount, items.size(),
                String.format("Console failed, does not contain %d addresses", addressCount));
        consoleWebPage.clearAllFilters();
    }

    protected void doTestRegexAlertBehavesConsistently() throws Exception {
        String subText = "*";
        int addressCount = 2;
        ArrayList<Address> addresses = generateQueueTopicList(sharedAddressSpace, "via-web", IntStream.range(0, addressCount));

        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        consoleWebPage.createAddressesWebConsole(addresses.toArray(new Address[0]));

        assertThat(String.format("Console failed, does not contain %d addresses", addressCount),
                consoleWebPage.getAddressItems().size(), is(addressCount));

        consoleWebPage.addAddressesFilter(FilterType.NAME, subText);
        WebElement regexAlert = consoleWebPage.getFilterRegexAlert();
        assertTrue(regexAlert.isDisplayed());
        consoleWebPage.clickOnRegexAlertClose();
        assertFalse(regexAlert.isDisplayed());

        //check on connections tab filter
        consoleWebPage.openConnectionsPageWebConsole();
        consoleWebPage.addConnectionsFilter(FilterType.HOSTNAME, subText);
        regexAlert = consoleWebPage.getFilterRegexAlert();
        assertTrue(regexAlert.isDisplayed());
        consoleWebPage.clickOnRegexAlertClose();
        assertFalse(regexAlert.isDisplayed());
    }

    protected void doTestSortAddressesByName() throws Exception {
        int addressCount = 4;
        ArrayList<Address> addresses = generateQueueTopicList(sharedAddressSpace, "via-web", IntStream.range(0, addressCount));

        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        consoleWebPage.createAddressesWebConsole(addresses.toArray(new Address[0]));

        consoleWebPage.sortItems(SortType.NAME, true);
        assertSorted("Console failed, items are not sorted by name asc", consoleWebPage.getAddressItems());

        consoleWebPage.sortItems(SortType.NAME, false);
        assertSorted("Console failed, items are not sorted by name desc", consoleWebPage.getAddressItems(), true);
    }

    protected void doTestSortAddressesByClients() throws Exception {
        int addressCount = 4;
        ArrayList<Address> addresses = generateQueueTopicList(sharedAddressSpace, "via-web", IntStream.range(0, addressCount));

        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        consoleWebPage.createAddressesWebConsole(addresses.toArray(new Address[0]));
        consoleWebPage.openAddressesPageWebConsole();

        clientsList = attachReceivers(addresses);

        Thread.sleep(15000);

        consoleWebPage.sortItems(SortType.RECEIVERS, true);
        assertSorted("Console failed, items are not sorted by count of receivers asc",
                consoleWebPage.getAddressItems(), Comparator.comparingInt(AddressWebItem::getReceiversCount));

        consoleWebPage.sortItems(SortType.RECEIVERS, false);
        assertSorted("Console failed, items are not sorted by count of receivers desc",
                consoleWebPage.getAddressItems(), true, Comparator.comparingInt(AddressWebItem::getReceiversCount));

        stopClients(clientsList);

        clientsList = attachSenders(addresses);

        Thread.sleep(15000);

        consoleWebPage.sortItems(SortType.SENDERS, true);
        assertSorted("Console failed, items are not sorted by count of senders asc",
                consoleWebPage.getAddressItems(), Comparator.comparingInt(AddressWebItem::getSendersCount));

        consoleWebPage.sortItems(SortType.SENDERS, false);
        assertSorted("Console failed, items are not sorted by count of senders desc",
                consoleWebPage.getAddressItems(), true, Comparator.comparingInt(AddressWebItem::getSendersCount));

        stopClients(clientsList);
    }

    protected void doTestSortConnectionsBySenders() throws Exception {
        int addressCount = 2;
        ArrayList<Address> addresses = generateQueueTopicList(sharedAddressSpace, "via-web", IntStream.range(0, addressCount));

        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        consoleWebPage.createAddressesWebConsole(addresses.toArray(new Address[0]));
        consoleWebPage.openConnectionsPageWebConsole();

        clientsList = attachClients(addresses);

        consoleWebPage.sortItems(SortType.SENDERS, true);
        assertSorted("Console failed, items are not sorted by count of senders asc",
                consoleWebPage.getConnectionItems(6), Comparator.comparingInt(ConnectionWebItem::getSendersCount));

        consoleWebPage.sortItems(SortType.SENDERS, false);
        assertSorted("Console failed, items are not sorted by count of senders desc",
                consoleWebPage.getConnectionItems(6), true, Comparator.comparingInt(ConnectionWebItem::getSendersCount));
    }

    protected void doTestSortConnectionsByReceivers() throws Exception {
        int addressCount = 2;
        ArrayList<Address> addresses = generateQueueTopicList(sharedAddressSpace, "via-web", IntStream.range(0, addressCount));

        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        consoleWebPage.createAddressesWebConsole(addresses.toArray(new Address[0]));
        consoleWebPage.openConnectionsPageWebConsole();

        clientsList = attachClients(addresses);

        consoleWebPage.sortItems(SortType.RECEIVERS, true);
        assertSorted("Console failed, items are not sorted by count of receivers asc",
                consoleWebPage.getConnectionItems(6), Comparator.comparingInt(ConnectionWebItem::getReceiversCount));

        consoleWebPage.sortItems(SortType.RECEIVERS, false);
        assertSorted("Console failed, items are not sorted by count of receivers desc",
                consoleWebPage.getConnectionItems(6), true, Comparator.comparingInt(ConnectionWebItem::getReceiversCount));
    }


    protected void doTestFilterConnectionsByEncrypted() throws Exception {
        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        Address queue = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, "queue-connection-encrypted"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("queue-connection-encrypted")
                .withPlan(getDefaultPlan(AddressType.QUEUE))
                .endSpec()
                .build();
        consoleWebPage.createAddressesWebConsole(queue);
        consoleWebPage.openConnectionsPageWebConsole();

        int receiverCount = 5;
        clientsList = attachReceivers(queue, receiverCount);

        consoleWebPage.addConnectionsFilter(FilterType.ENCRYPTED, "encrypted");
        List<ConnectionWebItem> items = consoleWebPage.getConnectionItems(receiverCount);
        assertThat(String.format("Console failed, does not contain %d connections", receiverCount),
                items.size(), is(receiverCount));
        assertConnectionUnencrypted("Console failed, does not show only Encrypted connections", items);

        consoleWebPage.clearAllFilters();
        assertThat(consoleWebPage.getConnectionItems(receiverCount).size(), is(receiverCount));

        consoleWebPage.addConnectionsFilter(FilterType.ENCRYPTED, "unencrypted");
        items = consoleWebPage.getConnectionItems();
        assertThat(String.format("Console failed, does not contain %d connections", 0),
                items.size(), is(0));
        assertConnectionEncrypted("Console failed, does not show only Encrypted connections", items);
    }

    protected void doTestFilterConnectionsByUser() throws Exception {
        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        Address queue = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, "queue-connection-users"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("queue-connection-users")
                .withPlan(getDefaultPlan(AddressType.QUEUE))
                .endSpec()
                .build();
        consoleWebPage.createAddressesWebConsole(queue);
        consoleWebPage.openConnectionsPageWebConsole();

        UserCredentials pavel = new UserCredentials("pavel", "enmasse");
        createOrUpdateUser(sharedAddressSpace, pavel);
        List<AbstractClient> receiversPavel = null;
        List<AbstractClient> receiversTest = null;
        try {
            int receiversBatch1 = 5;
            int receiversBatch2 = 10;
            receiversPavel = attachReceivers(queue, receiversBatch1, pavel);
            receiversTest = attachReceivers(queue, receiversBatch2);
            assertThat(String.format("Console failed, does not contain %d connections", receiversBatch1 + receiversBatch2),
                    consoleWebPage.getConnectionItems(receiversBatch1 + receiversBatch2).size(), is(receiversBatch1 + receiversBatch2));

            consoleWebPage.addConnectionsFilter(FilterType.USER, defaultCredentials.getUsername());
            List<ConnectionWebItem> items = consoleWebPage.getConnectionItems(receiversBatch2);
            assertThat(String.format("Console failed, does not contain %d connections", receiversBatch2),
                    items.size(), is(receiversBatch2));
            assertConnectionUsers(
                    String.format("Console failed, does not contain connections for user '%s'", defaultCredentials),
                    items, defaultCredentials.getUsername());

            consoleWebPage.addConnectionsFilter(FilterType.USER, pavel.getUsername());
            assertThat(String.format("Console failed, does not contain %d connections", 0),
                    consoleWebPage.getConnectionItems().size(), is(0));

            consoleWebPage.removeFilterByUser(defaultCredentials.getUsername());
            items = consoleWebPage.getConnectionItems(receiversBatch1);
            assertThat(String.format("Console failed, does not contain %d connections", receiversBatch1),
                    items.size(), is(receiversBatch1));
            assertConnectionUsers(
                    String.format("Console failed, does not contain connections for user '%s'", pavel),
                    items, pavel.getUsername());

            consoleWebPage.clearAllFilters();
            assertThat(String.format("Console failed, does not contain %d connections", receiversBatch1 + receiversBatch2),
                    consoleWebPage.getConnectionItems(receiversBatch1 + receiversBatch2).size(), is(receiversBatch1 + receiversBatch2));
        } finally {
            removeUser(sharedAddressSpace, pavel.getUsername());
            stopClients(receiversTest);
            stopClients(receiversPavel);
        }

    }

    protected void doTestFilterConnectionsByHostname() throws Exception {
        int addressCount = 2;
        ArrayList<Address> addresses = generateQueueTopicList(sharedAddressSpace, "via-web", IntStream.range(0, addressCount));
        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        consoleWebPage.createAddressesWebConsole(addresses.toArray(new Address[0]));
        consoleWebPage.openConnectionsPageWebConsole();

        clientsList = attachClients(addresses);

        String hostname = consoleWebPage.getConnectionItems(6).get(0).getName();

        consoleWebPage.addConnectionsFilter(FilterType.HOSTNAME, hostname);
        assertThat(String.format("Console failed, does not contain %d connections", 1),
                consoleWebPage.getConnectionItems(1).size(), is(1));

        consoleWebPage.clearAllFilters();
        assertThat(String.format("Console failed, does not contain %d connections", 6),
                consoleWebPage.getConnectionItems(6).size(), is(6));
    }

    protected void doTestSortConnectionsByHostname() throws Exception {
        int addressCount = 2;
        ArrayList<Address> addresses = generateQueueTopicList(sharedAddressSpace, "via-web", IntStream.range(0, addressCount));
        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        consoleWebPage.createAddressesWebConsole(addresses.toArray(new Address[0]));
        consoleWebPage.openConnectionsPageWebConsole();

        clientsList = attachClients(addresses);

        consoleWebPage.sortItems(SortType.HOSTNAME, true);
        assertSorted("Console failed, items are not sorted by hostname asc",
                consoleWebPage.getConnectionItems(), Comparator.comparing(ConnectionWebItem::getName));

        consoleWebPage.sortItems(SortType.HOSTNAME, false);
        assertSorted("Console failed, items are not sorted by hostname desc",
                consoleWebPage.getConnectionItems(), true, Comparator.comparing(ConnectionWebItem::getName));
    }

    protected void doTestFilterConnectionsByContainerId() throws Exception {
        int connectionCount = 5;

        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        Address dest = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, "queue-via-web"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("queue-via-web")
                .withPlan(getDefaultPlan(AddressType.QUEUE))
                .endSpec()
                .build();
        consoleWebPage.createAddressWebConsole(dest);
        consoleWebPage.openConnectionsPageWebConsole();

        clientsList = new ArrayList<>();
        clientsList.add(attachConnector(dest, connectionCount, 1, 1));
        selenium.waitUntilPropertyPresent(60, connectionCount, () -> consoleWebPage.getConnectionItems().size());

        String containerID = consoleWebPage.getConnectionItems(connectionCount).get(0).getContainerID();

        consoleWebPage.addConnectionsFilter(FilterType.CONTAINER, containerID);
        assertThat(String.format("Console failed, does not contain %d connections", 1),
                consoleWebPage.getConnectionItems(1).size(), is(1));

        consoleWebPage.clearAllFilters();
        assertThat(String.format("Console failed, does not contain %d connections", connectionCount),
                consoleWebPage.getConnectionItems(connectionCount).size(), is(connectionCount));
    }

    protected void doTestSortConnectionsByContainerId() throws Exception {
        int connectionCount = 5;

        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        Address dest = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, "queue-via-web"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("queue-via-web")
                .withPlan(getDefaultPlan(AddressType.QUEUE))
                .endSpec()
                .build();
        consoleWebPage.createAddressWebConsole(dest);
        consoleWebPage.openConnectionsPageWebConsole();

        clientsList = new ArrayList<>();
        clientsList.add(attachConnector(dest, connectionCount, 1, 1));
        selenium.waitUntilPropertyPresent(60, connectionCount, () -> consoleWebPage.getConnectionItems().size());

        consoleWebPage.sortItems(SortType.CONTAINER_ID, true);
        assertSorted("Console failed, items are not sorted by containerID asc",
                consoleWebPage.getConnectionItems(), Comparator.comparing(ConnectionWebItem::getContainerID));

        consoleWebPage.sortItems(SortType.CONTAINER_ID, false);
        assertSorted("Console failed, items are not sorted by containerID desc",
                consoleWebPage.getConnectionItems(), true, Comparator.comparing(ConnectionWebItem::getContainerID));
    }

    protected void doTestMessagesMetrics() throws Exception {
        int msgCount = 19;
        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        Address dest = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, "queue-via-web"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("queue-via-web")
                .withPlan(getDefaultPlan(AddressType.QUEUE))
                .endSpec()
                .build();
        consoleWebPage.createAddressWebConsole(dest);
        consoleWebPage.openAddressesPageWebConsole();

        AmqpClient client = amqpClientFactory.createQueueClient();
        List<String> msgBatch = TestUtils.generateMessages(msgCount);

        int sent = client.sendMessages(dest.getSpec().getAddress(), msgBatch).get(2, TimeUnit.MINUTES);
        selenium.waitUntilPropertyPresent(60, msgCount, () -> consoleWebPage.getAddressItem(dest).getMessagesIn());
        assertEquals(sent, consoleWebPage.getAddressItem(dest).getMessagesIn(),
                String.format("Console failed, does not contain %d messagesIN", sent));

        selenium.waitUntilPropertyPresent(60, msgCount, () -> consoleWebPage.getAddressItem(dest).getMessagesStored());
        assertEquals(msgCount, consoleWebPage.getAddressItem(dest).getMessagesStored(),
                String.format("Console failed, does not contain %d messagesStored", msgCount));

        int received = client.recvMessages(dest.getSpec().getAddress(), msgCount).get(1, TimeUnit.MINUTES).size();
        selenium.waitUntilPropertyPresent(60, msgCount, () -> consoleWebPage.getAddressItem(dest).getMessagesOut());
        assertEquals(received, consoleWebPage.getAddressItem(dest).getMessagesOut(),
                String.format("Console failed, does not contain %d messagesOUT", received));

    }

    protected void doTestClientsMetrics() throws Exception {
        int senderCount = 5;
        int receiverCount = 10;
        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        Address dest = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, "queue-via-web"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("queue-via-web")
                .withPlan(getDefaultPlan(AddressType.QUEUE))
                .endSpec()
                .build();
        consoleWebPage.createAddressWebConsole(dest);
        consoleWebPage.openAddressesPageWebConsole();

        AbstractClient client = new RheaClientConnector();
        try {
            client = attachConnector(dest, 1, senderCount, receiverCount);
            selenium.waitUntilPropertyPresent(60, senderCount, () -> consoleWebPage.getAddressItem(dest).getSendersCount());

            assertAll(
                    () -> assertEquals(10, consoleWebPage.getAddressItem(dest).getReceiversCount(),
                            String.format("Console failed, does not contain %d receivers", 10)),
                    () -> assertEquals(5, consoleWebPage.getAddressItem(dest).getSendersCount(),
                            String.format("Console failed, does not contain %d senders", 5)));
        } finally {
            client.stop();
        }
    }

    protected void doTestCanOpenConsolePage(UserCredentials credentials) throws Exception {
        try {
            consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                    sharedAddressSpace, credentials);
            consoleWebPage.openWebConsolePage();
            consoleWebPage.openAddressesPageWebConsole();
            log.info(String.format("User %s successfully authenticated", credentials));
            consoleWebPage.openAddressesPageWebConsole();
        } catch (IllegalAccessException | org.openqa.selenium.WebDriverException ex) {
            selenium.tearDownDrivers();
            log.info(String.format("User %s can't authenticate", credentials));
            throw new IllegalAccessException();
        }
    }

    protected void doTestWithStrangeAddressNames(boolean hyphen, boolean longName, AddressType... types) throws Exception {
        int assert_value = 1;
        String testString = null;
        Address dest;
        Address dest_topic = null;
        if (hyphen) {
            testString = String.join("-", Collections.nCopies(9, "10charhere"));
        }
        if (longName) {
            testString = String.join("", Collections.nCopies(24, "10charhere"));
        }

        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();

        for (AddressType type : types) {
            if (type == AddressType.SUBSCRIPTION) {
                dest_topic = new AddressBuilder()
                        .withNewMetadata()
                        .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                        .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, "topic-sub" + testString))
                        .endMetadata()
                        .withNewSpec()
                        .withType("topic")
                        .withAddress("topic-sub" + testString)
                        .withPlan(getDefaultPlan(AddressType.TOPIC))
                        .endSpec()
                        .build();
                log.info("Creating topic for subscription");
                consoleWebPage.createAddressWebConsole(dest_topic);
                dest = new AddressBuilder()
                        .withNewMetadata()
                        .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                        .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, testString))
                        .endMetadata()
                        .withNewSpec()
                        .withType("subscription")
                        .withAddress(testString)
                        .withTopic(dest_topic.getSpec().getAddress())
                        .withPlan(DestinationPlan.STANDARD_SMALL_SUBSCRIPTION)
                        .endSpec()
                        .build();
                assert_value = 2;
            } else {
                dest = new AddressBuilder()
                        .withNewMetadata()
                        .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                        .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, type.toString() + "-" + testString))
                        .endMetadata()
                        .withNewSpec()
                        .withType(type.toString())
                        .withAddress(type.toString() + "-" + testString)
                        .withPlan(getDefaultPlan(type))
                        .endSpec()
                        .build();
            }

            consoleWebPage.createAddressWebConsole(dest);
            assertWaitForValue(assert_value, () -> consoleWebPage.getResultsCount(), new TimeoutBudget(120, TimeUnit.SECONDS));

            if (type.equals(AddressType.SUBSCRIPTION)) {
                consoleWebPage.deleteAddressWebConsole(dest_topic);
            }
            consoleWebPage.deleteAddressWebConsole(dest);
            assertWaitForValue(0, () -> consoleWebPage.getResultsCount(), new TimeoutBudget(20, TimeUnit.SECONDS));
        }
    }

    protected void doTestCreateAddressWithSpecialCharsShowsErrorMessage() throws Exception {
        final Supplier<WebElement> webElementSupplier = () -> selenium.getDriver().findElement(By.id("new-name"));
        String testString = "addressname";
        Address destValid = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, testString))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress(testString)
                .withPlan(getDefaultPlan(AddressType.QUEUE))
                .endSpec()
                .build();

        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        consoleWebPage.openAddressesPageWebConsole();
        consoleWebPage.clickOnCreateButton();

        for (char special_char : "#*/.:".toCharArray()) {
            //fill with valid name first
            selenium.fillInputItem(selenium.getWebElement(webElementSupplier), destValid.getSpec().getAddress());
            WebElement helpBlock = selenium.getWebElement(() -> selenium.getDriver().findElement(By.className("help-block")));
            assertTrue(helpBlock.getText().isEmpty());

            //fill with invalid name (including spec_char)
            selenium.fillInputItem(selenium.getWebElement(webElementSupplier), testString + special_char);
            assertTrue(helpBlock.isDisplayed());
        }
    }

    protected void doTestCreateAddressWithSymbolsAt61stCharIndex(Address... destinations) throws Exception {
        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        consoleWebPage.openAddressesPageWebConsole();

        for (Address dest : destinations) {
            consoleWebPage.createAddressWebConsole(dest);
            consoleWebPage.deleteAddressWebConsole(dest);
        }
        assertWaitForValue(0, () -> consoleWebPage.getResultsCount(), new TimeoutBudget(20, TimeUnit.SECONDS));
    }

    protected void doTestAddressWithValidPlanOnly() throws Exception {
        Address destQueue = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, "queue-via-web"))
                .endMetadata()
                .withNewSpec()
                .withType("queue")
                .withAddress("queue-via-web")
                .withPlan(getDefaultPlan(AddressType.QUEUE))
                .endSpec()
                .build();

        Address destTopic = new AddressBuilder()
                .withNewMetadata()
                .withNamespace(sharedAddressSpace.getMetadata().getNamespace())
                .withName(AddressUtils.generateAddressMetadataName(sharedAddressSpace, "topic-via-web"))
                .endMetadata()
                .withNewSpec()
                .withType("topic")
                .withAddress("topic-via-web")
                .withPlan(getDefaultPlan(AddressType.TOPIC))
                .endSpec()
                .build();

        consoleWebPage = new ConsoleWebPage(selenium, getConsoleRoute(sharedAddressSpace),
                sharedAddressSpace, clusterUser);
        consoleWebPage.openWebConsolePage();
        consoleWebPage.openAddressesPageWebConsole();

        // create Queue with default Plan and move to confirmation page
        selenium.clickOnItem(consoleWebPage.getCreateButton(), "clicking on create button");
        final Supplier<WebElement> webElementSupplier = () -> selenium.getDriver().findElement(By.id("new-name"));
        selenium.fillInputItem(selenium.getWebElement(webElementSupplier), destQueue.getSpec().getAddress());
        selenium.clickOnItem(consoleWebPage.getRadioButtonForAddressType(destQueue), "clicking on radio button");
        consoleWebPage.next();
        consoleWebPage.next();

        // go back to page 1 by clicking "number 1"
        consoleWebPage.clickOnAddressModalPageByNumber(1);

        // change details to Topic
        selenium.fillInputItem(selenium.getWebElement(webElementSupplier), destTopic.getSpec().getAddress());
        selenium.clickOnItem(consoleWebPage.getRadioButtonForAddressType(destTopic), "clicking on radio button");

        // skip straight back to page 3 and create address
        consoleWebPage.clickOnAddressModalPageByNumber(3);
        consoleWebPage.next();

        // assert new address is Topic
        assertEquals(AddressType.TOPIC.toString(),
                selenium.waitUntilItemPresent(60, () -> consoleWebPage.getAddressItem(destTopic)).getType(),
                "Console failed, expected TOPIC type");

        waitForDestinationsReady(destTopic);

        assertCanConnect(sharedAddressSpace, defaultCredentials, Collections.singletonList(destTopic));
    }

    protected void doTestOpenConsoleCustomRoute() throws Exception {
        String endpointPrefix = "test-endpoint-";

        AddressSpace addressSpace = new AddressSpaceBuilder()
                .withNewMetadata()
                .withName("standard")
                .withNamespace(kubernetes.getInfraNamespace())
                .endMetadata()
                .withNewSpec()
                .withType(AddressSpaceType.STANDARD.toString())
                .withPlan(AddressSpacePlans.STANDARD_SMALL)
                .withNewAuthenticationService()
                .withName("standard-authservice")
                .endAuthenticationService()

                .addNewEndpoint()
                .withName(endpointPrefix + "console")
                .withService("console")
                .editOrNewExpose()
                .withType(ExposeType.route)
                .withRouteTlsTermination(TlsTermination.reencrypt)
                .withRouteServicePort("https")
                .endExpose()
                .endEndpoint()

                .endSpec()
                .build();
        createAddressSpace(addressSpace);

        //try to get all external endpoints
        kubernetes.getExternalEndpoint(endpointPrefix + "console-" + AddressSpaceUtils.getAddressSpaceInfraUuid(addressSpace));

        ConsoleWebPage console = new ConsoleWebPage(
                selenium,
                getConsoleRoute(addressSpace),
                addressSpace,
                clusterUser);
        console.openWebConsolePage();
        console.openAddressesPageWebConsole();
    }

    //============================================================================================
    //============================ Help methods ==================================================
    //============================================================================================


    private List<AbstractClient> attachClients(List<Address> destinations) throws Exception {
        List<AbstractClient> clients = new ArrayList<>();
        for (Address destination : destinations) {
            clients.add(attachConnector(destination, 1, 6, 1));
            clients.add(attachConnector(destination, 1, 4, 4));
            clients.add(attachConnector(destination, 1, 1, 6));
        }

        Thread.sleep(5000);

        return clients;
    }


    private void assertAddressType(String message, List<AddressWebItem> allItems, AddressType type) {
        assertThat(message, getAddressProperty(allItems, (item -> item.getType().contains(type.toString()))).size(), is(allItems.size()));
    }

    private void assertAddressName(String message, List<AddressWebItem> allItems, String subString) {
        assertThat(message, getAddressProperty(allItems, (item -> item.getName().contains(subString))).size(), is(allItems.size()));
    }

    private void assertConnectionEncrypted(String message, List<ConnectionWebItem> allItems) {
        assertThat(message, getConnectionProperty(allItems, (ConnectionWebItem::isEncrypted)).size(), is(allItems.size()));
    }

    private void assertConnectionUnencrypted(String message, List<ConnectionWebItem> allItems) {
        assertThat(message, getConnectionProperty(allItems, (item -> !item.isEncrypted())).size(), is(allItems.size()));
    }

    private void assertConnectionUsers(String message, List<ConnectionWebItem> allItems, String userName) {
        assertThat(message, getConnectionProperty(allItems, (item -> item.getUser().contains(userName))).size(), is(allItems.size()));
    }

    private List<ConnectionWebItem> getConnectionProperty(List<ConnectionWebItem> allItems, Predicate<ConnectionWebItem> f) {
        return allItems.stream().filter(f).collect(Collectors.toList());
    }

    private List<AddressWebItem> getAddressProperty(List<AddressWebItem> allItems, Predicate<AddressWebItem> f) {
        return allItems.stream().filter(f).collect(Collectors.toList());
    }
}
