package es.mrdino.serverpassword;

import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.regex.Pattern;

/** Drops obsolete password-command records before Log4j sends them to any appender. */
final class PasswordLogProtection implements AutoCloseable {
    private static final Pattern SECRET_COMMAND = Pattern.compile(
            "(?i)(?:^|[\\s>:])/?(?:[a-z0-9_.-]+:)?(?:serverpass\\s+\\S|spass\\s+set\\s+\\S)");
    private final Object context;
    private final Object configuration;
    private final Object filter;
    private final Class<?> filterType;

    private PasswordLogProtection(Object context, Object configuration, Object filter, Class<?> filterType) {
        this.context = context; this.configuration = configuration;
        this.filter = filter; this.filterType = filterType;
    }

    static boolean sensitive(String text) {
        return text != null && SECRET_COMMAND.matcher(text).find();
    }

    static PasswordLogProtection install() {
        try {
            Class<?> manager = Class.forName("org.apache.logging.log4j.LogManager");
            Object context = manager.getMethod("getContext", boolean.class).invoke(null, false);
            Object configuration = context.getClass().getMethod("getConfiguration").invoke(context);
            Class<?> filterType = Class.forName("org.apache.logging.log4j.core.Filter");
            Class<?> resultType = Class.forName("org.apache.logging.log4j.core.Filter$Result");
            Object deny = resultType.getField("DENY").get(null), neutral = resultType.getField("NEUTRAL").get(null);
            Class<?> messageType = Class.forName("org.apache.logging.log4j.message.Message");
            Class<?> eventType = Class.forName("org.apache.logging.log4j.core.LogEvent");
            Object filter = Proxy.newProxyInstance(filterType.getClassLoader(), new Class<?>[]{filterType}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "filter" -> hasSecret(args, messageType, eventType) ? deny : neutral;
                    case "getOnMatch" -> deny;
                    case "getOnMismatch" -> neutral;
                    case "isStarted" -> true;
                    case "isStopped" -> false;
                    case "getState" -> Class.forName("org.apache.logging.log4j.core.LifeCycle$State").getField("STARTED").get(null);
                    case "stop" -> method.getReturnType() == boolean.class ? true : null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "ServerPassword sensitive-command filter";
                    default -> null; // Lifecycle start/stop/initialize.
                };
            });
            configuration.getClass().getMethod("addFilter", filterType).invoke(configuration, filter);
            context.getClass().getMethod("updateLoggers").invoke(context);
            return new PasswordLogProtection(context, configuration, filter, filterType);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("Cannot install sensitive-command log protection.", failure);
        }
    }

    private static boolean hasSecret(Object value, Class<?> messageType, Class<?> eventType) throws ReflectiveOperationException {
        if (value == null) return false;
        if (value instanceof String text) return sensitive(text);
        if (value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++)
                if (hasSecret(Array.get(value, index), messageType, eventType)) return true;
        } else if (messageType.isInstance(value)) {
            return sensitive((String) messageType.getMethod("getFormattedMessage").invoke(value));
        } else if (eventType.isInstance(value)) {
            return hasSecret(eventType.getMethod("getMessage").invoke(value), messageType, eventType);
        }
        return false;
    }

    @Override public void close() {
        try {
            configuration.getClass().getMethod("removeFilter", filterType).invoke(configuration, filter);
            context.getClass().getMethod("updateLoggers").invoke(context);
        } catch (ReflectiveOperationException ignored) {
            // Keeping a restrictive filter is safer than logging a removal failure.
        }
    }
}
