package com.jivesoftware.os.miru.lumberyard.deployable.region;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.jivesoftware.os.jive.utils.http.client.rest.RequestHelper;
import com.jivesoftware.os.jive.utils.ordered.id.JiveEpochTimestampProvider;
import com.jivesoftware.os.jive.utils.ordered.id.SnowflakeIdPacker;
import com.jivesoftware.os.miru.api.MiruActorId;
import com.jivesoftware.os.miru.api.activity.MiruActivity;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.api.field.MiruFieldType;
import com.jivesoftware.os.miru.api.query.filter.MiruAuthzExpression;
import com.jivesoftware.os.miru.api.query.filter.MiruFieldFilter;
import com.jivesoftware.os.miru.api.query.filter.MiruFilter;
import com.jivesoftware.os.miru.api.query.filter.MiruFilterOperation;
import com.jivesoftware.os.miru.cluster.rcvs.MiruActivityPayloads;
import com.jivesoftware.os.miru.lumberyard.deployable.FreshCutTimber;
import com.jivesoftware.os.miru.lumberyard.deployable.LumberyardSchemaConstants;
import com.jivesoftware.os.miru.lumberyard.deployable.MiruSoyRenderer;
import com.jivesoftware.os.miru.lumberyard.deployable.analytics.MinMaxDouble;
import com.jivesoftware.os.miru.lumberyard.plugins.LumberyardAnswer;
import com.jivesoftware.os.miru.lumberyard.plugins.LumberyardConstants;
import com.jivesoftware.os.miru.lumberyard.plugins.LumberyardQuery;
import com.jivesoftware.os.miru.plugin.solution.MiruRequest;
import com.jivesoftware.os.miru.plugin.solution.MiruResponse;
import com.jivesoftware.os.miru.plugin.solution.MiruSolutionLogLevel;
import com.jivesoftware.os.miru.plugin.solution.MiruTimeRange;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 *
 */
// soy.miru.page.lumberyardQueryPluginRegion
public class LumberyardQueryPluginRegion implements MiruPageRegion<Optional<LumberyardQueryPluginRegion.LumberyardPluginRegionInput>> {

    private static final MetricLogger log = MetricLoggerFactory.getLogger();

    private final String template;
    private final MiruSoyRenderer renderer;
    private final RequestHelper[] miruReaders;
    private final MiruActivityPayloads activityPayloads;

    public LumberyardQueryPluginRegion(String template,
        MiruSoyRenderer renderer,
        RequestHelper[] miruReaders,
        MiruActivityPayloads activityPayloads) {
        this.template = template;
        this.renderer = renderer;
        this.miruReaders = miruReaders;
        this.activityPayloads = activityPayloads;
    }

    public static class LumberyardPluginRegionInput {

        final String cluster;
        final String host;
        final String service;
        final String instance;
        final String version;

        final String logLevel;
        final int fromAgo;
        final int toAgo;
        final String fromTimeUnit;
        final String toTimeUnit;

        final String thread;
        final String logger;
        final String message;
        final String thrown;

        final int buckets;
        final int messageCount;

        public LumberyardPluginRegionInput(String cluster,
            String host,
            String version,
            String service,
            String instance,
            String logLevel,
            int fromAgo,
            int toAgo,
            String fromTimeUnit,
            String toTimeUnit,
            String thread,
            String logger,
            String message,
            String thrown,
            int buckets,
            int messageCount) {

            this.cluster = cluster;
            this.host = host;
            this.version = version;
            this.service = service;
            this.instance = instance;
            this.logLevel = logLevel;
            this.fromAgo = fromAgo;
            this.toAgo = toAgo;
            this.fromTimeUnit = fromTimeUnit;
            this.toTimeUnit = toTimeUnit;
            this.thread = thread;
            this.logger = logger;
            this.message = message;
            this.thrown = thrown;
            this.buckets = buckets;
            this.messageCount = messageCount;
        }

    }

    @Override
    public String render(Optional<LumberyardPluginRegionInput> optionalInput) {
        Map<String, Object> data = Maps.newHashMap();
        try {
            if (optionalInput.isPresent()) {
                LumberyardPluginRegionInput input = optionalInput.get();
                int fromAgo = input.fromAgo > input.toAgo ? input.fromAgo : input.toAgo;
                int toAgo = input.fromAgo > input.toAgo ? input.toAgo : input.fromAgo;

                data.put("cluster", input.cluster);
                data.put("host", input.host);
                data.put("service", input.service);
                data.put("instance", input.instance);
                data.put("version", input.version);
                data.put("fromTimeUnit", input.fromTimeUnit);
                data.put("toTimeUnit", input.toTimeUnit);
                data.put("thread", input.thread);
                data.put("logger", input.logger);
                data.put("message", input.message);
                data.put("thrown", input.thrown);

                data.put("logLevel", input.logLevel);
                data.put("fromAgo", String.valueOf(fromAgo));
                data.put("toAgo", String.valueOf(toAgo));
                data.put("buckets", String.valueOf(input.buckets));
                data.put("desiredNumberOfResultsPerWaveform", String.valueOf(input.messageCount));

                SnowflakeIdPacker snowflakeIdPacker = new SnowflakeIdPacker();
                long jiveCurrentTime = new JiveEpochTimestampProvider().getTimestamp();
                final long packCurrentTime = snowflakeIdPacker.pack(jiveCurrentTime, 0, 0);
                final long fromTime = packCurrentTime - snowflakeIdPacker.pack(TimeUnit.valueOf(input.fromTimeUnit).toMillis(fromAgo), 0, 0);
                final long toTime = packCurrentTime - snowflakeIdPacker.pack(TimeUnit.valueOf(input.toTimeUnit).toMillis(toAgo), 0, 0);

                MiruTenantId tenantId = LumberyardSchemaConstants.TENANT_ID;
                MiruResponse<LumberyardAnswer> response = null;
                for (RequestHelper requestHelper : miruReaders) {
                    try {
                        List<MiruFieldFilter> fieldFilters = Lists.newArrayList();
                        addFieldFilter(fieldFilters, "cluster", input.cluster);
                        addFieldFilter(fieldFilters, "host", input.host);
                        addFieldFilter(fieldFilters, "service", input.service);
                        addFieldFilter(fieldFilters, "instance", input.instance);
                        addFieldFilter(fieldFilters, "version", input.version);
                        addFieldFilter(fieldFilters, "thread", input.thread);
                        addFieldFilter(fieldFilters, "logger", input.logger);
                        addFieldFilter(fieldFilters, "message", input.message);
                        addFieldFilter(fieldFilters, "level", input.logLevel);
                        addFieldFilter(fieldFilters, "thrownStackTrace", input.thrown);

                        ImmutableMap<String, MiruFilter> lumberyardFilters = ImmutableMap.of(
                            "stumptown",
                            new MiruFilter(MiruFilterOperation.and,
                                false,
                                fieldFilters,
                                null));

                        @SuppressWarnings("unchecked")
                        MiruResponse<LumberyardAnswer> analyticsResponse = requestHelper.executeRequest(
                            new MiruRequest<>(tenantId, MiruActorId.NOT_PROVIDED, MiruAuthzExpression.NOT_PROVIDED,
                                new LumberyardQuery(
                                    new MiruTimeRange(fromTime, toTime),
                                    input.buckets,
                                    input.messageCount,
                                    MiruFilter.NO_FILTER,
                                    lumberyardFilters),
                                MiruSolutionLogLevel.valueOf(input.logLevel)),
                            LumberyardConstants.LUMBERYARD_PREFIX + LumberyardConstants.CUSTOM_QUERY_ENDPOINT, MiruResponse.class,
                            new Class[] { LumberyardAnswer.class },
                            null);
                        response = analyticsResponse;
                        if (response != null && response.answer != null) {
                            break;
                        } else {
                            log.warn("Empty lumberyard response from {}, trying another", requestHelper);
                        }
                    } catch (Exception e) {
                        log.warn("Failed lumberyard request to {}, trying another", new Object[] { requestHelper }, e);
                    }
                }

                if (response != null && response.answer != null) {
                    Map<String, LumberyardAnswer.Waveform> waveforms = response.answer.waveforms;
                    if (waveforms == null) {
                        waveforms = Collections.emptyMap();
                    }
                    data.put("elapse", String.valueOf(response.totalElapsed));
                    //data.put("waveform", waveform == null ? "" : waveform.toString());

                    data.put("waveform", "data:image/png;base64," + new PNGWaveforms().hitsToBase64PNGWaveform(1024, 200, 32, waveforms,
                        Optional.<MinMaxDouble>absent()));

                    List<Long> activityTimes = Lists.newArrayList();
                    for (LumberyardAnswer.Waveform waveform : waveforms.values()) {
                        for (MiruActivity activity : waveform.results) {
                            activityTimes.add(activity.time);
                        }
                    }
                    List<FreshCutTimber> timbers = activityPayloads.multiGet(tenantId, activityTimes, FreshCutTimber.class);
                    List<String> events = Lists.newArrayList();
                    for (FreshCutTimber timber : timbers) {
                        if (timber.message != null) {
                            events.add(timber.message);
                        }
                        if (timber.thrownStackTrace != null) {
                            events.add(Joiner.on('\n').join(timber.thrownStackTrace));
                        }
                    }
                    data.put("events", events);

                    ObjectMapper mapper = new ObjectMapper();
                    mapper.enable(SerializationFeature.INDENT_OUTPUT);
                    data.put("summary", Joiner.on("\n").join(response.log) + "\n\n" + mapper.writeValueAsString(response.solutions));
                }
            }
        } catch (Exception e) {
            log.error("Unable to retrieve data", e);
        }

        return renderer.render(template, data);
    }

    private void addFieldFilter(List<MiruFieldFilter> fieldFilters, String fieldName, String values) {
        if (values != null) {
            String[] valueArray = values.split("\\s*,\\s*");
            if (valueArray.length != 0) {
                fieldFilters.add(new MiruFieldFilter(MiruFieldType.primary, fieldName, Arrays.asList(valueArray)));
            }
        }
    }

    @Override
    public String getTitle() {
        return "Query";
    }
}
