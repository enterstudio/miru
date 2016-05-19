package com.jivesoftware.os.miru.plugin.cache;

import com.jivesoftware.os.filer.io.api.StackBuffer;
import java.io.IOException;

/**
 * @author jonathan.colt
 */
public interface MiruPluginCacheProvider {

    CacheKeyValues getKeyValues(String name, int payloadSize, boolean variablePayloadSize, int maxUpdatesBeforeFlush);

    interface CacheKeyValues {

        boolean get(byte[] cacheId, byte[][] keys, IndexKeyValueStream stream, StackBuffer stackBuffer) throws Exception;

        boolean rangeScan(byte[] cacheId, byte[] fromInclusive, byte[] toExclusive, KeyValueStream stream) throws Exception;

        void put(byte[] cacheId,
            byte[][] keys,
            byte[][] values,
            boolean commitOnUpdate,
            boolean fsyncOnCommit,
            StackBuffer stackBuffer) throws Exception;
    }

    interface IndexKeyValueStream {
        boolean stream(int index, byte[] key, byte[] value) throws Exception;
    }

    interface KeyValueStream {
        boolean stream(byte[] key, byte[] value) throws Exception;
    }

    TimestampedCacheKeyValues getTimestampedKeyValues(String name, int payloadSize, boolean variablePayloadSize, int maxUpdatesBeforeFlush);

    interface TimestampedCacheKeyValues {

        boolean get(byte[] cacheId, byte[][] keys, TimestampedIndexKeyValueStream stream, StackBuffer stackBuffer) throws Exception;

        boolean rangeScan(byte[] cacheId, byte[] fromInclusive, byte[] toExclusive, TimestampedKeyValueStream stream) throws Exception;

        boolean put(byte[] cacheId,
            boolean commitOnUpdate,
            boolean fsyncOnCommit,
            ConsumeTimestampedKeyValueStream consume,
            StackBuffer stackBuffer) throws Exception;

        Object lock(byte[] cacheId);
    }

    interface TimestampedIndexKeyValueStream {
        boolean stream(int index, byte[] key, byte[] value, long timestamp) throws Exception;
    }

    interface TimestampedKeyValueStream {
        boolean stream(byte[] key, byte[] value, long timestamp) throws Exception;
    }

    interface ConsumeTimestampedKeyValueStream {
        boolean consume(TimestampedKeyValueStream stream) throws Exception;
    }
}
