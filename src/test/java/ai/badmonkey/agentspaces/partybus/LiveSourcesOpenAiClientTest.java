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
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scouts' structured answers through Spring AI's real OpenAI client, the client a live run builds. A local
 * stand-in for the chat completions API answers in place of the hosted service, so the test needs no key and runs
 * in every build.
 */
class LiveSourcesOpenAiClientTest {

    @Test
    @Timeout(120)
    void theOpenAiClientFillsAPartyBusRecord() throws IOException {
        AtomicReference<String> request = new AtomicReference<>();
        String record = "{\"name\":\"Belém Tower\",\"description\":\"A fortified tower on the Tagus\","
                + "\"location\":\"Lisbon\",\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-01\"}";
        String reply = "{\"id\":\"c-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-4.1-mini\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                + record.replace("\"", "\\\"") + "\",\"refusal\":null},\"logprobs\":null,\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":40,\"completion_tokens\":30,\"total_tokens\":70}}";
        HttpServer api = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        api.createContext("/v1/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        api.start();
        try {
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .options(OpenAiChatOptions.builder().baseUrl("http://127.0.0.1:" + api.getAddress().getPort() + "/v1")
                            .apiKey("test-key-not-a-secret").model("gpt-4.1-mini").temperature(0.2).maxRetries(0).build())
                    .build();
            LiveSources sources = new LiveSources(HttpClient.newHttpClient(), ChatClient.create(model),
                    "unused-brave-key", Optional.empty());

            PointOfInterest sight = sources.structured("live-sight",
                    "Name one famous sight in Lisbon, Portugal, with a one-sentence description.", PointOfInterest.class);

            assertThat(sight.name()).isEqualTo("Belém Tower");
            assertThat(sight.location()).isEqualTo("Lisbon");
            assertThat(request.get()).contains("Name one famous sight in Lisbon").contains("gpt-4.1-mini")
                    .as("Spring AI asked for the record's fields").contains("fromDate");
        } finally {
            api.stop(0);
        }
    }
}
