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

import ai.badmonkey.agentspaces.partybus.sources.Sources;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Port blocks for the fleet tests.
 *
 * <p>The tests used to take a block that starts at an ephemeral port the OS just
 * handed out. The OS hands out the ports of that range to the outgoing connections
 * of every process on the host, the bus's own seats dialing the turret and any other
 * test JVM, so a seat sometimes found its port already taken between the probe and
 * its bind ("cannot seat scouts-b", "cannot seat concierge": BindException, two runs
 * of twenty-one on 2026-11-15 with other builds running). A block below the ephemeral range
 * (49152 and up on macOS, 32768 and up on Linux) is never handed to an outgoing
 * connection. A listener of another process can still take a port between the probe
 * and the bind, so boarding retries on a fresh block when a seat cannot bind.
 */
final class TestPorts {

    private static final int LOWEST = 20000;
    private static final int HIGHEST = 32000;
    private static final int BOARDING_ATTEMPTS = 5;

    private TestPorts() {
    }

    /** A base port with {@code size} consecutive ports free after it, all below the ephemeral range. */
    static int freeBlock(int size) throws IOException {
        for (int attempt = 0; attempt < 200; attempt++) {
            int base = ThreadLocalRandom.current().nextInt(LOWEST, HIGHEST - size);
            if (free(base, size)) {
                return base;
            }
        }
        throw new IOException("no free block of " + size + " ports in " + LOWEST + ".." + HIGHEST);
    }

    /** Boards the bus on a free block, again on a fresh block if a seat could not bind its port. */
    static PartyBus.Seating board(Sources sources) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return PartyBus.board(sources, freeBlock(40), 0);
            } catch (IllegalStateException e) {
                if (attempt >= BOARDING_ATTEMPTS || !(rootCause(e) instanceof BindException)) {
                    throw e;
                }
                System.out.println("boarding attempt " + attempt + " lost a port (" + e.getMessage()
                        + "); boarding again on a fresh block");
            }
        }
    }

    private static boolean free(int base, int size) {
        for (int port = base; port < base + size; port++) {
            try (ServerSocket probe = new ServerSocket(port)) {
                probe.setReuseAddress(true);
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }
}
