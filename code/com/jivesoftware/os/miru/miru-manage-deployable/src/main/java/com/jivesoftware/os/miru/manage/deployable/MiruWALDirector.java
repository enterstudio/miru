package com.jivesoftware.os.miru.manage.deployable;

import com.google.common.base.Optional;
import com.jivesoftware.os.jive.utils.logger.MetricLogger;
import com.jivesoftware.os.jive.utils.logger.MetricLoggerFactory;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.api.activity.MiruPartitionedActivityFactory;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.cluster.MiruClusterRegistry;
import com.jivesoftware.os.miru.wal.activity.MiruActivityWALReader;
import com.jivesoftware.os.miru.wal.activity.MiruActivityWALStatus;
import com.jivesoftware.os.miru.wal.activity.MiruActivityWALWriter;
import java.util.Arrays;
import java.util.List;

/**
 *
 */
public class MiruWALDirector {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final MiruClusterRegistry clusterRegistry;
    private final MiruActivityWALReader activityWALReader;
    private final MiruActivityWALWriter activityWALWriter;

    private final MiruPartitionedActivityFactory partitionedActivityFactory = new MiruPartitionedActivityFactory();

    public MiruWALDirector(MiruClusterRegistry clusterRegistry,
        MiruActivityWALReader activityWALReader,
        MiruActivityWALWriter activityWALWriter) {
        this.clusterRegistry = clusterRegistry;
        this.activityWALReader = activityWALReader;
        this.activityWALWriter = activityWALWriter;
    }

    public void repairActivityWAL() throws Exception {
        List<MiruTenantId> tenantIds = clusterRegistry.allTenantIds();
        for (MiruTenantId tenantId : tenantIds) {
            Optional<MiruPartitionId> latestPartitionId = clusterRegistry.getLatestPartitionIdForTenant(tenantId);
            if (latestPartitionId.isPresent()) {
                for (MiruPartitionId partitionId = latestPartitionId.get().prev(); partitionId != null; partitionId = partitionId.prev()) {
                    MiruActivityWALStatus status = activityWALReader.getStatus(tenantId, partitionId);
                    if (!status.begins.equals(status.ends)) {
                        for (int begin : status.begins) {
                            if (!status.ends.contains(begin)) {
                                activityWALWriter.write(tenantId, Arrays.asList(partitionedActivityFactory.end(begin, partitionId, tenantId, -1)));
                                LOG.info("Added missing 'end' to WAL for {} {}", tenantId, partitionId);
                            }
                        }
                        for (int end : status.ends) {
                            if (!status.begins.contains(end)) {
                                activityWALWriter.write(tenantId, Arrays.asList(partitionedActivityFactory.begin(end, partitionId, tenantId, -1)));
                                LOG.info("Added missing 'begin' to WAL for {} {}", tenantId, partitionId);
                            }
                        }
                    }
                }
            }
        }
    }
}
