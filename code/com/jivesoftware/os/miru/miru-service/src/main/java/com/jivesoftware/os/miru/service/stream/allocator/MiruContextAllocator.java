package com.jivesoftware.os.miru.service.stream.allocator;

import com.jivesoftware.os.miru.api.MiruPartitionCoord;
import com.jivesoftware.os.miru.api.MiruPartitionState;
import com.jivesoftware.os.miru.plugin.bitmap.MiruBitmaps;
import com.jivesoftware.os.miru.service.stream.MiruContext;
import java.io.IOException;

/**
 *
 */
public interface MiruContextAllocator {

    boolean checkMarkedStorage(MiruPartitionCoord coord) throws Exception;

    <BM> MiruContext<BM> allocate(MiruBitmaps<BM> bitmaps, MiruPartitionCoord coord) throws Exception;

    <BM> MiruContext<BM> stateChanged(MiruBitmaps<BM> bitmaps, MiruPartitionCoord coord, MiruContext<BM> from, MiruPartitionState state)
        throws Exception;

    <BM> void close(MiruContext<BM> context);

    <BM> void releaseCaches(MiruContext<BM> context) throws IOException;
}
