package com.jivesoftware.os.miru.plugin.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import com.jivesoftware.os.miru.api.MiruActorId;
import com.jivesoftware.os.miru.api.MiruHost;
import com.jivesoftware.os.miru.api.MiruHostProvider;
import com.jivesoftware.os.miru.plugin.solution.MiruRequest;
import com.jivesoftware.os.miru.plugin.solution.MiruResponse;
import com.jivesoftware.os.miru.plugin.solution.MiruSolution;
import com.jivesoftware.os.routing.bird.http.client.HttpResponse;
import com.jivesoftware.os.routing.bird.http.client.HttpResponseMapper;
import com.jivesoftware.os.routing.bird.http.client.RoundRobinStrategy;
import com.jivesoftware.os.routing.bird.http.client.TenantAwareHttpClient;
import com.jivesoftware.os.routing.bird.shared.ClientCall;
import com.jivesoftware.os.routing.bird.shared.ClientHealth;
import com.jivesoftware.os.routing.bird.shared.ConnectionDescriptor;
import com.jivesoftware.os.routing.bird.shared.HostPort;
import com.jivesoftware.os.routing.bird.shared.HttpClientException;
import com.jivesoftware.os.routing.bird.shared.IndexedClientStrategy;
import com.jivesoftware.os.routing.bird.shared.InstanceDescriptor;
import com.jivesoftware.os.routing.bird.shared.InstanceDescriptor.InstanceDescriptorPort;
import com.jivesoftware.os.routing.bird.shared.NextClientStrategy;
import com.jivesoftware.os.routing.bird.shared.ReturnFirstNonFailure;
import com.jivesoftware.os.routing.bird.shared.ReturnFirstNonFailure.Favored;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 */
public class MiruQueryRouting implements MiruRouting {

    private final Map<MiruTenantIdAndFamily, NextClientStrategy> strategyCache = Maps.newConcurrentMap();

    private final TenantAwareHttpClient<String> readerClient;
    private final ObjectMapper requestMapper;
    private final HttpResponseMapper responseMapper;
    private final MiruQueryEvent queryEvent;

    public MiruQueryRouting(
        TenantAwareHttpClient<String> readerClient,
        ObjectMapper requestMapper,
        HttpResponseMapper responseMapper,
        MiruQueryEvent queryEvent
    ) {
        this.readerClient = readerClient;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
        this.queryEvent = queryEvent;
    }


    @Override
    public <Q, A> MiruResponse<A> query(String routingTenant,
        String family,
        MiruRequest<Q> request,
        String path,
        Class<A> answerClass) throws Exception {

        MiruTenantIdAndFamily tenantAndFamily = new MiruTenantIdAndFamily(request.tenantId, family);
        long start = System.currentTimeMillis();
        try {
            String json = requestMapper.writeValueAsString(request);
            NextClientStrategy tenantStrategy = getTenantStrategy(tenantAndFamily);
            InterceptingNextClientStrategy interceptingNextClientStrategy = new InterceptingNextClientStrategy(tenantStrategy);
            HttpResponse httpResponse = readerClient.call(routingTenant,
                tenantStrategy,
                family,
                (c) -> new ClientCall.ClientResponse<>(c.postJson(path, json, null), true)
            );
            MiruResponse<A> answer = responseMapper.extractResultFromResponse(httpResponse, MiruResponse.class, new Class[] { answerClass }, null);
            recordTenantStrategy(tenantAndFamily, request.actorId, interceptingNextClientStrategy, answer);
            return answer;
        } catch (HttpClientException x) {
            queryEvent.event(tenantAndFamily.miruTenantId, request.actorId, tenantAndFamily.family, "nil", System.currentTimeMillis() - start, "failure");
            throw x;
        }
    }

    private NextClientStrategy getTenantStrategy(MiruTenantIdAndFamily tenantAndFamily) {
        return strategyCache.getOrDefault(tenantAndFamily, new RoundRobinStrategy());
    }


    private void recordTenantStrategy(MiruTenantIdAndFamily tenantAndFamily,
        MiruActorId actorId,
        InterceptingNextClientStrategy interceptingNextClientStrategy,
        MiruResponse<?> response) {

        if (response != null && response.solutions != null && !response.solutions.isEmpty()) {
            MiruSolution solution = response.solutions.get(0);
            MiruHost host = solution.usedPartition.host;

            ConnectionDescriptor favored = interceptingNextClientStrategy.favoredConnectionDescriptor;
            if (favored != null) {
                InstanceDescriptor instanceDescriptor = favored.getInstanceDescriptor();
                InstanceDescriptorPort port = instanceDescriptor.ports.get("main");
                int p = port == null ? -1 : port.port;

                queryEvent.event(tenantAndFamily.miruTenantId, actorId, tenantAndFamily.family, instanceDescriptor.instanceKey, solution.totalElapsed,
                    "success",
                    "attempt:" + interceptingNextClientStrategy.attempt,
                    "totalAttempts:" + interceptingNextClientStrategy.totalAttempts,
                    "destinationService:" + instanceDescriptor.serviceName,
                    "destinationAddress:" + instanceDescriptor.publicHost + ":" + p
                );
            }

            strategyCache.compute(tenantAndFamily, (key, existing) -> {
                if (existing != null && existing instanceof PreferredNodeStrategy && ((PreferredNodeStrategy) existing).host.equals(host)) {
                    return existing;
                } else {
                    return new PreferredNodeStrategy(host);
                }
            });
        }
    }


    private static final class PreferredNodeStrategy implements NextClientStrategy, IndexedClientStrategy {

        private final ReturnFirstNonFailure returnFirstNonFailure = new ReturnFirstNonFailure();
        private final MiruHost host;

        PreferredNodeStrategy(MiruHost host) {
            this.host = host;
        }

        @Override
        public <C, R> R call(String family,
            ClientCall<C, R, HttpClientException> httpCall,
            ConnectionDescriptor[] connectionDescriptors,
            long connectionDescriptorsVersion,
            C[] clients,
            ClientHealth[] clientHealths,
            int deadAfterNErrors,
            long checkDeadEveryNMillis,
            AtomicInteger[] clientsErrors,
            AtomicLong[] clientsDeathTimestamp,
            Favored favored) throws HttpClientException {
            return returnFirstNonFailure.call(this,
                family,
                httpCall,
                connectionDescriptors,
                connectionDescriptorsVersion,
                clients,
                clientHealths,
                deadAfterNErrors,
                checkDeadEveryNMillis,
                clientsErrors,
                clientsDeathTimestamp,
                favored);
        }

        @Override
        public int[] getClients(ConnectionDescriptor[] connectionDescriptors) {
            int len = connectionDescriptors.length;
            int[] indexes = new int[len];
            int pos = 0;
            int preferredIndex = -1;
            for (int i = 0; i < connectionDescriptors.length; i++) {
                HostPort hostPort = connectionDescriptors[i].getHostPort();
                InstanceDescriptor instanceDescriptor = connectionDescriptors[i].getInstanceDescriptor();
                if (MiruHostProvider.checkEquals(host,
                    instanceDescriptor.instanceName, instanceDescriptor.instanceKey,
                    hostPort.getHost(), hostPort.getPort())) {
                    indexes[0] = i;
                    pos = 1;
                    preferredIndex = i;
                    break;
                }
            }
            for (int i = 0; i < connectionDescriptors.length; i++) {
                if (i != preferredIndex) {
                    indexes[pos] = i;
                    pos++;
                }
            }
            return indexes;
        }

        @Override
        public void usedClientAtIndex(int i) {
        }
    }


}
