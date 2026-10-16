package io.github.temporalrift.timeline.infrastructure.adapter.in.inmemory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import io.github.temporalrift.timeline.domain.execution.SourceWatermark;

/**
 * The simulation host's way into the {@code @KafkaListener} consumers when there is no broker. A delivered record is
 * handed to every listener subscribed to its topic — each consumer group once — through the same beans, and so the
 * same transactional proxies, the Kafka container would call.
 *
 * <p>Records with the same key are dispatched one at a time in arrival order, and a call returns only after the
 * handlers' transactions have committed, which is the guarantee a partition gives a consumer group. A handler failure
 * propagates to the caller after the other groups have run; a simulation has no retry or dead-letter step.
 */
public final class InMemoryEventDelivery implements Consumer<Map<String, Object>> {

    private static final String BASE_PACKAGE = "io.github.temporalrift.";
    private static final int PARTITION = 0;
    private static final int LOCK_STRIPES = 64;

    private final ApplicationContext context;
    private final ReentrantLock[] keyLocks = fairLocks();
    private final ConcurrentMap<Subscription, AtomicLong> handled = new ConcurrentHashMap<>();
    private final AtomicReference<List<Listener>> listeners = new AtomicReference<>();

    public InMemoryEventDelivery(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void accept(Map<String, Object> event) {
        var topic = required(event, "topic", String.class);
        var key = required(event, "key", String.class);
        var subscribed = listeners().stream()
                .filter(listener -> listener.topics().contains(topic))
                .toList();
        if (subscribed.isEmpty()) {
            return;
        }
        var message = toMessage(event);
        var lock = keyLocks[Math.floorMod(key.hashCode(), keyLocks.length)];
        lock.lock();
        try {
            dispatch(subscribed, topic, message);
        } finally {
            lock.unlock();
        }
    }

    /** How many records each consumer group has handled on each topic it consumes. */
    public List<SourceWatermark> watermarks() {
        return listeners().stream()
                .flatMap(listener -> listener.topics().stream()
                        .map(topic -> new SourceWatermark(
                                listener.groupId(),
                                topic,
                                PARTITION,
                                handledCount(new Subscription(listener.groupId(), topic)))))
                .sorted(Comparator.comparing(SourceWatermark::groupId)
                        .thenComparing(SourceWatermark::topic)
                        .thenComparingInt(SourceWatermark::partition))
                .toList();
    }

    private void dispatch(List<Listener> subscribed, String topic, Message<Object> message) {
        RuntimeException failure = null;
        for (var listener : subscribed) {
            try {
                listener.invoke(message);
                handled.computeIfAbsent(new Subscription(listener.groupId(), topic), ignored -> new AtomicLong())
                        .incrementAndGet();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private long handledCount(Subscription subscription) {
        var count = handled.get(subscription);
        return count == null ? 0 : count.get();
    }

    /** What the Kafka container hands a raw {@code Message<Object>} listener: the headers and the value bytes. */
    @SuppressWarnings("unchecked")
    private static Message<Object> toMessage(Map<String, Object> event) {
        var payload = required(event, "payload", byte[].class);
        var headers = required(event, "headers", Map.class);
        return MessageBuilder.<Object>withPayload(payload).copyHeaders(headers).build();
    }

    private static <T> T required(Map<String, Object> event, String name, Class<T> type) {
        var value = event.get(name);
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(
                    "A delivered record needs '" + name + "' of type " + type.getSimpleName() + ", got " + value);
        }
        return type.cast(value);
    }

    private List<Listener> listeners() {
        var found = listeners.get();
        if (found == null) {
            synchronized (this) {
                found = listeners.get();
                if (found == null) {
                    found = discover();
                    listeners.set(found);
                }
            }
        }
        return found;
    }

    /** Fair stripes keep records of one key strictly in arrival order while bounding the locks held per instance. */
    private static ReentrantLock[] fairLocks() {
        var locks = new ReentrantLock[LOCK_STRIPES];
        for (var i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock(true);
        }
        return locks;
    }

    private List<Listener> discover() {
        var found = new ArrayList<Listener>();
        for (var beanName : context.getBeanNamesForType(Object.class, false, false)) {
            var type = context.getType(beanName);
            if (type == null || !ClassUtils.getUserClass(type).getName().startsWith(BASE_PACKAGE)) {
                continue;
            }
            var methods = MethodIntrospector.selectMethods(
                    ClassUtils.getUserClass(type), (MethodIntrospector.MetadataLookup<KafkaListener>)
                            method -> AnnotatedElementUtils.findMergedAnnotation(method, KafkaListener.class));
            methods.forEach((method, annotation) -> found.add(new Listener(
                    context.getBean(beanName),
                    method,
                    Set.copyOf(Arrays.asList(annotation.topics())),
                    annotation.groupId())));
        }
        found.sort(Comparator.comparing(Listener::groupId));
        return List.copyOf(found);
    }

    private record Subscription(String groupId, String topic) {}

    private record Listener(Object bean, Method method, Set<String> topics, String groupId) {

        private Listener {
            Objects.requireNonNull(bean, "bean");
            if (method.getParameterCount() != 1 || !Message.class.isAssignableFrom(method.getParameterTypes()[0])) {
                throw new IllegalStateException(
                        "A @KafkaListener method must take a single Message, but " + method + " does not");
            }
            ReflectionUtils.makeAccessible(method);
        }

        void invoke(Message<Object> message) {
            try {
                method.invoke(bean, message);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (e.getCause() instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("Listener " + method + " failed", e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot invoke listener " + method, e);
            }
        }
    }
}
