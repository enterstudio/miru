package com.jivesoftware.os.miru.manage.deployable;

import com.jivesoftware.os.miru.api.MiruPartition;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.cluster.MiruClusterRegistry;
import java.util.Collection;
import java.util.List;

/**
*
*/
public interface ShiftPredicate {
    boolean needsToShift(MiruTenantId tenantId,
        MiruPartitionId partitionId,
        Collection<MiruClusterRegistry.HostHeartbeat> hostHeartbeats,
        List<MiruPartition> partitions);
}
