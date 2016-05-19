package com.jivesoftware.os.miru.stream.plugins.strut;

import com.google.common.base.Optional;
import com.jivesoftware.os.miru.api.MiruQueryServiceException;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.plugin.Miru;
import com.jivesoftware.os.miru.plugin.MiruProvider;
import com.jivesoftware.os.miru.plugin.partition.MiruPartitionUnavailableException;
import com.jivesoftware.os.miru.plugin.solution.MiruPartitionResponse;
import com.jivesoftware.os.miru.plugin.solution.MiruRequest;
import com.jivesoftware.os.miru.plugin.solution.MiruRequestAndReport;
import com.jivesoftware.os.miru.plugin.solution.MiruResponse;
import com.jivesoftware.os.miru.plugin.solution.MiruSolutionLogLevel;
import com.jivesoftware.os.miru.plugin.solution.MiruSolvableFactory;

/**
 *
 */
public class StrutInjectable {

    private final MiruProvider<? extends Miru> provider;
    private final StrutModelScorer modelScorer;
    private final Strut strut;
    private final int maxTermIdsPerRequest;

    public StrutInjectable(MiruProvider<? extends Miru> provider,
        StrutModelScorer modelScorer,
        Strut strut,
        int maxTermIdsPerRequest) {
        this.provider = provider;
        this.modelScorer = modelScorer;
        this.strut = strut;
        this.maxTermIdsPerRequest = maxTermIdsPerRequest;
    }

    public MiruResponse<StrutAnswer> strut(MiruRequest<StrutQuery> request) throws MiruQueryServiceException, InterruptedException {
        try {
            MiruTenantId tenantId = request.tenantId;
            Miru miru = provider.getMiru(tenantId);
            return miru.askAndMerge(tenantId,
                new MiruSolvableFactory<>(request.name, provider.getStats(),
                    "strut",
                    new StrutQuestion(modelScorer,
                        strut,
                        provider.getBackfillerizer(tenantId),
                        request,
                        provider.getRemotePartition(StrutRemotePartition.class),
                        maxTermIdsPerRequest)),
                new StrutAnswerEvaluator(),
                new StrutAnswerMerger(request.query.desiredNumberOfResults),
                StrutAnswer.EMPTY_RESULTS,
                request.logLevel);
        } catch (MiruPartitionUnavailableException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            //TODO throw http error codes
            throw new MiruQueryServiceException("Failed to strut", e);
        }
    }

    public MiruPartitionResponse<StrutAnswer> strut(MiruPartitionId partitionId,
        MiruRequestAndReport<StrutQuery, StrutReport> requestAndReport)
        throws MiruQueryServiceException, InterruptedException {
        try {
            MiruTenantId tenantId = requestAndReport.request.tenantId;
            Miru miru = provider.getMiru(tenantId);
            return miru.askImmediate(tenantId,
                partitionId,
                new MiruSolvableFactory<>(requestAndReport.request.name, provider.getStats(),
                    "strut",
                    new StrutQuestion(modelScorer,
                        strut,
                        provider.getBackfillerizer(tenantId),
                        requestAndReport.request,
                        provider.getRemotePartition(StrutRemotePartition.class),
                        maxTermIdsPerRequest)),
                Optional.fromNullable(requestAndReport.report),
                StrutAnswer.EMPTY_RESULTS,
                MiruSolutionLogLevel.NONE);
        } catch (MiruPartitionUnavailableException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            //TODO throw http error codes
            throw new MiruQueryServiceException("Failed remote strut for partition: " + partitionId.getId(), e);
        }
    }

    public MiruResponse<StrutAnswer> strut(MiruPartitionId partitionId,
        MiruRequest<StrutQuery> request) throws MiruQueryServiceException, InterruptedException {
        try {
            MiruTenantId tenantId = request.tenantId;
            Miru miru = provider.getMiru(tenantId);
            return miru.askAndMergePartition(tenantId,
                partitionId,
                new MiruSolvableFactory<>(request.name, provider.getStats(),
                    "strut",
                    new StrutQuestion(modelScorer,
                        strut,
                        provider.getBackfillerizer(tenantId),
                        request,
                        provider.getRemotePartition(StrutRemotePartition.class),
                        maxTermIdsPerRequest)),
                new StrutAnswerMerger(request.query.desiredNumberOfResults),
                StrutAnswer.EMPTY_RESULTS,
                request.logLevel);
        } catch (MiruPartitionUnavailableException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            //TODO throw http error codes
            throw new MiruQueryServiceException("Failed single strut for partition: " + partitionId.getId(), e);
        }
    }

}
