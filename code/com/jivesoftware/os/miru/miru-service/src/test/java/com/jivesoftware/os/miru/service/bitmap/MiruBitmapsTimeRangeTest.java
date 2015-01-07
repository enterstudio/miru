package com.jivesoftware.os.miru.service.bitmap;

import com.googlecode.javaewah.EWAHCompressedBitmap;
import com.jivesoftware.os.miru.api.MiruHost;
import com.jivesoftware.os.miru.api.MiruPartitionCoord;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.plugin.bitmap.MiruBitmaps;
import com.jivesoftware.os.miru.plugin.bitmap.MiruIntIterator;
import com.jivesoftware.os.miru.plugin.index.MiruTimeIndex;
import com.jivesoftware.os.miru.service.index.BulkExport;
import com.jivesoftware.os.miru.service.index.BulkImport;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static com.jivesoftware.os.miru.service.IndexTestUtil.buildHybridContextAllocator;
import static com.jivesoftware.os.miru.service.IndexTestUtil.buildOnDiskContextAllocator;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

/**
 *
 */
public class MiruBitmapsTimeRangeTest {

    private final int numberOfChunkStores = 4;

    @Test(dataProvider = "evenTimeIndexDataProvider")
    public <BM> void testBuildEvenTimeRangeMask(MiruBitmaps<BM> bitmaps, MiruTimeIndex miruTimeIndex) throws Exception {
        final int size = (EWAHCompressedBitmap.WORD_IN_BITS * 3) + 1;
        for (int lower = 0; lower <= size / 2; lower++) {
            int upper = size - 1 - lower;

            BM bitmap = bitmaps.buildTimeRangeMask(miruTimeIndex, lower, upper);
            if (lower == 0) {
                // 0 case is inclusive due to ambiguity
                assertExpectedNumberOfConsecutiveBitsStartingFromN(bitmaps, bitmap, lower, size - 2 * lower);
            } else if (lower == upper) {
                // the lower and upper are the same so there should be nothing left
                assertExpectedNumberOfConsecutiveBitsStartingFromN(bitmaps, bitmap, -1, 0);
            } else {
                assertExpectedNumberOfConsecutiveBitsStartingFromN(bitmaps, bitmap, lower + 1, size - 1 - 2 * lower);
            }
        }
    }

    @Test(dataProvider = "oddTimeIndexDataProvider")
    public <BM> void testBuildOddTimeRangeMask(MiruBitmaps<BM> bitmaps, MiruTimeIndex miruTimeIndex) throws Exception {
        final int size = EWAHCompressedBitmap.WORD_IN_BITS * 3;
        for (int lower = 0; lower < size / 2; lower++) {
            int upper = size - 1 - lower;

            BM bitmap = bitmaps.buildTimeRangeMask(miruTimeIndex, lower, upper);
            if (lower == 0) {
                // 0 case is inclusive due to ambiguity
                assertExpectedNumberOfConsecutiveBitsStartingFromN(bitmaps, bitmap, lower, size - 2 * lower);
            } else if (lower == upper) {
                fail();
            } else {
                assertExpectedNumberOfConsecutiveBitsStartingFromN(bitmaps, bitmap, lower + 1, size - 1 - 2 * lower);
            }
        }
    }

    @Test(dataProvider = "singleEntryTimeIndexDataProvider")
    public <BM> void testSingleBitTimeRange(MiruBitmaps<BM> bitmaps, MiruTimeIndex miruTimeIndex) {
        BM bitmap = bitmaps.buildTimeRangeMask(miruTimeIndex, 0, Long.MAX_VALUE);

        assertExpectedNumberOfConsecutiveBitsStartingFromN(bitmaps, bitmap, 0, 1);
    }

    private <BM> void assertExpectedNumberOfConsecutiveBitsStartingFromN(MiruBitmaps<BM> bitmaps, BM bitmap, int expectedStartingFrom,
        int expectedCardinality) {
        int last = -1;
        int cardinality = 0;
        int startingFrom = -1;
        MiruIntIterator iter = bitmaps.intIterator(bitmap);
        while (iter.hasNext()) {
            int current = iter.next();
            if (last < 0) {
                startingFrom = current;
            } else if (last >= 0) {
                assertEquals(current - last, 1);
            }
            last = current;
            cardinality++;
        }
        assertEquals(startingFrom, expectedStartingFrom);
        assertEquals(cardinality, expectedCardinality);
    }

    @DataProvider(name = "evenTimeIndexDataProvider")
    public Object[][] evenTimeIndexDataProvider() throws Exception {

        final int size = (EWAHCompressedBitmap.WORD_IN_BITS * 3) + 1;
        final long[] timestamps = new long[size];
        for (int i = 0; i < size; i++) {
            timestamps[i] = i;
        }

        MiruTenantId tenantId = new MiruTenantId(new byte[] { 1 });
        MiruTimeIndex miruInMemoryTimeIndex = buildInMemoryTimeIndex();
        for (long timestamp : timestamps) {
            miruInMemoryTimeIndex.nextId(timestamp);
        }
        MiruTimeIndex miruOnDiskTimeIndex = buildOnDiskTimeIndex();
        ((BulkImport) miruOnDiskTimeIndex).bulkImport(tenantId, (BulkExport) miruInMemoryTimeIndex);

        return new Object[][] {
            { new MiruBitmapsEWAH(2), miruInMemoryTimeIndex },
            { new MiruBitmapsEWAH(2), miruOnDiskTimeIndex }
        };
    }

    @DataProvider(name = "oddTimeIndexDataProvider")
    public Object[][] oddTimeIndexDataProvider() throws Exception {

        final int size = EWAHCompressedBitmap.WORD_IN_BITS * 3;
        final long[] timestamps = new long[size];
        for (int i = 0; i < size; i++) {
            timestamps[i] = i;
        }

        MiruTenantId tenantId = new MiruTenantId(new byte[] { 1 });
        MiruTimeIndex miruInMemoryTimeIndex = buildInMemoryTimeIndex();
        for (long timestamp : timestamps) {
            miruInMemoryTimeIndex.nextId(timestamp);
        }
        MiruTimeIndex miruOnDiskTimeIndex = buildOnDiskTimeIndex();
        ((BulkImport) miruOnDiskTimeIndex).bulkImport(tenantId, (BulkExport) miruInMemoryTimeIndex);

        return new Object[][] {
            { new MiruBitmapsEWAH(2), miruInMemoryTimeIndex },
            { new MiruBitmapsEWAH(2), miruOnDiskTimeIndex }
        };
    }

    @DataProvider(name = "singleEntryTimeIndexDataProvider")
    public Object[][] singleEntryTimeIndexDataProvider() throws Exception {

        final long[] timestamps = new long[] { System.currentTimeMillis() };
        MiruTenantId tenantId = new MiruTenantId(new byte[] { 1 });
        MiruTimeIndex miruInMemoryTimeIndex = buildInMemoryTimeIndex();
        for (long timestamp : timestamps) {
            miruInMemoryTimeIndex.nextId(timestamp);
        }
        MiruTimeIndex miruOnDiskTimeIndex = buildOnDiskTimeIndex();
        ((BulkImport) miruOnDiskTimeIndex).bulkImport(tenantId, (BulkExport) miruInMemoryTimeIndex);

        return new Object[][] {
            { new MiruBitmapsEWAH(2), miruInMemoryTimeIndex },
            { new MiruBitmapsEWAH(2), miruOnDiskTimeIndex }
        };
    }

    private MiruTimeIndex buildInMemoryTimeIndex() throws Exception {
        MiruBitmapsEWAH bitmaps = new MiruBitmapsEWAH(4);
        MiruPartitionCoord coord = new MiruPartitionCoord(new MiruTenantId("test".getBytes()), MiruPartitionId.of(0), new MiruHost("localhost", 10000));
        return buildHybridContextAllocator(numberOfChunkStores, 10, true, 64).allocate(bitmaps, coord).timeIndex;
    }

    private MiruTimeIndex buildOnDiskTimeIndex() throws Exception {
        MiruBitmapsEWAH bitmaps = new MiruBitmapsEWAH(4);
        MiruPartitionCoord coord = new MiruPartitionCoord(new MiruTenantId("test".getBytes()), MiruPartitionId.of(0), new MiruHost("localhost", 10000));
        return buildOnDiskContextAllocator(numberOfChunkStores, 10, 64).allocate(bitmaps, coord).timeIndex;
    }
}
