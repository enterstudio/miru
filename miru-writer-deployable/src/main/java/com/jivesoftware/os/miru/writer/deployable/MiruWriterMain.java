/*
 * Copyright 2013 Jive Software, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.jivesoftware.os.miru.writer.deployable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.google.common.collect.Maps;
import com.jivesoftware.os.amza.api.wal.WALKey;
import com.jivesoftware.os.amza.service.AmzaService;
import com.jivesoftware.os.amza.service.EmbeddedClientProvider;
import com.jivesoftware.os.miru.amza.MiruAmzaServiceConfig;
import com.jivesoftware.os.miru.amza.MiruAmzaServiceInitializer;
import com.jivesoftware.os.miru.api.MiruStats;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.api.topology.MiruClusterClient;
import com.jivesoftware.os.miru.api.wal.AmzaCursor;
import com.jivesoftware.os.miru.api.wal.AmzaSipCursor;
import com.jivesoftware.os.miru.api.wal.MiruWALClient;
import com.jivesoftware.os.miru.api.wal.MiruWALConfig;
import com.jivesoftware.os.miru.api.wal.RCVSCursor;
import com.jivesoftware.os.miru.api.wal.RCVSSipCursor;
import com.jivesoftware.os.miru.cluster.client.MiruClusterClientInitializer;
import com.jivesoftware.os.miru.logappender.MiruLogAppender;
import com.jivesoftware.os.miru.logappender.MiruLogAppenderInitializer;
import com.jivesoftware.os.miru.logappender.RoutingBirdLogSenderProvider;
import com.jivesoftware.os.miru.metric.sampler.MiruMetricSampler;
import com.jivesoftware.os.miru.metric.sampler.MiruMetricSamplerInitializer;
import com.jivesoftware.os.miru.metric.sampler.MiruMetricSamplerInitializer.MiruMetricSamplerConfig;
import com.jivesoftware.os.miru.metric.sampler.RoutingBirdMetricSampleSenderProvider;
import com.jivesoftware.os.miru.ui.MiruSoyRenderer;
import com.jivesoftware.os.miru.ui.MiruSoyRendererInitializer;
import com.jivesoftware.os.miru.ui.MiruSoyRendererInitializer.MiruSoyRendererConfig;
import com.jivesoftware.os.miru.wal.client.MiruWALClientInitializer;
import com.jivesoftware.os.miru.wal.client.MiruWALClientInitializer.WALClientSickThreadsHealthCheckConfig;
import com.jivesoftware.os.miru.writer.deployable.base.MiruActivityIngress;
import com.jivesoftware.os.miru.writer.deployable.endpoints.MiruIngressEndpoints;
import com.jivesoftware.os.miru.writer.partition.AmzaPartitionIdProvider;
import com.jivesoftware.os.routing.bird.deployable.Deployable;
import com.jivesoftware.os.routing.bird.deployable.InstanceConfig;
import com.jivesoftware.os.routing.bird.endpoints.base.HasUI;
import com.jivesoftware.os.routing.bird.health.api.HealthCheckRegistry;
import com.jivesoftware.os.routing.bird.health.api.HealthChecker;
import com.jivesoftware.os.routing.bird.health.api.HealthFactory;
import com.jivesoftware.os.routing.bird.health.checkers.GCLoadHealthChecker;
import com.jivesoftware.os.routing.bird.health.checkers.ServiceStartupHealthCheck;
import com.jivesoftware.os.routing.bird.health.checkers.SickThreads;
import com.jivesoftware.os.routing.bird.health.checkers.SickThreadsHealthCheck;
import com.jivesoftware.os.routing.bird.http.client.HttpDeliveryClientHealthProvider;
import com.jivesoftware.os.routing.bird.http.client.HttpRequestHelperUtils;
import com.jivesoftware.os.routing.bird.http.client.TenantAwareHttpClient;
import com.jivesoftware.os.routing.bird.http.client.TenantRoutingHttpClientInitializer;
import com.jivesoftware.os.routing.bird.server.util.Resource;
import com.jivesoftware.os.routing.bird.shared.TenantRoutingProvider;
import com.jivesoftware.os.routing.bird.shared.TenantsServiceConnectionDescriptorProvider;
import java.io.File;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.merlin.config.defaults.LongDefault;
import org.merlin.config.defaults.StringDefault;

public class MiruWriterMain {

    public static void main(String[] args) throws Exception {
        new MiruWriterMain().run(args);
    }

    public interface WriterAmzaServiceConfig extends MiruAmzaServiceConfig {

        @StringDefault("./var/amza/writer/data/")
        @Override
        String getWorkingDirectories();

        @LongDefault(60_000L)
        long getReplicateCursorTimeoutMillis();
    }

    public void run(String[] args) throws Exception {
        ServiceStartupHealthCheck serviceStartupHealthCheck = new ServiceStartupHealthCheck();
        try {
            final Deployable deployable = new Deployable(args);
            HealthFactory.initialize(deployable::config,
                new HealthCheckRegistry() {

                @Override
                public void register(HealthChecker healthChecker) {
                    deployable.addHealthCheck(healthChecker);
                }

                @Override
                public void unregister(HealthChecker healthChecker) {
                    throw new UnsupportedOperationException("Not supported yet.");
                }
            });
            deployable.addErrorHealthChecks();
            deployable.addManageInjectables(HasUI.class, new HasUI(Arrays.asList(
                new HasUI.UI("Reset Errors", "manage", "/manage/resetErrors"),
                new HasUI.UI("Tail", "manage", "/manage/tail?lastNLines=1000"),
                new HasUI.UI("Thread Dump", "manage", "/manage/threadDump"),
                new HasUI.UI("Health", "manage", "/manage/ui"),
                new HasUI.UI("Miru-Writer", "main", "/miru/writer"),
                new HasUI.UI("Miru-Writer-Amza", "main", "/amza"))));

            deployable.buildStatusReporter(null).start();
            deployable.addHealthCheck(new GCLoadHealthChecker(deployable.config(GCLoadHealthChecker.GCLoadHealthCheckerConfig.class)));
            deployable.addHealthCheck(serviceStartupHealthCheck);
            deployable.buildManageServer().start();

            InstanceConfig instanceConfig = deployable.config(InstanceConfig.class);

            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
            mapper.registerModule(new GuavaModule());

            TenantRoutingProvider tenantRoutingProvider = deployable.getTenantRoutingProvider();
            MiruLogAppenderInitializer.MiruLogAppenderConfig miruLogAppenderConfig = deployable.config(MiruLogAppenderInitializer.MiruLogAppenderConfig.class);
            TenantsServiceConnectionDescriptorProvider logConnections = tenantRoutingProvider.getConnections("miru-stumptown", "main");
            MiruLogAppender miruLogAppender = new MiruLogAppenderInitializer().initialize(null, //TODO datacenter
                instanceConfig.getClusterName(),
                instanceConfig.getHost(),
                instanceConfig.getServiceName(),
                String.valueOf(instanceConfig.getInstanceName()),
                instanceConfig.getVersion(),
                miruLogAppenderConfig,
                new RoutingBirdLogSenderProvider<>(logConnections, "", miruLogAppenderConfig.getSocketTimeoutInMillis()));
            miruLogAppender.install();

            MiruMetricSamplerConfig metricSamplerConfig = deployable.config(MiruMetricSamplerConfig.class);
            TenantsServiceConnectionDescriptorProvider metricConnections = tenantRoutingProvider.getConnections("miru-anomaly", "main");
            MiruMetricSampler sampler = new MiruMetricSamplerInitializer().initialize(null, //TODO datacenter
                instanceConfig.getClusterName(),
                instanceConfig.getHost(),
                instanceConfig.getServiceName(),
                String.valueOf(instanceConfig.getInstanceName()),
                instanceConfig.getVersion(),
                metricSamplerConfig,
                new RoutingBirdMetricSampleSenderProvider<>(metricConnections, "", miruLogAppenderConfig.getSocketTimeoutInMillis()));
            sampler.start();

            MiruClientConfig clientConfig = deployable.config(MiruClientConfig.class);

            HttpDeliveryClientHealthProvider clientHealthProvider = new HttpDeliveryClientHealthProvider(instanceConfig.getInstanceKey(),
                HttpRequestHelperUtils.buildRequestHelper(instanceConfig.getRoutesHost(), instanceConfig.getRoutesPort()),
                instanceConfig.getConnectionsHealth(), 5_000, 100);

            TenantRoutingHttpClientInitializer<String> tenantRoutingHttpClientInitializer = new TenantRoutingHttpClientInitializer<>();
            TenantAwareHttpClient<String> manageHttpClient = tenantRoutingHttpClientInitializer.initialize(tenantRoutingProvider
                .getConnections("miru-manage", "main"),
                clientHealthProvider,
                10, 10_000); // TODO expose to conf

            TenantAwareHttpClient<String> walHttpClient = tenantRoutingHttpClientInitializer.initialize(tenantRoutingProvider
                .getConnections("miru-wal", "main"),
                clientHealthProvider,
                10, 10_000); // TODO expose to conf

            final Map<MiruTenantId, Boolean> latestAlignmentCache = Maps.newConcurrentMap();

            WriterAmzaServiceConfig miruAmzaServiceConfig = deployable.config(WriterAmzaServiceConfig.class);
            AmzaService amzaService = new MiruAmzaServiceInitializer().initialize(deployable,
                instanceConfig.getRoutesHost(),
                instanceConfig.getRoutesPort(),
                instanceConfig.getConnectionsHealth(),
                instanceConfig.getInstanceName(),
                instanceConfig.getInstanceKey(),
                instanceConfig.getServiceName(),
                instanceConfig.getDatacenter(),
                instanceConfig.getRack(),
                instanceConfig.getHost(),
                instanceConfig.getMainPort(),
                null, //"miru-writer-" + instanceConfig.getClusterName(),
                miruAmzaServiceConfig,
                changes -> {
                    if (changes.getVersionedPartitionName().getPartitionName().equals(AmzaPartitionIdProvider.LATEST_PARTITIONS_PARTITION_NAME)) {
                        for (WALKey key : changes.getApply().keySet()) {
                            MiruTenantId tenantId = AmzaPartitionIdProvider.extractTenantForLatestPartition(key);
                            latestAlignmentCache.remove(tenantId);
                        }
                    }
                });

            SickThreads walClientSickThreads = new SickThreads();
            deployable.addHealthCheck(new SickThreadsHealthCheck(deployable.config(WALClientSickThreadsHealthCheckConfig.class), walClientSickThreads));

            MiruWALConfig walConfig = deployable.config(MiruWALConfig.class);
            MiruWALClient<?, ?> walClient;
            if (walConfig.getActivityWALType().equals("rcvs") || walConfig.getActivityWALType().equals("rcvs_amza")) {
                MiruWALClient<RCVSCursor, RCVSSipCursor> rcvsWALClient = new MiruWALClientInitializer().initialize("", walHttpClient, mapper,
                    walClientSickThreads, 10_000,
                    "/miru/wal/rcvs", RCVSCursor.class, RCVSSipCursor.class);
                walClient = rcvsWALClient;
            } else if (walConfig.getActivityWALType().equals("amza") || walConfig.getActivityWALType().equals("amza_rcvs")) {
                MiruWALClient<AmzaCursor, AmzaSipCursor> amzaWALClient = new MiruWALClientInitializer().initialize("", walHttpClient, mapper,
                    walClientSickThreads, 10_000,
                    "/miru/wal/amza", AmzaCursor.class, AmzaSipCursor.class);
                walClient = amzaWALClient;
            } else {
                throw new IllegalStateException("Invalid activity WAL type: " + walConfig.getActivityWALType());
            }

            String indexClass = "lab";

            EmbeddedClientProvider clientProvider = new EmbeddedClientProvider(amzaService);
            AmzaPartitionIdProvider amzaPartitionIdProvider = new AmzaPartitionIdProvider(amzaService,
                clientProvider,
                miruAmzaServiceConfig.getReplicateCursorTimeoutMillis(),
                indexClass,
                clientConfig.getTotalCapacity(),
                walClient);

            MiruStats miruStats = new MiruStats();
            MiruClusterClient clusterClient = new MiruClusterClientInitializer().initialize(miruStats, "", manageHttpClient, mapper);

            MiruPartitioner miruPartitioner = new MiruPartitioner(instanceConfig.getInstanceName(),
                amzaPartitionIdProvider,
                walClient,
                clusterClient,
                clientConfig.getPartitionMaximumAgeInMillis());

            ExecutorService sendActivitiesExecutorService = Executors.newFixedThreadPool(clientConfig.getSendActivitiesThreadPoolSize());
            MiruActivityIngress activityIngress = new MiruActivityIngress(miruPartitioner, latestAlignmentCache, sendActivitiesExecutorService);

            MiruSoyRendererConfig rendererConfig = deployable.config(MiruSoyRendererConfig.class);

            File staticResourceDir = new File(System.getProperty("user.dir"));
            System.out.println("Static resources rooted at " + staticResourceDir.getAbsolutePath());
            Resource sourceTree = new Resource(staticResourceDir)
                //.addResourcePath("../../../../../src/main/resources") // fluff?
                .addResourcePath(rendererConfig.getPathToStaticResources())
                .setContext("/static");

            MiruSoyRenderer renderer = new MiruSoyRendererInitializer().initialize(rendererConfig);

            MiruWriterUIService miruWriterUIService = new MiruWriterUIServiceInitializer()
                .initialize(instanceConfig.getClusterName(), instanceConfig.getInstanceName(), renderer, miruStats, tenantRoutingProvider);

            deployable.addEndpoints(MiruWriterEndpoints.class);
            deployable.addInjectables(MiruWriterUIService.class, miruWriterUIService);

            deployable.addEndpoints(MiruIngressEndpoints.class);
            deployable.addInjectables(MiruStats.class, miruStats);
            deployable.addInjectables(MiruActivityIngress.class, activityIngress);

            deployable.addResource(sourceTree);
            deployable.buildServer().start();
            clientHealthProvider.start();
            serviceStartupHealthCheck.success();
        } catch (Throwable t) {
            serviceStartupHealthCheck.info("Encountered the following failure during startup.", t);
        }
    }
}
