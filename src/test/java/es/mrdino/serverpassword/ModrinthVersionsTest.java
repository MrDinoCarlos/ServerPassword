package es.mrdino.serverpassword;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModrinthVersionsTest {
    @Test void sortsVersionsNumericallyAndHandlesTheExistingProjectNaming() {
        assertTrue(ModrinthVersions.compare("1.0.10", "1.0.2") > 0);
        assertTrue(ModrinthVersions.compare("1.2X-0.0.4-BETA-26X", "1.0.0") < 0);
        assertEquals(0, ModrinthVersions.compare("1.0.0-26.3.X", "1.0.0"));
        assertTrue(ModrinthVersions.compare("1.0.0", "1.0.0-beta.2") > 0);
        assertTrue(ModrinthVersions.compare("1.0.0-beta.10", "1.0.0-beta.2") > 0);
    }

    @Test void findsTheNewestCompatibleVersionRatherThanTheFirstEntry() {
        var versions = JsonParser.parseString("""
                [
                  {"id":"wronggame","version_number":"9.0.0","game_versions":["26.3"],"loaders":["paper"]},
                  {"id":"wrongloader","version_number":"8.0.0","game_versions":["1.20.1"],"loaders":["fabric"]},
                  {"id":"older","version_number":"1.0.2","game_versions":["1.20.1"],"loaders":["spigot"]},
                  {"id":"newest","version_number":"1.0.10","game_versions":["1.20.1"],"loaders":["paper"]}
                ]
                """).getAsJsonArray();
        var update = ModrinthVersions.newerCompatible(versions, "1.0.0", "1.20.1", Set.of("paper", "spigot"));
        assertEquals("1.0.10", update.orElseThrow().version());
        assertTrue(update.orElseThrow().url().endsWith("/newest"));
    }

    @Test void olderEqualAndMalformedEntriesDoNotProduceAnUpdate() {
        var versions = JsonParser.parseString("""
                [
                  {"id":"old","version_number":"0.0.4-BETA","game_versions":["1.21.7"],"loaders":["paper"]},
                  {"id":"same","version_number":"1.0.0-1.21.X","game_versions":["1.21.7"],"loaders":["paper"]},
                  {"id":"bad"}
                ]
                """).getAsJsonArray();
        assertTrue(ModrinthVersions.newerCompatible(versions, "1.0.0", "1.21.7", Set.of("paper")).isEmpty());
    }
}
