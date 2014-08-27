package com.jivesoftware.os.miru.service.endpoint.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.googlecode.javaewah.EWAHCompressedBitmap;
import com.jivesoftware.os.jive.utils.http.client.HttpClientConfiguration;
import com.jivesoftware.os.jive.utils.http.client.HttpClientFactory;
import com.jivesoftware.os.jive.utils.http.client.HttpClientFactoryProvider;
import com.jivesoftware.os.jive.utils.id.Id;
import com.jivesoftware.os.jive.utils.row.column.value.store.api.SetOfSortedMapsImplInitializer;
import com.jivesoftware.os.jive.utils.row.column.value.store.inmemory.InMemorySetOfSortedMapsImplInitializer;
import com.jivesoftware.os.miru.api.MiruActorId;
import com.jivesoftware.os.miru.api.MiruAggregateCountsQueryCriteria;
import com.jivesoftware.os.miru.api.MiruAggregateCountsQueryParams;
import com.jivesoftware.os.miru.api.MiruBackingStorage;
import com.jivesoftware.os.miru.api.MiruHost;
import com.jivesoftware.os.miru.api.MiruLifecyle;
import com.jivesoftware.os.miru.api.MiruPartition;
import com.jivesoftware.os.miru.api.MiruPartitionCoord;
import com.jivesoftware.os.miru.api.MiruPartitionCoordInfo;
import com.jivesoftware.os.miru.api.MiruPartitionCoordMetrics;
import com.jivesoftware.os.miru.api.MiruPartitionState;
import com.jivesoftware.os.miru.api.MiruReader;
import com.jivesoftware.os.miru.api.MiruWriter;
import com.jivesoftware.os.miru.api.activity.MiruActivity;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.api.activity.MiruPartitionedActivity;
import com.jivesoftware.os.miru.api.activity.MiruPartitionedActivityFactory;
import com.jivesoftware.os.miru.api.activity.schema.DefaultMiruSchemaDefinition;
import com.jivesoftware.os.miru.api.activity.schema.MiruSchema;
import com.jivesoftware.os.miru.api.activity.schema.SingleSchemaProvider;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.api.query.filter.MiruAuthzExpression;
import com.jivesoftware.os.miru.api.query.filter.MiruFieldFilter;
import com.jivesoftware.os.miru.api.query.filter.MiruFilter;
import com.jivesoftware.os.miru.api.query.filter.MiruFilterOperation;
import com.jivesoftware.os.miru.api.query.result.AggregateCountsResult;
import com.jivesoftware.os.miru.cluster.MiruClusterRegistry;
import com.jivesoftware.os.miru.cluster.MiruRegistryStore;
import com.jivesoftware.os.miru.cluster.MiruRegistryStoreInitializer;
import com.jivesoftware.os.miru.cluster.MiruReplicaSet;
import com.jivesoftware.os.miru.cluster.rcvs.MiruRCVSClusterRegistry;
import com.jivesoftware.os.miru.service.MiruService;
import com.jivesoftware.os.miru.service.MiruServiceConfig;
import com.jivesoftware.os.miru.service.MiruServiceInitializer;
import com.jivesoftware.os.miru.service.MiruTempResourceLocatorProviderInitializer;
import com.jivesoftware.os.miru.service.bitmap.MiruBitmapsEWAH;
import com.jivesoftware.os.miru.service.endpoint.MiruReaderEndpoints;
import com.jivesoftware.os.miru.service.endpoint.MiruWriterEndpoints;
import com.jivesoftware.os.miru.service.reader.MiruReaderImpl;
import com.jivesoftware.os.miru.service.stream.locator.MiruResourceLocatorProvider;
import com.jivesoftware.os.miru.service.writer.MiruWriterImpl;
import com.jivesoftware.os.miru.wal.MiruWALInitializer;
import org.merlin.config.BindInterfaceToConfiguration;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.ws.rs.core.Response;

import static com.jivesoftware.os.miru.api.field.MiruFieldName.AUTHOR_ID;
import static com.jivesoftware.os.miru.api.field.MiruFieldName.OBJECT_ID;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class InMemoryMiruReaderWriterEndpointsTest {

    MiruTenantId tenantId = new MiruTenantId("tenant1".getBytes());
    MiruPartitionId partitionId = MiruPartitionId.of(0);

    private final MiruPartitionedActivityFactory activityFactory = new MiruPartitionedActivityFactory();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MiruReaderEndpoints miruReaderEndpoints;
    private MiruWriterEndpoints miruWriterEndpoints;

    @BeforeMethod
    public void setUp() throws Exception {

        MiruBackingStorage desiredStorage = MiruBackingStorage.memory;

        MiruServiceConfig config = BindInterfaceToConfiguration.bindDefault(MiruServiceConfig.class);
        config.setDefaultStorage(desiredStorage.name());
        config.setDefaultFailAfterNMillis(TimeUnit.HOURS.toMillis(1));

        MiruHost miruHost = new MiruHost("logicalName", 1234);
        HttpClientFactory httpClientFactory = new HttpClientFactoryProvider()
                .createHttpClientFactory(Collections.<HttpClientConfiguration>emptyList());

        SetOfSortedMapsImplInitializer<RuntimeException> setOfSortedMapsImplInitializer = new InMemorySetOfSortedMapsImplInitializer();
        MiruRegistryStore registryStore = new MiruRegistryStoreInitializer().initialize("test", setOfSortedMapsImplInitializer);
        MiruClusterRegistry clusterRegistry = new MiruRCVSClusterRegistry(registryStore.getHostsRegistry(),
                registryStore.getExpectedTenantsRegistry(),
                registryStore.getExpectedTenantPartitionsRegistry(),
                registryStore.getReplicaRegistry(),
                registryStore.getTopologyRegistry(),
                registryStore.getConfigRegistry(),
                3,
                TimeUnit.HOURS.toMillis(1));

        clusterRegistry.sendHeartbeatForHost(miruHost, 0, 0);
        clusterRegistry.electToReplicaSetForTenantPartition(tenantId, partitionId,
                new MiruReplicaSet(ArrayListMultimap.<MiruPartitionState, MiruPartition>create(), new HashSet<MiruHost>(), 3));
        clusterRegistry.refreshTopology(new MiruPartitionCoord(tenantId, partitionId, miruHost), new MiruPartitionCoordMetrics(0, 0),
                System.currentTimeMillis());

        MiruWALInitializer.MiruWAL wal = new MiruWALInitializer().initialize("test", setOfSortedMapsImplInitializer);

        MiruLifecyle<MiruResourceLocatorProvider> miruResourceLocatorProviderLifecyle = new MiruTempResourceLocatorProviderInitializer().initialize();
        miruResourceLocatorProviderLifecyle.start();
        MiruLifecyle<MiruService> miruServiceLifecyle = new MiruServiceInitializer<EWAHCompressedBitmap>().initialize(config,
                registryStore,
                clusterRegistry,
                miruHost,
                new SingleSchemaProvider(new MiruSchema(DefaultMiruSchemaDefinition.FIELDS)),
                wal,
                httpClientFactory,
                miruResourceLocatorProviderLifecyle.getService(),
                //new MiruBitmapsRoaring());
                new MiruBitmapsEWAH(config.getBitsetBufferSize()));

        miruServiceLifecyle.start();
        MiruService miruService = miruServiceLifecyle.getService();

        long t = System.currentTimeMillis();
        while (!miruService.checkInfo(tenantId, partitionId, new MiruPartitionCoordInfo(MiruPartitionState.online, desiredStorage))) {
            Thread.sleep(10);
            if (System.currentTimeMillis() - t > TimeUnit.SECONDS.toMillis(5000)) {
                Assert.fail("Partition failed to come online");
            }
        }

        MiruReader miruReader = new MiruReaderImpl(miruService);
        MiruWriter miruWriter = new MiruWriterImpl(miruService);
        this.miruReaderEndpoints = new MiruReaderEndpoints(miruReader);
        this.miruWriterEndpoints = new MiruWriterEndpoints(miruWriter);
    }

    @Test(enabled = true, description = "Disabled until we can figure out a  better solution for bootstrapping instead of sleeping.")
    public void testSimpleAddActivities() throws Exception {
        AtomicLong time = new AtomicLong(0);
        AtomicInteger index = new AtomicInteger(0);

        miruWriterEndpoints.addActivities(Lists.newArrayList(
                activityFactory.activity(1, partitionId, index.incrementAndGet(),
                        new MiruActivity.Builder(tenantId, time.incrementAndGet(), new String[] { }, 0)
                                .putFieldValue(OBJECT_ID.getFieldName(), "value1")
                                .putFieldValue(AUTHOR_ID.getFieldName(), "value2")
                                .build())));

        List<MiruPartitionedActivity> partitionedActivities = Lists.newArrayList(
                activityFactory.activity(1, partitionId, index.incrementAndGet(),
                        new MiruActivity.Builder(tenantId, time.incrementAndGet(), new String[] { }, 0)
                                .putFieldValue(OBJECT_ID.getFieldName(), "value1")
                                .putFieldValue(AUTHOR_ID.getFieldName(), "value2")
                                .build()
                ),
                activityFactory.activity(1, partitionId, index.incrementAndGet(),
                        new MiruActivity.Builder(tenantId, time.incrementAndGet(), new String[] { }, 0)
                                .putFieldValue(OBJECT_ID.getFieldName(), "value2")
                                .putFieldValue(AUTHOR_ID.getFieldName(), "value3")
                                .build()
                )
        );
        Response addResponse = miruWriterEndpoints.addActivities(partitionedActivities);
        assertNotNull(addResponse);
        assertEquals(addResponse.getStatus(), 200);

        // Request 1
        Response getResponse = miruReaderEndpoints.filterCustomStream(new MiruAggregateCountsQueryParams(new MiruTenantId("tenant1".getBytes(Charsets.UTF_8)),
                Optional.of(new MiruActorId(new Id(1))),
                Optional.<MiruAuthzExpression>absent(),
                new MiruAggregateCountsQueryCriteria.Builder()
                        .setAggregateCountAroundField(OBJECT_ID.getFieldName())
                        .setStreamFilter(new MiruFilter(MiruFilterOperation.or,
                                Optional.of(ImmutableList.<MiruFieldFilter>of(
                                        new MiruFieldFilter(OBJECT_ID.getFieldName(), ImmutableList.<String>of("value2")))),
                                Optional.<ImmutableList<MiruFilter>>absent()
                        ))
                        .build()
        ));
        assertNotNull(getResponse);
        AggregateCountsResult aggregateCountsResult = objectMapper.readValue(getResponse.getEntity().toString(), AggregateCountsResult.class);
        assertEquals(aggregateCountsResult.collectedDistincts, 1);

        // Request 2
        getResponse = miruReaderEndpoints.filterCustomStream(new MiruAggregateCountsQueryParams(new MiruTenantId("tenant1".getBytes(Charsets.UTF_8)),
                Optional.of(new MiruActorId(new Id(1))),
                Optional.<MiruAuthzExpression>absent(),
                new MiruAggregateCountsQueryCriteria.Builder()
                        .setAggregateCountAroundField(OBJECT_ID.getFieldName())
                        .setStreamFilter(new MiruFilter(MiruFilterOperation.or,
                                Optional.of(ImmutableList.<MiruFieldFilter>of(
                                        new MiruFieldFilter(OBJECT_ID.getFieldName(), ImmutableList.<String>of("value2")),
                                        new MiruFieldFilter(AUTHOR_ID.getFieldName(), ImmutableList.<String>of("value2")))),
                                Optional.<ImmutableList<MiruFilter>>absent()
                        ))
                        .build()
        ));
        assertNotNull(getResponse);
        aggregateCountsResult = objectMapper.readValue(getResponse.getEntity().toString(), AggregateCountsResult.class);
        assertEquals(aggregateCountsResult.collectedDistincts, 2);
    }

}
