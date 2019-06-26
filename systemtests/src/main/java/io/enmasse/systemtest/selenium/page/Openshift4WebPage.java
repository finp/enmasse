/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.selenium.page;


import io.enmasse.systemtest.CustomLogger;
import io.enmasse.systemtest.UserCredentials;
import io.enmasse.systemtest.selenium.SeleniumProvider;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public class Openshift4WebPage implements IWebPage {

    private static Logger log = CustomLogger.getLogger();

    SeleniumProvider selenium;
    String ocRoute;
    UserCredentials credentials;
    OpenshiftLoginWebPage loginPage;

    public Openshift4WebPage(SeleniumProvider selenium, String ocRoute, UserCredentials credentials) {
        this.selenium = selenium;
        this.ocRoute = ocRoute;
        this.credentials = credentials;
        this.loginPage = new OpenshiftLoginWebPage(selenium);
    }

    //================================================================================================
    // Get methods
    //================================================================================================
    private WebElement getNavBar() {
        return selenium.getDriver().findElement(By.id("page-sidebar"));
    }

    private WebElement getNavItem(String name) {
        List<WebElement> navItems = getNavBar().findElements(By.tagName("li"));
        for (WebElement navItem : navItems) {
            String text = navItem.findElement(By.tagName("a")).getText();
            if (name.equals(text)) {
                return navItem;
            }
        }
        return null;
    }

    private WebElement getNamespaceBar() {
        return selenium.getDriver().findElement(By.className("co-namespace-bar"));
    }

    private WebElement getDropdownButton() {
        return getNamespaceBar().findElement(By.className("caret"));
    }

    private WebElement getContentWindow() {
        return selenium.getDriver().findElement(By.id("content"));
    }

    private WebElement getGridOfItems() {
        return getContentWindow().findElement(By.className("co-m-table-grid__body"));
    }

    private List<WebElement> getOperatorRows() {
        return getGridOfItems().findElements(By.xpath("//div[@class='co-m-row']"));
    }

    private String getOperatorName(WebElement operator) {
        return operator.findElement(By.xpath("//h1[@class='co-clusterserviceversion-logo__name__clusterserviceversion']")).getText();
    }

    private WebElement getInstalledOperatorItem(String operatorName) {
        List<WebElement> operators = getOperatorRows();
        for (WebElement operator : operators) {
            if (getOperatorName(operator).toLowerCase().equals(operatorName.toLowerCase())) {
                return operator;
            }
        }
        return null;
    }

    private WebElement getTopMenuResources() {
        return getContentWindow().findElement(By.xpath("//ul[@class='co-m-horizontal-nav__menu-secondary']"));
    }

    private WebElement getTopMenuResourceItem(String name) {
        List<WebElement> elements = getTopMenuResources().findElements(By.tagName("li"));
        for (WebElement element : elements) {
            if (name.replaceAll("\\s", "").toLowerCase()
                    .equals(element.findElement(By.tagName("a")).getText().replaceAll("\\s", "").toLowerCase())) {
                return element;
            }
        }
        return null;
    }

    private WebElement getCreateYamlButton() {
        return getContentWindow().findElement(By.id("yaml-create"));
    }

    private WebElement getSaveChangesButton() {
        return getContentWindow().findElement(By.id("save-changes"));
    }

    //================================================================================================
    // Operations
    //================================================================================================

    public void openOpenshiftPage() throws Exception {
        log.info("Opening openshift web page on route {}", ocRoute);
        selenium.getDriver().get(ocRoute);
        if (waitUntilLoginPage()) {
            selenium.getAngularDriver().waitForAngularRequestsToFinish();
            selenium.takeScreenShot();
            try {
                logout();
            } catch (Exception ex) {
                log.info("User is not logged");
            }
            if (!login())
                throw new IllegalAccessException(loginPage.getAlertMessage());
        }
        selenium.getAngularDriver().waitForAngularRequestsToFinish();
        if (!waitUntilConsolePage()) {
            throw new IllegalStateException("Openshift console not loaded");
        }
    }

    private boolean login() throws Exception {
        return loginPage.login(credentials.getUsername(), credentials.getPassword());
    }

    private void logout() throws Exception {
        WebElement userDropdown = selenium.getDriver().findElement(By.className("navbar-right")).findElement(By.id("user-dropdown"));
        selenium.clickOnItem(userDropdown, "User dropdown navigation");
        WebElement logout = selenium.getDriver().findElement(By.className("navbar-right")).findElement(By.cssSelector("a[ng-href='logout']"));
        selenium.clickOnItem(logout, "Log out");
    }

    private boolean waitUntilLoginPage() {
        try {
            selenium.getDriverWait().withTimeout(Duration.ofSeconds(3)).until(ExpectedConditions.titleContains("Login"));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean waitUntilConsolePage() {
        try {
            selenium.getDriverWait().until(ExpectedConditions.visibilityOfElementLocated(By.id("app")));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public void openInstalledOperators() {
        selenium.clickOnItem(getNavItem("Catalog"), "Catalog");
        selenium.clickOnItem(getNavItem("Installed Operators"), "Installed Operators");
    }

    public void selectNamespaceFromBar(String namespace) {
        selenium.clickOnItem(getDropdownButton(), "Namespace bar");
        WebElement list = getNamespaceBar().findElement(By.className("co-namespace-selector__menu"));
        selenium.clickOnItem(list.findElement(By.id(namespace + "-link")));
    }

    public void selectOperator(String operatorName) {
        WebElement operator = getInstalledOperatorItem(operatorName);
        selenium.clickOnItem(Objects.requireNonNull(operator).findElement(By.xpath("//h1[@class='co-clusterserviceversion-logo__name__clusterserviceversion']")));
    }

    public void selectTopMenuResourceItem(String name) {
        selenium.clickOnItem(getTopMenuResourceItem(name), name);
    }

    public void createExampleResourceItem(String resourceName) {
        selectTopMenuResourceItem(resourceName);
        selenium.clickOnItem(getCreateYamlButton());
        selenium.clickOnItem(getSaveChangesButton());
    }

    public void createCustomResourceItem(String resourceName, String data) {
        selectTopMenuResourceItem(resourceName);
        selenium.clickOnItem(getCreateYamlButton());
        fillCustomResource(data);
        selenium.clickOnItem(getSaveChangesButton());
    }

    public void fillCustomResource(String data) {
        selenium.fillInputItem(getContentWindow().findElement(By.className("yaml-editor")).findElement(By.className("ace_text-input")), data);
    }

    @Override
    public void checkReachableWebPage() {
        selenium.getDriverWait().withTimeout(Duration.ofSeconds(60)).until(ExpectedConditions.presenceOfElementLocated(By.id("app")));
    }
}
