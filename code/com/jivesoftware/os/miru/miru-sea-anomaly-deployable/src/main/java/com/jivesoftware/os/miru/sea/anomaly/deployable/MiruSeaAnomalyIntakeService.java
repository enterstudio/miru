/*
 * Copyright 2015 jonathan.colt.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jivesoftware.os.miru.sea.anomaly.deployable;

import com.google.common.collect.Lists;
import com.jivesoftware.os.jive.utils.health.api.HealthFactory;
import com.jivesoftware.os.jive.utils.health.api.HealthTimer;
import com.jivesoftware.os.jive.utils.health.api.TimerHealthCheckConfig;
import com.jivesoftware.os.jive.utils.health.checkers.TimerHealthChecker;
import com.jivesoftware.os.jive.utils.http.client.rest.RequestHelper;
import com.jivesoftware.os.miru.api.activity.MiruActivity;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.metric.sampler.AnomalyMetric;
import com.jivesoftware.os.miru.sea.anomaly.deployable.storage.MiruSeaAnomalyPayloads;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.List;
import java.util.Random;
import org.merlin.config.defaults.DoubleDefault;
import org.merlin.config.defaults.StringDefault;

/**
 * @author jonathan.colt
 */
public class MiruSeaAnomalyIntakeService {

    public static interface IngressLatency extends TimerHealthCheckConfig {

        @StringDefault("ingress>latency")
        @Override
        String getName();

        @StringDefault("How long its taking to ingress batches of logEvents.")
        @Override
        String getDescription();

        @DoubleDefault(30000d) /// 30sec
        @Override
        Double get95ThPecentileMax();
    }

    private static final HealthTimer ingressLatency = HealthFactory.getHealthTimer(IngressLatency.class, TimerHealthChecker.FACTORY);
    private static final MetricLogger log = MetricLoggerFactory.getLogger();

    private final Random rand = new Random();
    private final SeaAnomalySchemaService seaAnomalySchemaService;
    private final SampleTrawl logMill;
    private final String miruIngressEndpoint;
    private final RequestHelper[] miruWriter;
    private final MiruSeaAnomalyPayloads payloads;

    public MiruSeaAnomalyIntakeService(SeaAnomalySchemaService seaAnomalySchemaService,
        SampleTrawl logMill,
        String miruIngressEndpoint,
        RequestHelper[] miruWrites,
        MiruSeaAnomalyPayloads payloads) {
        this.seaAnomalySchemaService = seaAnomalySchemaService;
        this.logMill = logMill;
        this.miruIngressEndpoint = miruIngressEndpoint;
        this.miruWriter = miruWrites;
        this.payloads = payloads;
    }

    void ingressEvents(List<AnomalyMetric> events) throws Exception {
        List<MiruActivity> activities = Lists.newArrayListWithCapacity(events.size());
        List<MiruSeaAnomalyPayloads.TimeAndPayload<AnomalyMetric>> timedLogEvents = Lists.newArrayListWithCapacity(events.size());
        for (AnomalyMetric logEvent : events) {
            MiruTenantId tenantId = SeaAnomalySchemaConstants.TENANT_ID;
            seaAnomalySchemaService.ensureSchema(tenantId, SeaAnomalySchemaConstants.SCHEMA);
            MiruActivity activity = logMill.trawl(tenantId, logEvent);
            if (activity != null) {
                activities.add(activity);
                timedLogEvents.add(new MiruSeaAnomalyPayloads.TimeAndPayload<>(activity.time, logEvent));
            }
        }
        if (!activities.isEmpty()) {
            ingress(activities);
            record(timedLogEvents);
            log.inc("ingressed", timedLogEvents.size());
            log.info("Ingressed " + timedLogEvents.size());
        }
    }

    private void ingress(List<MiruActivity> activities) {
        int index = 0;
        ingressLatency.startTimer();
        try {
            while (true) {
                try {
                    index = rand.nextInt(miruWriter.length);
                    RequestHelper requestHelper = miruWriter[index];
                    requestHelper.executeRequest(activities, miruIngressEndpoint, String.class, null);
                    log.inc("ingressed");
                    break;
                } catch (Exception x) {
                    try {
                        log.error("Failed to forward ingress to miru at index=" + index + ". Will retry shortly....", x);
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) {
                        Thread.interrupted();
                        return;
                    }
                }
            }
        } finally {
            ingressLatency.stopTimer("Ingress " + activities.size(), "Add more seaAnomaly services or fix down stream issue.");
        }
    }

    private void record(List<MiruSeaAnomalyPayloads.TimeAndPayload<AnomalyMetric>> timedEvents) throws Exception {
        payloads.multiPut(SeaAnomalySchemaConstants.TENANT_ID, timedEvents);
    }
}
