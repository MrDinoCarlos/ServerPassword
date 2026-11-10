package es.mrdino.serverpassword;

import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordLogProtectionTest {
    @Test void actualLog4jBackendDropsPlaintextCommandsBeforeEveryAppender() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        var config = context.getConfiguration();
        List<String> records = new ArrayList<>();
        var appender = new AbstractAppender("ServerPasswordCapture", null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) { records.add(event.getMessage().getFormattedMessage()); }
        };
        appender.start();
        LoggerConfig isolated = new LoggerConfig("password-audit-isolated", Level.ALL, false);
        isolated.addAppender(appender, Level.ALL, null);
        config.addLogger("password-audit-isolated", isolated);
        context.updateLoggers();
        try (var protection = PasswordLogProtection.install()) {
            var logger = LogManager.getLogger("password-audit-isolated");
            String secret = "audit-secret-never-write-42";
            logger.info("{} issued server command: {}", "Probe", "/serverpass " + secret);
            logger.info("{} issued server command: {}", "Probe", "/ServerPassword:SPASS set " + secret);
            logger.info((org.apache.logging.log4j.message.Message) new SimpleMessage("Probe issued server command: /spass set " + secret));
            logger.info("Probe issued server command: /spass\tset\t" + secret);
            logger.info("Probe issued server command: /serverpass");
            logger.info("Probe issued server command: /spass set");
            logger.info("ordinary log remains available");
            assertEquals(3, records.size());
            assertTrue(records.stream().noneMatch(record -> record.contains(secret)));
            assertTrue(records.contains("ordinary log remains available"));
        } finally {
            config.removeLogger("password-audit-isolated");
            context.updateLoggers();
            appender.stop();
        }
    }

    @Test void namespaceAndWhitespaceVariantsAreProtectedButSecretFreeCommandsAreAllowed() {
        for (String command : List.of("/serverpass 0012", "/serverpassword:serverpass Password", "/spass set Text password",
                "/SERVERPASSWORD:SPASS\tSET\tSecret", "Probe issued server command: /spass set Secret"))
            assertTrue(PasswordLogProtection.sensitive(command));
        for (String command : List.of("/serverpass", "/spass set", "/spass settings", "/spass import", "normal server log"))
            assertFalse(PasswordLogProtection.sensitive(command));
    }
}
