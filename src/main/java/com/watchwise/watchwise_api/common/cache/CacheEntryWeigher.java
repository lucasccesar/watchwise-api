package com.watchwise.watchwise_api.common.cache;

import org.springframework.http.MediaType;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Estimates retained cache memory structurally; it does not measure JVM object layout. */
public final class CacheEntryWeigher {
    private static final long ENTRY_OVERHEAD = 64;
    private static final long REFERENCE_BYTES = 8;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 10_000;
    private static final String CACHE_PACKAGE = "com.watchwise.watchwise_api.common.cache";
    private static final String TMDB_PACKAGE = "com.watchwise.watchwise_api.common.tmdb";
    private static final Set<Class<?>> SCALAR_TYPES = Set.of(
            Boolean.class, Byte.class, Short.class, Integer.class, Long.class,
            Float.class, Double.class, Character.class);
    private static final Set<Class<?>> TIME_TYPES = Set.of(
            Instant.class, LocalDate.class, LocalDateTime.class, LocalTime.class,
            OffsetDateTime.class, OffsetTime.class, ZonedDateTime.class, Duration.class,
            Period.class, Year.class, YearMonth.class, MonthDay.class, ZoneOffset.class);

    private static final ClassValue<RecordModel> RECORD_MODELS = new ClassValue<>() {
        @Override
        protected RecordModel computeValue(Class<?> type) {
            if (!type.isRecord() || !isTrustedRecord(type)) return RecordModel.UNSUPPORTED;
            try {
                RecordComponent[] components = type.getRecordComponents();
                Method[] accessors = new Method[components.length];
                for (int index = 0; index < components.length; index++) {
                    Method accessor = components[index].getAccessor();
                    if (!accessor.trySetAccessible()) {
                        return RecordModel.UNSUPPORTED;
                    }
                    accessors[index] = accessor;
                }
                return new RecordModel(accessors);
            } catch (RuntimeException exception) {
                return RecordModel.UNSUPPORTED;
            }
        }
    };

    /** Returns a positive weight; a value greater than the budget makes Caffeine reject retention. */
    public int weight(Object key, Object value, long cacheBudget) {
        if (cacheBudget <= 0 || cacheBudget >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Cache budget must be positive and below Integer.MAX_VALUE");
        }
        long rejectionWeight = cacheBudget + 1;
        State state = new State(rejectionWeight);
        long total = ENTRY_OVERHEAD;
        total = add(total, estimate(key, 0, state), rejectionWeight);
        total = add(total, estimate(value, 0, state), rejectionWeight);
        return (int) Math.min(total, rejectionWeight);
    }

    private long estimate(Object value, int depth, State state) {
        if (value == null) return REFERENCE_BYTES;
        if (++state.nodes > MAX_NODES || depth > MAX_DEPTH) return state.rejectionWeight;

        Class<?> type = value.getClass();
        if (type == String.class) return stringWeight((String) value, state.rejectionWeight);
        if (type == byte[].class) return arrayWeight(((byte[]) value).length, 1, 16, state.rejectionWeight);
        if (type.isArray()) return estimateArray(value, type.getComponentType(), depth, state);
        if (type.isEnum() || SCALAR_TYPES.contains(type) || TIME_TYPES.contains(type) || type == UUID.class) {
            return Math.min(32, state.rejectionWeight);
        }
        if (value instanceof Optional<?> optional) return estimateOptional(optional, depth, state);
        if (value instanceof MediaType mediaType) return estimateMediaType(mediaType, depth, state);
        if (value instanceof List<?> list) return estimateList(list, depth, state);
        if (value instanceof Map<?, ?> map) return estimateMap(map, depth, state);
        if (type.isRecord()) return estimateRecord(value, type, depth, state);
        return state.rejectionWeight;
    }

    private long estimateArray(Object value, Class<?> componentType, int depth, State state) {
        int length = Array.getLength(value);
        if (componentType.isPrimitive()) {
            int width = componentType == boolean.class || componentType == byte.class ? 1
                    : componentType == char.class || componentType == short.class ? 2
                    : componentType == int.class || componentType == float.class ? 4 : 8;
            return arrayWeight(length, width, 16, state.rejectionWeight);
        }
        long total = arrayWeight(length, REFERENCE_BYTES, 16, state.rejectionWeight);
        if (total >= state.rejectionWeight) return state.rejectionWeight;
        if (!enter(value, state)) return state.rejectionWeight;
        try {
            for (int index = 0; index < length; index++) {
                total = add(total, estimate(Array.get(value, index), depth + 1, state), state.rejectionWeight);
                if (total >= state.rejectionWeight) return state.rejectionWeight;
            }
            return total;
        } finally {
            state.active.remove(value);
        }
    }

    private long estimateList(List<?> list, int depth, State state) {
        if (!isKnownList(list)) return state.rejectionWeight;
        long total = collectionHeader(40, list.size(), REFERENCE_BYTES, state.rejectionWeight);
        if (total >= state.rejectionWeight) return state.rejectionWeight;
        if (!enter(list, state)) return state.rejectionWeight;
        try {
            for (Object element : list) {
                total = add(total, estimate(element, depth + 1, state), state.rejectionWeight);
                if (total >= state.rejectionWeight) return state.rejectionWeight;
            }
            return total;
        } catch (RuntimeException exception) {
            return state.rejectionWeight;
        } finally {
            state.active.remove(list);
        }
    }

    private long estimateMap(Map<?, ?> map, int depth, State state) {
        if (!isKnownMap(map)) return state.rejectionWeight;
        long total = collectionHeader(64, map.size(), 48, state.rejectionWeight);
        if (total >= state.rejectionWeight) return state.rejectionWeight;
        if (!enter(map, state)) return state.rejectionWeight;
        try {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                total = add(total, estimate(entry.getKey(), depth + 1, state), state.rejectionWeight);
                total = add(total, estimate(entry.getValue(), depth + 1, state), state.rejectionWeight);
                if (total >= state.rejectionWeight) return state.rejectionWeight;
            }
            return total;
        } catch (RuntimeException exception) {
            return state.rejectionWeight;
        } finally {
            state.active.remove(map);
        }
    }

    private long estimateOptional(Optional<?> optional, int depth, State state) {
        long total = Math.min(24, state.rejectionWeight);
        if (optional.isEmpty()) return total;
        if (!enter(optional, state)) return state.rejectionWeight;
        try {
            return add(total, estimate(optional.get(), depth + 1, state), state.rejectionWeight);
        } finally {
            state.active.remove(optional);
        }
    }

    private long estimateMediaType(MediaType mediaType, int depth, State state) {
        long total = Math.min(64, state.rejectionWeight);
        total = add(total, estimate(mediaType.getType(), depth + 1, state), state.rejectionWeight);
        total = add(total, estimate(mediaType.getSubtype(), depth + 1, state), state.rejectionWeight);
        total = add(total, estimate(mediaType.getParameters(), depth + 1, state), state.rejectionWeight);
        return total;
    }

    private long estimateRecord(Object value, Class<?> type, int depth, State state) {
        RecordModel model = RECORD_MODELS.get(type);
        if (model == RecordModel.UNSUPPORTED) return state.rejectionWeight;
        long total = collectionHeader(32, model.accessors.length, REFERENCE_BYTES, state.rejectionWeight);
        if (total >= state.rejectionWeight) return state.rejectionWeight;
        if (!enter(value, state)) return state.rejectionWeight;
        try {
            for (Method accessor : model.accessors) {
                Object component;
                try {
                    component = accessor.invoke(value);
                } catch (IllegalAccessException | InvocationTargetException | RuntimeException exception) {
                    return state.rejectionWeight;
                }
                total = add(total, estimate(component, depth + 1, state), state.rejectionWeight);
                if (total >= state.rejectionWeight) return state.rejectionWeight;
            }
            return total;
        } finally {
            state.active.remove(value);
        }
    }

    private static boolean isTrustedRecord(Class<?> type) {
        String packageName = type.getPackageName();
        return CACHE_PACKAGE.equals(packageName) || TMDB_PACKAGE.equals(packageName);
    }

    private static boolean isKnownList(List<?> list) {
        return list instanceof ArrayList<?> || list instanceof LinkedList<?> || list instanceof Vector<?> 
                || list instanceof CopyOnWriteArrayList<?> || list instanceof java.util.RandomAccess && list.getClass().getName().startsWith("java.util.ImmutableCollections$")
                || list.getClass().getName().startsWith("java.util.Collections$")
                || list.getClass().getName().equals("java.util.Arrays$ArrayList");
    }

    private static boolean isKnownMap(Map<?, ?> map) {
        return map instanceof HashMap<?, ?> || map instanceof LinkedHashMap<?, ?>
                || map instanceof TreeMap<?, ?> || map instanceof ConcurrentHashMap<?, ?>
                || map.getClass().getName().startsWith("java.util.ImmutableCollections$")
                || map.getClass().getName().startsWith("java.util.Collections$");
    }

    private static long stringWeight(String value, long cap) {
        return alignAndCap(48L + 2L * value.length(), cap);
    }

    private static long arrayWeight(int length, long elementBytes, long headerBytes, long cap) {
        return alignAndCap(headerBytes + elementBytes * length, cap);
    }

    private static long collectionHeader(long header, int size, long bytesPerEntry, long cap) {
        return alignAndCap(header + bytesPerEntry * size, cap);
    }

    private static long alignAndCap(long size, long cap) {
        long aligned = (size + 7L) & ~7L;
        return Math.min(aligned, cap);
    }

    private static long add(long left, long right, long cap) {
        if (left >= cap || right >= cap || left > cap - right) return cap;
        return left + right;
    }

    /* State is thread-confined; this helper keeps the cap explicit without global mutable state. */
    private static boolean enter(Object value, State state) {
        return state.active.put(value, Boolean.TRUE) == null;
    }

    private static final class State {
        private final long rejectionWeight;
        private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
        private int nodes;

        private State(long rejectionWeight) {
            this.rejectionWeight = rejectionWeight;
        }
    }

    private static final class RecordModel {
        private static final RecordModel UNSUPPORTED = new RecordModel(new Method[0]);
        private final Method[] accessors;

        private RecordModel(Method[] accessors) {
            this.accessors = accessors;
        }
    }
}
