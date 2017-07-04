/*
 * Copyright 2017 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.enmasse.address.model.impl;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the status of an address
 */
public class AddressStatus implements io.enmasse.address.model.AddressStatus {
    private boolean isReady = false;
    private List<String> messages = new ArrayList<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AddressStatus status = (AddressStatus) o;

        if (isReady != status.isReady) return false;
        return messages.equals(status.messages);
    }

    @Override
    public int hashCode() {
        int result = (isReady ? 1 : 0);
        result = 31 * result + messages.hashCode();
        return result;
    }

    public AddressStatus(boolean isReady) {
        this.isReady = isReady;
    }

    public AddressStatus(io.enmasse.address.model.AddressStatus other) {
        this.isReady = other.isReady();
        this.messages.addAll(other.getMessages());
    }

    public boolean isReady() {
        return isReady;
    }

    public AddressStatus setReady(boolean isReady) {
        this.isReady = isReady;
        return this;
    }

    public List<String> getMessages() {
        return messages;
    }

    public AddressStatus appendMessage(String message) {
        this.messages.add(message);
        return this;
    }

    public AddressStatus clearMessages() {
        this.messages.clear();
        return this;
    }

    @Override
    public String toString() {
        return String.valueOf(isReady);
    }
}
