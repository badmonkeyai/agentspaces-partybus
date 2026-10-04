/*
 * Copyright 2026 Bad Monkey, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.badmonkey.agentspaces.partybus;

import ai.badmonkey.agentspaces.partybus.domain.Trip.PointOfInterest;
import ai.badmonkey.agentspaces.partybus.sources.LiveSources;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scouts' structured answers come from a Spring AI ChatClient: the prompt
 * carries the type's format instructions, and the answer binds into the record.
 */
class LiveSourcesChatClientTest {

    @Test
    void aStructuredAnswerBindsIntoTheRequestedRecord() {
        AtomicReference<Prompt> seen = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                seen.set(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"name\":\"Belém Tower\",\"description\":\"A fortified tower\","
                                + "\"location\":\"Lisbon\",\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-01\"}"))));
            }
        };
        LiveSources sources = new LiveSources(HttpClient.newHttpClient(), ChatClient.create(model), "brave-key",
                Optional.empty());

        PointOfInterest sight = sources.structured("sights", "Name one sight in Lisbon", PointOfInterest.class);

        assertThat(sight.name()).isEqualTo("Belém Tower");
        assertThat(sight.location()).isEqualTo("Lisbon");
        assertThat(seen.get().getContents()).contains("Name one sight in Lisbon")
                .as("Spring AI added the record's format").contains("fromDate");
    }
}
