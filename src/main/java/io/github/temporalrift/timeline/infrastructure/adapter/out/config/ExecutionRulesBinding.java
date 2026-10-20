package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.validation.Validator;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import io.github.temporalrift.timeline.domain.execution.InvalidExecutionRulesException;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRules;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRulesValidator;

/**
 * Binds one rules type from an execution context's flat properties with the binding and validation the deployment's
 * own configuration gets, so a context's rules are held to the same bar.
 */
public final class ExecutionRulesBinding<T> {

    private final Class<T> type;
    private final String prefix;
    private final SpringValidatorAdapter validator;

    public ExecutionRulesBinding(Class<T> type, String prefix, Validator validator) {
        this.type = type;
        this.prefix = prefix;
        this.validator = new SpringValidatorAdapter(validator);
    }

    /**
     * @throws InvalidExecutionRulesException naming the rule that is missing or invalid
     */
    public T bind(Map<String, String> properties) {
        var binder = new Binder(new MapConfigurationPropertySource(properties));
        try {
            return binder.bindOrCreate(prefix, Bindable.of(type), new ValidationBindHandler(validator));
        } catch (BindException e) {
            throw invalid(e);
        }
    }

    public ExecutionRulesValidator validator() {
        return this::bind;
    }

    /**
     * Serves {@code port} from the open context's rules, falling back to {@code deployment} while no context is open.
     * The bound rules are kept for as long as the context's digest is unchanged.
     */
    public static <P> P servedFrom(
            Class<P> port, ExecutionRules active, ExecutionRulesBinding<? extends P> binding, P deployment) {
        var served = new AtomicReference<Served<P>>();
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> port.getSimpleName() + " served from the open execution context";
                };
            }
            try {
                return method.invoke(current(active, binding, deployment, served), args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return port.cast(Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[] {port}, handler));
    }

    private static <P> P current(
            ExecutionRules active,
            ExecutionRulesBinding<? extends P> binding,
            P deployment,
            AtomicReference<Served<P>> served) {
        Optional<ExecutionRules.Rules> rules = active.current();
        if (rules.isEmpty()) {
            return deployment;
        }
        var digest = rules.get().digest();
        var cached = served.get();
        if (cached != null && cached.digest().equals(digest)) {
            return cached.rules();
        }
        P bound = binding.bind(rules.get().properties());
        served.set(new Served<>(digest, bound));
        return bound;
    }

    private InvalidExecutionRulesException invalid(BindException failure) {
        if (failure.getCause() instanceof BindValidationException validation) {
            var errors = validation.getValidationErrors();
            var first = errors.getAllErrors().getFirst();
            var key = first instanceof FieldError field
                    ? errors.getName() + "." + kebab(field.getField())
                    : errors.getName().toString();
            return new InvalidExecutionRulesException(key, first.getDefaultMessage(), failure);
        }
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        var reason = root.getMessage() == null ? "is missing or invalid" : root.getMessage();
        return new InvalidExecutionRulesException(failure.getName().toString(), reason, failure);
    }

    /** {@code actionRoundTimerSeconds[3]} to {@code action-round-timer-seconds.3}; map keys keep their spelling. */
    private static String kebab(String field) {
        var kebab = new StringBuilder();
        var inKey = false;
        for (var character : field.toCharArray()) {
            if (character == '[') {
                inKey = true;
                kebab.append('.');
            } else if (character == ']') {
                inKey = false;
            } else if (!inKey && Character.isUpperCase(character)) {
                kebab.append('-').append(Character.toLowerCase(character));
            } else {
                kebab.append(character);
            }
        }
        return kebab.toString();
    }

    private record Served<P>(String digest, P rules) {}
}
