package com.jivesoftware.os.miru.stream.plugins.strut;

import com.jivesoftware.os.miru.api.base.MiruTermId;
import java.util.Map;

/**
 * @author jonathan.colt
 */
public class StrutModel {

    final Map<StrutModelCache.StrutModelKey, StrutModelCache.ModelScore>[] model;
    public final long[] modelCounts;
    public final long totalCount;
    public final int[] numberOfModels;
    public final int[] totalNumPartitions;

    public StrutModel(Map<StrutModelCache.StrutModelKey, StrutModelCache.ModelScore>[] model,
        long[] modelCounts,
        long totalCount,
        int[] numberOfModels,
        int[] totalNumPartitions) {
        this.model = model;
        this.modelCounts = modelCounts;
        this.totalCount = totalCount;
        this.numberOfModels = numberOfModels;
        this.totalNumPartitions = totalNumPartitions;
    }

    public StrutModelCache.ModelScore score(int featureId, MiruTermId[] values) {
        return model[featureId].get(new StrutModelCache.StrutModelKey(values));
    }

}
