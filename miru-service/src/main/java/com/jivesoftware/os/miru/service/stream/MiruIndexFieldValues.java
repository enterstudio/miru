package com.jivesoftware.os.miru.service.stream;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multiset;
import com.jivesoftware.os.filer.io.api.StackBuffer;
import com.jivesoftware.os.miru.api.activity.schema.MiruFieldDefinition;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.api.base.MiruTermId;
import com.jivesoftware.os.miru.api.field.MiruFieldType;
import com.jivesoftware.os.miru.plugin.index.MiruActivityAndId;
import com.jivesoftware.os.miru.plugin.index.MiruFieldIndex;
import com.jivesoftware.os.miru.plugin.index.MiruInternalActivity;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import gnu.trove.list.TIntList;
import gnu.trove.list.TLongList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.list.array.TLongArrayList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 *
 */
public class MiruIndexFieldValues<BM> {

    private final static MetricLogger log = MetricLoggerFactory.getLogger();

    public List<Future<List<FieldValuesWork>>> compose(MiruContext<BM, ?> context,
        final List<MiruActivityAndId<MiruInternalActivity>> internalActivityAndIds,
        ExecutorService indexExecutor)
        throws Exception {

        MiruFieldDefinition[] fieldDefinitions = context.schema.getFieldDefinitions();
        List<Future<List<FieldValuesWork>>> workFutures = new ArrayList<>(fieldDefinitions.length);
        for (final MiruFieldDefinition fieldDefinition : fieldDefinitions) {
            if (!fieldDefinition.type.hasFeature(MiruFieldDefinition.Feature.indexed)) {
                continue;
            }
            boolean hasCardinality = fieldDefinition.type.hasFeature(MiruFieldDefinition.Feature.cardinality);
            workFutures.add(indexExecutor.submit(() -> {
                Map<MiruTermId, TermWork> fieldWork = Maps.newHashMap();
                for (MiruActivityAndId<MiruInternalActivity> internalActivityAndId : internalActivityAndIds) {
                    MiruInternalActivity activity = internalActivityAndId.activity;
                    MiruTermId[] fieldValues = activity.fieldsValues[fieldDefinition.fieldId];
                    if (fieldValues != null) {
                        if (fieldValues.length > 1 && !fieldDefinition.type.hasFeature(MiruFieldDefinition.Feature.multiValued)) {
                            log.warn("Activity {} field {} type {} is not multi-valued but was given {} terms", internalActivityAndId.activity.time,
                                fieldDefinition.name, fieldDefinition.type, fieldValues.length);
                        }
                        if (hasCardinality) {
                            HashMultiset<MiruTermId> multiset = HashMultiset.create();
                            Collections.addAll(multiset, fieldValues);
                            for (Multiset.Entry<MiruTermId> entry : multiset.entrySet()) {
                                MiruTermId term = entry.getElement();
                                TermWork work = fieldWork.get(term);
                                if (work == null) {
                                    work = new TermWork(true);
                                    fieldWork.put(term, work);
                                }
                                work.ids.add(internalActivityAndId.id);
                                work.counts.add(entry.getCount());
                            }
                        } else {
                            for (MiruTermId term : fieldValues) {
                                TermWork work = fieldWork.get(term);
                                if (work == null) {
                                    work = new TermWork(false);
                                    fieldWork.put(term, work);
                                }
                                work.ids.add(internalActivityAndId.id);
                            }
                        }
                    }
                }
                List<FieldValuesWork> workList = Lists.newArrayListWithCapacity(fieldWork.size());
                for (Map.Entry<MiruTermId, TermWork> entry : fieldWork.entrySet()) {
                    TermWork work = entry.getValue();
                    workList.add(new FieldValuesWork(entry.getKey(), work.ids, work.counts));
                }
                return workList;
            }));
        }
        return workFutures;
    }

    public List<Future<?>> index(final MiruContext<BM, ?> context,
        MiruTenantId tenantId,
        List<Future<List<FieldValuesWork>>> fieldWorkFutures,
        final boolean repair,
        ExecutorService indexExecutor)
        throws Exception {

        List<FieldValuesWork>[] work = awaitFieldWorkFutures(fieldWorkFutures);

        final MiruFieldIndex<BM> fieldIndex = context.getFieldIndexProvider().getFieldIndex(MiruFieldType.primary);
        List<Integer> fieldIds = context.schema.getFieldIds();
        List<Future<?>> futures = new ArrayList<>(fieldIds.size());
        for (int fieldId = 0; fieldId < work.length; fieldId++) {
            List<FieldValuesWork> fieldWork = work[fieldId];
            final int finalFieldId = fieldId;
            for (final FieldValuesWork fieldValuesWork : fieldWork) {
                futures.add(indexExecutor.submit(() -> {
                    StackBuffer stackBuffer = new StackBuffer();
                    if (repair) {
                        log.inc("count>set", fieldValuesWork.ids.size());
                        log.inc("count>set", fieldValuesWork.ids.size(), tenantId.toString());
                        fieldIndex.set(finalFieldId,
                            fieldValuesWork.fieldValue,
                            fieldValuesWork.ids.toArray(),
                            fieldValuesWork.counts != null ? fieldValuesWork.counts.toArray() : null,
                            stackBuffer);
                    } else {
                        log.inc("count>append", fieldValuesWork.ids.size());
                        log.inc("count>append", fieldValuesWork.ids.size(), tenantId.toString());
                        fieldIndex.append(finalFieldId,
                            fieldValuesWork.fieldValue,
                            fieldValuesWork.ids.toArray(),
                            fieldValuesWork.counts != null ? fieldValuesWork.counts.toArray() : null,
                            stackBuffer);
                    }
                    return null;
                }));
            }
        }
        return futures;
    }

    private List<FieldValuesWork>[] awaitFieldWorkFutures(List<Future<List<FieldValuesWork>>> fieldWorkFutures)
        throws InterruptedException, ExecutionException {

        @SuppressWarnings("unchecked")
        List<FieldValuesWork>[] fieldsWork = new List[fieldWorkFutures.size()];
        for (int i = 0; i < fieldWorkFutures.size(); i++) {
            fieldsWork[i] = fieldWorkFutures.get(i).get();
            Collections.sort(fieldsWork[i]);
        }
        return fieldsWork;
    }

    private static class TermWork {

        private final TIntList ids = new TIntArrayList();
        private final TLongList counts;

        public TermWork(boolean hasCardinality) {
            this.counts = hasCardinality ? new TLongArrayList() : null;
        }
    }

}
