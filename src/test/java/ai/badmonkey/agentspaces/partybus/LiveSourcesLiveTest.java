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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.net.http.HttpClient;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scouts' structured answers from a real model, opt-in: runs only with
 * {@code AGENTSPACES_LIVE_MODELS=1} and {@code OPENAI_API_KEY} set, and never
 * in the default build (which replays recorded sources).
 */
@EnabledIfEnvironmentVariable(named = "AGENTSPACES_LIVE_MODELS", matches = "1")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class LiveSourcesLiveTest {

    @Test
    @Timeout(120)
    void aRealModelFillsAPartyBusRecord() {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder().apiKey(System.getenv("OPENAI_API_KEY"))
                        .model(System.getenv().getOrDefault(LiveSources.OPENAI_MODEL, "gpt-4.1-mini"))
                        .temperature(0.2).build())
                .build();
        LiveSources sources = new LiveSources(HttpClient.newHttpClient(), ChatClient.create(model),
                "unused-brave-key", Optional.empty());

        PointOfInterest sight = sources.structured("live-sight",
                "Name one famous sight in Lisbon, Portugal, with a one-sentence description.", PointOfInterest.class);

        assertThat(sight.name()).isNotBlank();
        assertThat(sight.description()).isNotBlank();
    }
}
