/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.backend.tx;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.util.Strings;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

import com.baidu.hugegraph.HugeException;
import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.HugeGraphParams;
import com.baidu.hugegraph.analyzer.Analyzer;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.backend.page.IdHolder;
import com.baidu.hugegraph.backend.page.IdHolder.BatchIdHolder;
import com.baidu.hugegraph.backend.page.IdHolder.FixedIdHolder;
import com.baidu.hugegraph.backend.page.IdHolder.PagingIdHolder;
import com.baidu.hugegraph.backend.page.IdHolderList;
import com.baidu.hugegraph.backend.page.PageIds;
import com.baidu.hugegraph.backend.page.PageInfo;
import com.baidu.hugegraph.backend.page.PageState;
import com.baidu.hugegraph.backend.page.SortByCountIdHolderList;
import com.baidu.hugegraph.backend.query.Condition;
import com.baidu.hugegraph.backend.query.Condition.RangeConditions;
import com.baidu.hugegraph.backend.query.Condition.Relation;
import com.baidu.hugegraph.backend.query.Condition.RelationType;
import com.baidu.hugegraph.backend.query.ConditionQuery;
import com.baidu.hugegraph.backend.query.ConditionQuery.OptimizedType;
import com.baidu.hugegraph.backend.query.ConditionQueryFlatten;
import com.baidu.hugegraph.backend.query.Query;
import com.baidu.hugegraph.backend.query.QueryResults;
import com.baidu.hugegraph.backend.serializer.AbstractSerializer;
import com.baidu.hugegraph.backend.store.BackendEntry;
import com.baidu.hugegraph.backend.store.BackendStore;
import com.baidu.hugegraph.config.CoreOptions;
import com.baidu.hugegraph.config.HugeConfig;
import com.baidu.hugegraph.exception.NoIndexException;
import com.baidu.hugegraph.exception.NotAllowException;
import com.baidu.hugegraph.exception.NotSupportException;
import com.baidu.hugegraph.iterator.Metadatable;
import com.baidu.hugegraph.job.EphemeralJob;
import com.baidu.hugegraph.job.EphemeralJobBuilder;
import com.baidu.hugegraph.job.system.DeleteExpiredJob;
import com.baidu.hugegraph.perf.PerfUtil.Watched;
import com.baidu.hugegraph.schema.IndexLabel;
import com.baidu.hugegraph.schema.PropertyKey;
import com.baidu.hugegraph.schema.SchemaLabel;
import com.baidu.hugegraph.structure.HugeEdge;
import com.baidu.hugegraph.structure.HugeElement;
import com.baidu.hugegraph.structure.HugeIndex;
import com.baidu.hugegraph.structure.HugeProperty;
import com.baidu.hugegraph.structure.HugeVertex;
import com.baidu.hugegraph.task.HugeTask;
import com.baidu.hugegraph.type.HugeType;
import com.baidu.hugegraph.type.define.Action;
import com.baidu.hugegraph.type.define.HugeKeys;
import com.baidu.hugegraph.type.define.IndexType;
import com.baidu.hugegraph.util.CollectionUtil;
import com.baidu.hugegraph.util.E;
import com.baidu.hugegraph.util.InsertionOrderUtil;
import com.baidu.hugegraph.util.LockUtil;
import com.baidu.hugegraph.util.LongEncoding;
import com.baidu.hugegraph.util.NumericUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

public class GraphIndexTransaction extends AbstractTransaction {

    public static final char INDEX_SYM_ENDING = '\u0000';
    public static final String INDEX_SYM_NULL = "\u0001";
    public static final String INDEX_SYM_EMPTY = "\u0002";
    public static final char INDEX_SYM_MAX = '\u0003';

    private final Analyzer textAnalyzer;
    private final int indexIntersectThresh;

    public GraphIndexTransaction(HugeGraphParams graph, BackendStore store) {
        super(graph, store);

        this.textAnalyzer = graph.analyzer();
        assert this.textAnalyzer != null;

        final HugeConfig conf = graph.configuration();
        this.indexIntersectThresh =
             conf.get(CoreOptions.QUERY_INDEX_INTERSECT_THRESHOLD);
    }

    protected Id asyncRemoveIndexLeft(ConditionQuery query,
                                      HugeElement element) {
        LOG.info("Remove left index: {}, query: {}", element, query);
        RemoveLeftIndexJob job = new RemoveLeftIndexJob(query, element);
        HugeTask<?> task = EphemeralJobBuilder.of(this.graph())
                                              .name(element.id().asString())
                                              .job(job)
                                              .schedule();
        return task.id();
    }

    @Watched(prefix = "index")
    public void updateLabelIndex(HugeElement element, boolean removed) {
        if (element instanceof HugeVertex && ((HugeVertex) element).olap()) {
            return;
        }
        if (!this.needIndexForLabel()) {
            return;
        }

        // Don't update label index if it's not enabled
        SchemaLabel label = element.schemaLabel();
        if (!label.enableLabelIndex()) {
            return;
        }

        // Update label index if backend store not supports label-query
        HugeIndex index = new HugeIndex(this.graph(),
                                        IndexLabel.label(element.type()));
        index.fieldValues(element.schemaLabel().id());
        index.elementIds(element.id(), element.expiredTime());

        if (removed) {
            this.doEliminate(this.serializer.writeIndex(index));
        } else {
            this.doAppend(this.serializer.writeIndex(index));
        }
    }

    @Watched(prefix = "index")
    public void updateVertexIndex(HugeVertex vertex, boolean removed) {
        if (vertex.olap()) {
            this.updateVertexOlapIndex(vertex, removed);
            return;
        }
        // Update index(only property, no edge) of a vertex
        for (Id id : vertex.schemaLabel().indexLabels()) {
            this.updateIndex(id, vertex, removed);
        }
    }

    @Watched(prefix = "index")
    public void updateEdgeIndex(HugeEdge edge, boolean removed) {
        // Update index of an edge
        for (Id id : edge.schemaLabel().indexLabels()) {
            this.updateIndex(id, edge, removed);
        }
    }

    private void updateVertexOlapIndex(HugeVertex vertex, boolean removed) {
        Set<Id> propKeys = vertex.getPropertyKeys();
        E.checkArgument(propKeys.size() == 1,
                        "Expect only 1 property for olap vertex, but got %s",
                        propKeys.size());
        Id pkId = propKeys.iterator().next();
        List<IndexLabel> indexLabels = this.params().schemaTransaction()
                                           .getIndexLabels();
        for (IndexLabel il : indexLabels) {
            if (il.indexFields().contains(pkId)) {
                this.updateIndex(il.id(), vertex, removed);
            }
        }
    }

    /**
     * Update index(user properties) of vertex or edge
     * @param ilId      the id of index label
     * @param element   the properties owner
     * @param removed   remove or add index
     */
    protected void updateIndex(Id ilId, HugeElement element, boolean removed) {
        SchemaTransaction schema = this.params().schemaTransaction();
        IndexLabel indexLabel = schema.getIndexLabel(ilId);
        E.checkArgument(indexLabel != null,
                        "Not exist index label with id '%s'", ilId);

        // Collect property values of index fields
        List<Object> allPropValues = new ArrayList<>();
        int fieldsNum = indexLabel.indexFields().size();
        int firstNullField = fieldsNum;
        for (Id fieldId : indexLabel.indexFields()) {
            HugeProperty<Object> property = element.getProperty(fieldId);
            if (property == null) {
                E.checkState(hasNullableProp(element, fieldId),
                             "Non-null property '%s' is null for '%s'",
                             this.graph().propertyKey(fieldId) , element);
                if (firstNullField == fieldsNum) {
                    firstNullField = allPropValues.size();
                }
                allPropValues.add(INDEX_SYM_NULL);
            } else {
                E.checkArgument(!INDEX_SYM_NULL.equals(property.value()),
                                "Illegal value of index property: '%s'",
                                INDEX_SYM_NULL);
                allPropValues.add(property.value());
            }
        }

        if (firstNullField == 0 && !indexLabel.indexType().isUnique()) {
            // The property value of first index field is null
            return;
        }
        // Not build index for record with nullable field (except unique index)
        List<Object> propValues = allPropValues.subList(0, firstNullField);

        // Expired time
        long expiredTime = element.expiredTime();

        // Update index for each index type
        switch (indexLabel.indexType()) {
            case RANGE_INT:
            case RANGE_FLOAT:
            case RANGE_LONG:
            case RANGE_DOUBLE:
                E.checkState(propValues.size() == 1,
                             "Expect only one property in range index");
                Object value = NumericUtil.convertToNumber(propValues.get(0));
                this.updateIndex(indexLabel, value, element.id(),
                                 expiredTime, removed);
                break;
            case SEARCH:
                E.checkState(propValues.size() == 1,
                             "Expect only one property in search index");
                value = propValues.get(0);
                Set<String> words =
                            this.segmentWords(propertyValueToString(value));
                for (String word : words) {
                    this.updateIndex(indexLabel, word, element.id(),
                                     expiredTime, removed);
                }
                break;
            case SECONDARY:
                // Secondary index maybe include multi prefix index
                if (isCollectionIndex(propValues)) {
                    /*
                     * Property value is a collection
                     * we should create index for each item
                     */
                    for (Object propValue : (Collection<?>) propValues.get(0)) {
                        value = ConditionQuery.concatValues(propValue);
                        value = escapeIndexValueIfNeeded((String) value);
                        this.updateIndex(indexLabel, value, element.id(),
                                         expiredTime, removed);
                    }
                } else {
                    for (int i = 0, n = propValues.size(); i < n; i++) {
                        List<Object> prefixValues =
                                     propValues.subList(0, i + 1);
                        value = ConditionQuery.concatValues(prefixValues);
                        value = escapeIndexValueIfNeeded((String) value);
                        this.updateIndex(indexLabel, value, element.id(),
                                         expiredTime, removed);
                    }
                }
                break;
            case SHARD:
                value = ConditionQuery.concatValues(propValues);
                value = escapeIndexValueIfNeeded((String) value);
                this.updateIndex(indexLabel, value, element.id(),
                                 expiredTime, removed);
                break;
            case UNIQUE:
                value = ConditionQuery.concatValues(allPropValues);
                assert !value.equals("");
                Id id = element.id();
                // TODO: add lock for updating unique index
                if (!removed && this.existUniqueValue(indexLabel, value, id)) {
                    throw new IllegalArgumentException(String.format(
                              "Unique constraint %s conflict is found for %s",
                              indexLabel, element));
                }
                this.updateIndex(indexLabel, value, element.id(),
                                 expiredTime, removed);
                break;
            default:
                throw new AssertionError(String.format(
                          "Unknown index type '%s'", indexLabel.indexType()));
        }
    }

    private void updateIndex(IndexLabel indexLabel, Object propValue,
                             Id elementId, long expiredTime, boolean removed) {
        HugeIndex index = new HugeIndex(this.graph(), indexLabel);
        index.fieldValues(propValue);
        index.elementIds(elementId, expiredTime);

        if (removed) {
            this.doEliminate(this.serializer.writeIndex(index));
        } else {
            this.doAppend(this.serializer.writeIndex(index));
        }
    }

    private boolean existUniqueValue(IndexLabel indexLabel,
                                     Object value, Id id) {
        return !this.hasEliminateInTx(indexLabel, value, id) &&
               this.existUniqueValueInStore(indexLabel, value);
    }

    private boolean hasEliminateInTx(IndexLabel indexLabel, Object value,
                                     Id elementId) {
        HugeIndex index = new HugeIndex(this.graph(), indexLabel);
        index.fieldValues(value);
        index.elementIds(elementId);
        BackendEntry entry = this.serializer.writeIndex(index);
        return this.mutation().contains(entry, Action.ELIMINATE);
    }

    private boolean existUniqueValueInStore(IndexLabel indexLabel,
                                            Object value) {
        ConditionQuery query = new ConditionQuery(HugeType.UNIQUE_INDEX);
        query.eq(HugeKeys.INDEX_LABEL_ID, indexLabel.id());
        query.eq(HugeKeys.FIELD_VALUES, value);
        boolean exist;
        Iterator<BackendEntry> iterator = this.query(query).iterator();
        try {
            exist = iterator.hasNext();
            if (exist) {
                HugeIndex index = this.serializer.readIndex(graph(), query,
                                                            iterator.next());
                this.removeExpiredIndexIfNeeded(index, query.showExpired());
                // Memory backend might return empty BackendEntry
                if (index.elementIds().isEmpty()) {
                    return false;
                }
                LOG.debug("Already has existed unique index record {}",
                          index.elementId());
            }
            while (iterator.hasNext()) {
                LOG.warn("Unique constraint conflict found by record {}",
                         iterator.next());
            }
        } finally {
            CloseableIterator.closeIterator(iterator);
        }
        return exist;
    }

    /**
     * Composite index, an index involving multiple columns.
     * Single index, an index involving only one column.
     * Joint indexes, join of single indexes, composite indexes or mixed
     * of single indexes and composite indexes.
     * @param query original condition query
     * @return      converted id query
     */
    @Watched(prefix = "index")
    public IdHolderList queryIndex(ConditionQuery query) {
        // Index query must have been flattened in Graph tx
        query.checkFlattened();

        // NOTE: Currently we can't support filter changes in memory
        if (this.hasUpdate()) {
            throw new HugeException("Can't do index query when " +
                                    "there are changes in transaction");
        }

        // Can't query by index and by non-label sysprop at the same time
        List<Condition> conds = query.syspropConditions();
        if (conds.size() > 1 ||
            (conds.size() == 1 && !query.containsCondition(HugeKeys.LABEL))) {
            throw new HugeException("Can't do index query with %s and %s",
                                    conds, query.userpropConditions());
        }

        // Query by index
        query.optimized(OptimizedType.INDEX);
        if (query.allSysprop() && conds.size() == 1 &&
            query.containsCondition(HugeKeys.LABEL)) {
            // Query only by label
            return this.queryByLabel(query);
        } else {
            // Query by userprops (or userprops + label)
            return this.queryByUserprop(query);
        }
    }

    @Watched(prefix = "index")
    private IdHolderList queryByLabel(ConditionQuery query) {
        HugeType queryType = query.resultType();
        IndexLabel il = IndexLabel.label(queryType);
        validateIndexLabel(il);
        Id label = query.condition(HugeKeys.LABEL);
        assert label != null;

        HugeType indexType;
        SchemaLabel schemaLabel;
        if (queryType.isVertex()) {
            indexType = HugeType.VERTEX_LABEL_INDEX;
            schemaLabel = this.graph().vertexLabel(label);
        } else if (queryType.isEdge()) {
            indexType = HugeType.EDGE_LABEL_INDEX;
            schemaLabel = this.graph().edgeLabel(label);
        } else {
            throw new HugeException("Can't query %s by label", queryType);
        }

        if (!this.store().features().supportsQueryByLabel() &&
            !schemaLabel.enableLabelIndex()) {
            throw new NoIndexException("Don't accept query by label '%s', " +
                                       "label index is disabled", schemaLabel);
        }

        ConditionQuery indexQuery;
        indexQuery = new ConditionQuery(indexType , query);
        indexQuery.eq(HugeKeys.INDEX_LABEL_ID, il.id());
        indexQuery.eq(HugeKeys.FIELD_VALUES, label);
        /*
         * We can avoid redundant element ids if set limit, but if there are
         * label index overridden by other vertices with different label,
         * query with limit like g.V().hasLabel('xx').limit(10) may lose some
         * results, so can't set limit here. But in this case, the following
         * query results may be still different:
         *   g.V().hasLabel('xx').count()  // label index count
         *   g.V().hasLabel('xx').limit(-1).count()  // actual vertices count
         * It’s a similar situation for the offset, like:
         *   g.V().hasLabel('xx').range(26, 27)
         *   g.V().hasLabel('xx').range(27, 28)
         * we just reset limit here, but don't reset offset due to performance
         * optimization with index+offset query, see Query.skipOffsetIfNeeded().
         * NOTE: if set offset the backend itself will skip the offset
         */
        indexQuery.copyBasic(query);
        indexQuery.limit(Query.NO_LIMIT);

        IdHolder idHolder = this.doIndexQuery(il, indexQuery);

        IdHolderList holders = new IdHolderList(query.paging());
        holders.add(idHolder);
        return holders;
    }

    @Watched(prefix = "index")
    private IdHolderList queryByUserprop(ConditionQuery query) {
        // Get user applied label or collect all qualified labels with
        // related index labels
        if (!this.graph().readMode().showOlap()) {
            for (Id pkId : query.userpropKeys()) {
                PropertyKey propertyKey = this.graph().propertyKey(pkId);
                if (propertyKey.olap()) {
                    throw new NotAllowException(
                              "Not allowed to query by olap property key '%s'" +
                              " when graph-read-mode is '%s'",
                              propertyKey, this.graph().readMode());
                }
            }
        }
        Set<MatchedIndex> indexes = this.collectMatchedIndexes(query);
        if (indexes.isEmpty()) {
            Id label = query.condition(HugeKeys.LABEL);
            throw noIndexException(this.graph(), query, label);
        }

        // Value type of Condition not matched
        boolean paging = query.paging();
        if (!validQueryConditionValues(this.graph(), query)) {
            return IdHolderList.empty(paging);
        }

        // Do index query
        IdHolderList holders = new IdHolderList(paging);
        for (MatchedIndex index : indexes) {
            for (IndexLabel il : index.indexLabels()) {
                validateIndexLabel(il);
            }
            if (paging && index.indexLabels().size() > 1) {
                throw new NotSupportException("joint index query in paging");
            }

            if (index.containsSearchIndex()) {
                // Do search-index query
                holders.addAll(this.doSearchIndex(query, index));
            } else {
                // Do secondary-index, range-index or shard-index query
                IndexQueries queries = index.constructIndexQueries(query);
                assert !paging || queries.size() <= 1;
                IdHolder holder = this.doSingleOrJointIndex(queries);
                holders.add(holder);
            }

            /*
             * NOTE: need to skip the offset if offset > 0, but can't handle
             * it here because the query may a sub-query after flatten,
             * so the offset will be handle in QueryList.IndexQuery
             *
             * TODO: finish early here if records exceeds required limit with
             *       FixedIdHolder.
             */
        }
        return holders;
    }

    @Watched(prefix = "index")
    private IdHolderList doSearchIndex(ConditionQuery query,
                                       MatchedIndex index) {
        query = this.constructSearchQuery(query, index);
        // Sorted by matched count
        IdHolderList holders = new SortByCountIdHolderList(query.paging());
        List<ConditionQuery> flatten = ConditionQueryFlatten.flatten(query);
        for (ConditionQuery q : flatten) {
            if (!q.noLimit() && flatten.size() > 1) {
                // Increase limit for union operation
                increaseLimit(q);
            }
            IndexQueries queries = index.constructIndexQueries(q);
            assert !query.paging() || queries.size() <= 1;
            IdHolder holder = this.doSingleOrJointIndex(queries);
            // NOTE: ids will be merged into one IdHolder if not in paging
            holders.add(holder);
        }
        return holders;
    }

    @Watched(prefix = "index")
    private IdHolder doSingleOrJointIndex(IndexQueries queries) {
        if (queries.size() == 1) {
            return this.doSingleOrCompositeIndex(queries);
        } else {
            return this.doJointIndex(queries);
        }
    }

    @Watched(prefix = "index")
    private IdHolder doSingleOrCompositeIndex(IndexQueries queries) {
        assert queries.size() == 1;
        Map.Entry<IndexLabel, ConditionQuery> entry = queries.one();
        IndexLabel indexLabel = entry.getKey();
        ConditionQuery query = entry.getValue();
        return this.doIndexQuery(indexLabel, query);
    }

    @Watched(prefix = "index")
    private IdHolder doJointIndex(IndexQueries queries) {
        if (queries.oomRisk()) {
            LOG.warn("There is OOM risk if the joint operation is based on a " +
                     "large amount of data, please use single index + filter " +
                     "instead of joint index: {}", queries.rootQuery());
        }
        // All queries are joined with AND
        Set<Id> intersectIds = null;
        boolean filtering = false;
        IdHolder resultHolder = null;
        for (Map.Entry<IndexLabel, ConditionQuery> e : queries.entrySet()) {
            IndexLabel indexLabel = e.getKey();
            ConditionQuery query = e.getValue();
            assert !query.paging();
            if (!query.noLimit() && queries.size() > 1) {
                // Unset limit for intersection operation
                query.limit(Query.NO_LIMIT);
            }
            /*
             * Try to query by joint indexes:
             * 1 If there is any index exceeded the threshold, transform into
             *   partial index query, then filter after back-table.
             * 1.1 Return the holder of the first index that not exceeded the
             *     threshold if there exists one index, this holder will be used
             *     as the only query condition.
             * 1.2 Return the holder of the first index if all indexes exceeded
             *     the threshold.
             * 2 Else intersect holders for all indexes, and return intersection
             *   ids of all indexes.
             */
            IdHolder holder = this.doIndexQuery(indexLabel, query);
            if (resultHolder == null) {
                resultHolder = holder;
                this.storeSelectedIndexField(indexLabel, query);
            }
            assert this.indexIntersectThresh > 0; // default value is 1000
            Set<Id> ids = ((BatchIdHolder) holder).peekNext(
                          this.indexIntersectThresh).ids();
            if (ids.size() >= this.indexIntersectThresh) {
                // Transform into filtering
                filtering = true;
                query.optimized(OptimizedType.INDEX_FILTER);
            } else if (filtering) {
                assert ids.size() < this.indexIntersectThresh;
                resultHolder = holder;
                this.storeSelectedIndexField(indexLabel, query);
                break;
            } else {
                if (intersectIds == null) {
                    intersectIds = ids;
                } else {
                    CollectionUtil.intersectWithModify(intersectIds, ids);
                }
                if (intersectIds.isEmpty()) {
                    break;
                }
            }
        }

        if (filtering) {
            return resultHolder;
        } else {
            assert intersectIds != null;
            return new FixedIdHolder(queries.asJointQuery(), intersectIds);
        }
    }

    private void storeSelectedIndexField(IndexLabel indexLabel,
                                         ConditionQuery query) {
        // Only store range index field
        if (!indexLabel.indexType().isRange()) {
            return;
        }

        ConditionQuery originConditionQuery =
                       query.originConditionQuery();
        if (originConditionQuery != null) {
            originConditionQuery.selectedIndexField(indexLabel.indexField());
        }
    }

    @Watched(prefix = "index")
    private IdHolder doIndexQuery(IndexLabel indexLabel, ConditionQuery query) {
        if (!query.paging()) {
            return this.doIndexQueryBatch(indexLabel, query);
        } else {
            return new PagingIdHolder(query, q -> {
                return this.doIndexQueryOnce(indexLabel, q);
            });
        }
    }

    @Watched(prefix = "index")
    private IdHolder doIndexQueryBatch(IndexLabel indexLabel,
                                       ConditionQuery query) {
        Iterator<BackendEntry> entries = super.query(query).iterator();
        return new BatchIdHolder(query, entries, batch -> {
            LockUtil.Locks locks = new LockUtil.Locks(this.graphName());
            try {
                // Catch lock every batch
                locks.lockReads(LockUtil.INDEX_LABEL_DELETE, indexLabel.id());
                locks.lockReads(LockUtil.INDEX_LABEL_REBUILD, indexLabel.id());
                if (!indexLabel.system()) {
                    /*
                     * Check exist because it may be deleted after some batches
                     * throw exception if the index label not exists
                     * NOTE: graph() will return null with system index label
                     */
                    graph().indexLabel(indexLabel.id());
                }

                // Iterate one batch, and keep iterator position
                Set<Id> ids = InsertionOrderUtil.newSet();
                while ((ids.size() < batch || batch == Query.NO_LIMIT) &&
                       entries.hasNext()) {
                    HugeIndex index = this.serializer.readIndex(graph(), query,
                                                                entries.next());
                    this.removeExpiredIndexIfNeeded(index, query.showExpired());
                    ids.addAll(index.elementIds());
                    Query.checkForceCapacity(ids.size());
                    this.recordIndexValue(query, index);
                }
                return ids;
            } finally {
                locks.unlock();
            }
        });
    }

    private void recordIndexValue(ConditionQuery query, HugeIndex index) {
        if (!shouldRecordIndexValue(query, index)) {
            return;
        }

        ConditionQuery originQuery = query.originConditionQuery();
        Id fieldId = index.indexLabel().indexField();
        for (Id id : index.elementIds()) {
            Object value = index.indexLabel().validValue(index.fieldValues());
            originQuery.recordIndexValue(fieldId, id, value);
        }
    }

    @Watched(prefix = "index")
    private PageIds doIndexQueryOnce(IndexLabel indexLabel,
                                     ConditionQuery query) {
        // Query all or one page
        Iterator<BackendEntry> entries = null;
        LockUtil.Locks locks = new LockUtil.Locks(this.graphName());
        try {
            locks.lockReads(LockUtil.INDEX_LABEL_DELETE, indexLabel.id());
            locks.lockReads(LockUtil.INDEX_LABEL_REBUILD, indexLabel.id());

            Set<Id> ids = InsertionOrderUtil.newSet();
            entries = super.query(query).iterator();
            while (entries.hasNext()) {
                HugeIndex index = this.serializer.readIndex(graph(), query,
                                                            entries.next());
                this.removeExpiredIndexIfNeeded(index, query.showExpired());
                ids.addAll(index.elementIds());
                if (query.reachLimit(ids.size())) {
                    break;
                }
                Query.checkForceCapacity(ids.size());
                this.recordIndexValue(query, index);
            }
            // If there is no data, the entries is not a Metadatable object
            if (ids.isEmpty()) {
                return PageIds.EMPTY;
            }
            // NOTE: Memory backend's iterator is not Metadatable
            if (!query.paging()) {
                return new PageIds(ids, PageState.EMPTY);
            }
            E.checkState(entries instanceof Metadatable,
                         "The entries must be Metadatable when query " +
                         "in paging, but got '%s'",
                         entries.getClass().getName());
            return new PageIds(ids, PageInfo.pageState(entries));
        } finally {
            locks.unlock();
            CloseableIterator.closeIterator(entries);
        }
    }

    @Watched(prefix = "index")
    private Set<MatchedIndex> collectMatchedIndexes(ConditionQuery query) {
        SchemaTransaction schema = this.params().schemaTransaction();
        Id label = query.condition(HugeKeys.LABEL);

        List<? extends SchemaLabel> schemaLabels;
        if (label != null) {
            // Query has LABEL condition
            SchemaLabel schemaLabel;
            if (query.resultType().isVertex()) {
                schemaLabel = schema.getVertexLabel(label);
            } else if (query.resultType().isEdge()) {
                schemaLabel = schema.getEdgeLabel(label);
            } else {
                throw new AssertionError(String.format(
                          "Unsupported index query type: %s",
                          query.resultType()));
            }
            schemaLabels = ImmutableList.of(schemaLabel);
        } else {
            // Query doesn't have LABEL condition
            if (query.resultType().isVertex()) {
                schemaLabels = schema.getVertexLabels();
            } else if (query.resultType().isEdge()) {
                schemaLabels = schema.getEdgeLabels();
            } else {
                throw new AssertionError(String.format(
                          "Unsupported index query type: %s",
                          query.resultType()));
            }
        }

        // Collect MatchedIndex for each SchemaLabel
        Set<MatchedIndex> matchedIndexes = InsertionOrderUtil.newSet();
        for (SchemaLabel schemaLabel : schemaLabels) {
            MatchedIndex index = this.collectMatchedIndex(schemaLabel, query);
            if (index != null) {
                matchedIndexes.add(index);
            }
        }
        return matchedIndexes;
    }

    /**
     * Collect matched IndexLabel(s) in a SchemaLabel for a query
     * @param schemaLabel find indexLabels of this schemaLabel
     * @param query conditions container
     * @return MatchedLabel object contains schemaLabel and matched indexLabels
     */
    @Watched(prefix = "index")
    private MatchedIndex collectMatchedIndex(SchemaLabel schemaLabel,
                                             ConditionQuery query) {
        SchemaTransaction schema = this.params().schemaTransaction();
        Set<IndexLabel> ils = InsertionOrderUtil.newSet();
        for (Id il : schemaLabel.indexLabels()) {
            IndexLabel indexLabel = schema.getIndexLabel(il);
            /*
             * Method schema#getIndexLabel may return null here
             * because the indexLabel is being created at this time
             * and has not been saved to the backend storage
             */
            if (indexLabel == null || indexLabel.indexType().isUnique()) {
                continue;
            }
            ils.add(indexLabel);
        }
        if (this.graph().readMode().showOlap()) {
            for (IndexLabel il : schema.getIndexLabels()) {
                if (il.olap()) {
                    ils.add(il);
                }
            }
        }
        if (ils.isEmpty()) {
            return null;
        }
        // Try to match single or composite index
        Set<IndexLabel> matchedILs = matchSingleOrCompositeIndex(query, ils);
        if (matchedILs.isEmpty()) {
            // Try to match joint indexes
            matchedILs = matchJointIndexes(query, ils);
        }

        if (!matchedILs.isEmpty()) {
            return new MatchedIndex(schemaLabel, matchedILs);
        }
        return null;
    }


    private ConditionQuery constructSearchQuery(ConditionQuery query,
                                                MatchedIndex index) {
        ConditionQuery originQuery = query;
        Set<Id> indexFields = new HashSet<>();
        // Convert has(key, text) to has(key, textContainsAny(word1, word2))
        for (IndexLabel il : index.indexLabels()) {
            if (il.indexType() != IndexType.SEARCH) {
                continue;
            }
            Id indexField = il.indexField();
            String fieldValue = (String) query.userpropValue(indexField);
            Set<String> words = this.segmentWords(fieldValue);
            indexFields.add(indexField);

            query = query.copy();
            query.unsetCondition(indexField);
            query.query(Condition.textContainsAny(indexField, words));
        }

        // Register results filter to compare property value and search text
        query.registerResultsFilter(elem -> {
            for (Condition cond : originQuery.conditions()) {
                Object key = cond.isRelation() ? ((Relation) cond).key() : null;
                if (key instanceof Id && indexFields.contains(key)) {
                    // This is an index field of search index
                    Id field = (Id) key;
                    assert elem != null;
                    HugeProperty<?> property = elem.getProperty(field);
                    String propValue = propertyValueToString(property.value());
                    String fieldValue = (String) originQuery.userpropValue(field);
                    if (this.matchSearchIndexWords(propValue, fieldValue)) {
                        continue;
                    }
                    return false;
                }
                if (!cond.test(elem)) {
                    return false;
                }
            }
            return true;
        });

        return query;
    }

    private boolean matchSearchIndexWords(String propValue, String fieldValue) {
        Set<String> propValues = this.segmentWords(propValue);
        Set<String> words = this.segmentWords(fieldValue);
        return CollectionUtil.hasIntersection(propValues, words);
    }

    private Set<String> segmentWords(String text) {
        Set<String> words = this.textAnalyzer.segment(text);
        for (char ch = INDEX_SYM_ENDING; ch <= INDEX_SYM_MAX; ch++) {
            words.remove(ch);
        }
        return words;
    }

    private boolean needIndexForLabel() {
        return !this.store().features().supportsQueryByLabel();
    }

    private void removeExpiredIndexIfNeeded(HugeIndex index,
                                            boolean showExpired) {
        if (this.store().features().supportsTtl() || showExpired) {
            return;
        }
        for (HugeIndex.IdWithExpiredTime id : index.expiredElementIds()) {
            HugeIndex removeIndex = index.clone();
            removeIndex.resetElementIds();
            removeIndex.elementIds(id.id(), id.expiredTime());
            DeleteExpiredJob.asyncDeleteExpiredObject(this.graph(),
                                                      removeIndex);
        }
    }

    private static Set<IndexLabel> matchSingleOrCompositeIndex(
                                   ConditionQuery query,
                                   Set<IndexLabel> indexLabels) {
        if (query.hasNeqCondition()) {
            return ImmutableSet.of();
        }
        boolean requireRange = query.hasRangeCondition();
        boolean requireSearch = query.hasSearchCondition();
        Set<Id> queryPropKeys = query.userpropKeys();
        for (IndexLabel indexLabel : indexLabels) {
            List<Id> indexFields = indexLabel.indexFields();
            // Try to match fields
            if (!matchIndexFields(queryPropKeys, indexFields)) {
                continue;
            }
            /*
             * Matched all fields, try to match index type.
             * For range-index or search-index there must be only one condition.
             * The following terms are legal:
             *   1.hasSearchCondition and IndexType.SEARCH
             *   2.hasRangeCondition and IndexType.RANGE
             *   3.not hasRangeCondition but has range-index equal-condition
             *   4.secondary (composite) index
             */
            IndexType indexType = indexLabel.indexType();
            if ((requireSearch && !indexType.isSearch()) ||
                (!requireSearch && indexType.isSearch())) {
                continue;
            }
            if (requireRange && !indexType.isNumeric()) {
                continue;
            }
            return ImmutableSet.of(indexLabel);
        }
        return ImmutableSet.of();
    }

    /**
     * Collect index label(s) whose prefix index fields are contained in
     * property-keys in query
     */
    private static Set<IndexLabel> matchJointIndexes(
                                   ConditionQuery query,
                                   Set<IndexLabel> indexLabels) {
        if (query.hasNeqCondition()) {
            return ImmutableSet.of();
        }
        Set<Id> queryPropKeys = query.userpropKeys();
        assert !queryPropKeys.isEmpty();
        Set<IndexLabel> allILs = InsertionOrderUtil.newSet(indexLabels);

        // Handle range/search index first
        Set<IndexLabel> matchedIndexLabels = InsertionOrderUtil.newSet();
        if (query.hasRangeCondition() || query.hasSearchCondition()) {
            matchedIndexLabels = matchRangeOrSearchIndexLabels(query, allILs);
            if (matchedIndexLabels.isEmpty()) {
                return ImmutableSet.of();
            }
            allILs.removeAll(matchedIndexLabels);

            // Remove matched queryPropKeys
            for (IndexLabel il : matchedIndexLabels) {
                // Only one field each range/search index-label
                queryPropKeys.remove(il.indexField());
            }
            // Return if all fields are matched
            if (queryPropKeys.isEmpty()) {
                return matchedIndexLabels;
            }
        }

        // Handle secondary indexes
        Set<Id> indexFields = InsertionOrderUtil.newSet();
        for (IndexLabel indexLabel : allILs) {
            // Range index equal-condition and secondary index can joint
            if (indexLabel.indexType().isSearch()) {
                // Search index must be handled at the previous step
                continue;
            }

            List<Id> fields = indexLabel.indexFields();
            // Collect all fields prefix
            for (Id field : fields) {
                if (!queryPropKeys.contains(field)) {
                    break;
                }
                matchedIndexLabels.add(indexLabel);
                indexFields.add(field);
            }
        }
        // Must match all fields
        if (indexFields.equals(queryPropKeys)) {
            return matchedIndexLabels;
        } else {
            return ImmutableSet.of();
        }
    }

    private static Set<IndexLabel> matchRangeOrSearchIndexLabels(
                                   ConditionQuery query,
                                   Set<IndexLabel> indexLabels) {
        Set<IndexLabel> matchedIndexLabels = InsertionOrderUtil.newSet();
        for (Condition.Relation relation : query.userpropRelations()) {
            if (!relation.relation().isRangeType() &&
                !relation.relation().isSearchType()) {
                continue;
            }
            Id key = (Id) relation.key();
            boolean matched = false;
            for (IndexLabel indexLabel : indexLabels) {
                if (indexLabel.indexType().isRange() ||
                    indexLabel.indexType().isSearch()) {
                    if (indexLabel.indexField().equals(key)) {
                        matched = true;
                        matchedIndexLabels.add(indexLabel);
                        break;
                    }
                }
            }
            if (!matched) {
                return ImmutableSet.of();
            }
        }
        return matchedIndexLabels;
    }

    private static IndexQueries buildJointIndexesQueries(ConditionQuery query,
                                                         MatchedIndex index) {
        IndexQueries queries = new IndexQueries();
        List<IndexLabel> allILs = new ArrayList<>(index.indexLabels());

        // Handle range/search indexes
        if (query.hasRangeCondition() || query.hasSearchCondition()) {
            Set<IndexLabel> matchedILs =
                    matchRangeOrSearchIndexLabels(query, index.indexLabels());
            assert !matchedILs.isEmpty();
            allILs.removeAll(matchedILs);

            Set<Id> queryPropKeys = InsertionOrderUtil.newSet();
            for (IndexLabel il : matchedILs) {
                // Only one field each range/search index-label
                queryPropKeys.add(il.indexField());
            }

            // Construct queries by matched index-labels
            queries.putAll(constructQueries(query, matchedILs, queryPropKeys));

            // Remove matched queryPropKeys
            query = query.copy();
            for (Id field : queryPropKeys) {
                query.unsetCondition(field);
            }
            // Return if matched indexes satisfies query-conditions already
            if (query.userpropKeys().isEmpty()) {
                return queries;
            }
        }

        // Handle secondary joint indexes
        final ConditionQuery finalQuery = query;
        for (int i = 1, size = allILs.size(); i <= size; i++) {
            boolean found = cmn(allILs, size, i, 0, null, r -> {
                // All n indexLabels are selected, test current combination
                IndexQueries qs = constructJointSecondaryQueries(finalQuery, r);
                if (qs.isEmpty()) {
                    return false;
                }
                queries.putAll(qs);
                return true;
            });

            if (found) {
                return queries;
            }
        }
        return IndexQueries.EMPTY;
    }

    /**
     * Traverse C(m, n) combinations of a list to find first matched
     * result combination and call back with the result.
     * TODO: move this method to common module.
     * @param all list to contain all items for combination
     * @param m m of C(m, n)
     * @param n n of C(m, n)
     * @param current current position in list
     * @param result list to contains selected items
     * @return true if matched items combination else false
     */
    private static <T> boolean cmn(List<T> all, int m, int n,
                                   int current, List<T> result,
                                   Function<List<T>, Boolean> callback) {
        assert m <= all.size();
        assert n <= m;
        assert current <= all.size();
        if (result == null) {
            result = new ArrayList<>(n);
        }

        if (m == n) {
            result.addAll(all.subList(current, all.size()));
            n = 0;
        }
        if (n == 0) {
            // All n items are selected
            return callback.apply(result);
        }
        if (current >= all.size()) {
            // Reach the end of items
            return false;
        }

        // Select current item, continue to select C(m-1, n-1)
        int index = result.size();
        result.add(all.get(current));
        if (cmn(all, m - 1, n - 1, ++current, result, callback)) {
            return true;
        }
        // Not select current item, continue to select C(m-1, n)
        result.remove(index);
        if (cmn(all, m - 1, n, current, result, callback)) {
            return true;
        }
        return false;
    }

    private static boolean shouldRecordIndexValue(ConditionQuery query,
                                                  HugeIndex index) {
        // Currently, only range index has problems
        return query.originQuery() instanceof ConditionQuery &&
               index.indexLabel().indexType().isRange();
    }

    private static IndexQueries constructJointSecondaryQueries(
                                ConditionQuery query,
                                List<IndexLabel> ils) {
        Set<IndexLabel> indexLabels = InsertionOrderUtil.newSet();
        indexLabels.addAll(ils);
        indexLabels = matchJointIndexes(query, indexLabels);
        if (indexLabels.isEmpty()) {
            return IndexQueries.EMPTY;
        }

        return constructQueries(query, indexLabels, query.userpropKeys());
    }

    private static IndexQueries constructQueries(ConditionQuery query,
                                                 Set<IndexLabel> ils,
                                                 Set<Id> propKeys) {
        IndexQueries queries = new IndexQueries();

        for (IndexLabel il : ils) {
            List<Id> fields = il.indexFields();
            ConditionQuery newQuery = query.copy();
            newQuery.resetUserpropConditions();
            for (Id field : fields) {
                if (!propKeys.contains(field)) {
                    break;
                }
                for (Condition c : query.userpropConditions(field)) {
                    newQuery.query(c);
                }
            }
            ConditionQuery q = constructQuery(newQuery, il);
            assert q != null;
            queries.put(il, q);
        }
        return queries;
    }

    private static ConditionQuery constructQuery(ConditionQuery query,
                                                 IndexLabel indexLabel) {
        IndexType indexType = indexLabel.indexType();
        boolean requireRange = query.hasRangeCondition();
        boolean supportRange = indexType.isNumeric();
        if (requireRange && !supportRange) {
            LOG.debug("There is range query condition in '{}', " +
                      "but the index label '{}' is unable to match",
                      query, indexLabel.name());
            return null;
        }

        Set<Id> queryKeys = query.userpropKeys();
        List<Id> indexFields = indexLabel.indexFields();
        if (!matchIndexFields(queryKeys, indexFields)) {
            return null;
        }
        LOG.debug("Matched index fields: {} of index '{}'",
                  indexFields, indexLabel);

        ConditionQuery indexQuery;

        switch (indexType) {
            case SEARCH:
                E.checkState(indexFields.size() == 1,
                             "Invalid index fields size for %s: %s",
                             indexType, indexFields);
                Object fieldValue = query.userpropValue(indexFields.get(0));
                assert fieldValue instanceof String;
                fieldValue = escapeIndexValueIfNeeded((String) fieldValue);

                // Query search index from SECONDARY_INDEX table
                indexQuery = new ConditionQuery(indexType.type(), query);
                indexQuery.eq(HugeKeys.INDEX_LABEL_ID, indexLabel.id());
                indexQuery.eq(HugeKeys.FIELD_VALUES, fieldValue);
                break;
            case SECONDARY:
                List<Id> joinedKeys = indexFields.subList(0, queryKeys.size());
                String joinedValues = query.userpropValuesString(joinedKeys);
                joinedValues = escapeIndexValueIfNeeded(joinedValues);

                indexQuery = new ConditionQuery(indexType.type(), query);
                indexQuery.olap(indexLabel.olap());
                indexQuery.eq(HugeKeys.INDEX_LABEL_ID, indexLabel.id());
                indexQuery.eq(HugeKeys.FIELD_VALUES, joinedValues);
                break;
            case RANGE_INT:
            case RANGE_FLOAT:
            case RANGE_LONG:
            case RANGE_DOUBLE:
                if (query.userpropConditions().size() > 2) {
                    throw new HugeException(
                              "Range query has two conditions at most, " +
                              "but got: %s", query.userpropConditions());
                }
                // Replace the query key with PROPERTY_VALUES, set number value
                indexQuery = new ConditionQuery(indexType.type(), query);
                indexQuery.eq(HugeKeys.INDEX_LABEL_ID, indexLabel.id());
                indexQuery.olap(indexLabel.olap());
                for (Condition condition : query.userpropConditions()) {
                    assert condition instanceof Condition.Relation;
                    Condition.Relation r = (Condition.Relation) condition;
                    Number value = NumericUtil.convertToNumber(r.value());
                    Condition.Relation sys = new Condition.SyspropRelation(
                                                 HugeKeys.FIELD_VALUES,
                                                 r.relation(), value);
                    condition = condition.replace(r, sys);
                    indexQuery.query(condition);
                }
                break;
            case SHARD:
                HugeType type = indexLabel.indexType().type();
                indexQuery = new ConditionQuery(type, query);
                indexQuery.eq(HugeKeys.INDEX_LABEL_ID, indexLabel.id());
                List<Condition> conditions = constructShardConditions(
                                             query, indexLabel.indexFields(),
                                             HugeKeys.FIELD_VALUES);
                indexQuery.query(conditions);
                break;
            default:
                throw new AssertionError(String.format(
                          "Unknown index type '%s'", indexType));
        }

        /*
         * Set limit for single index or composite index, also for joint index,
         * to avoid redundant element ids and out of capacity.
         * NOTE: not set offset because this query might be a sub-query,
         * see queryByUserprop()
         */
        indexQuery.page(query.page());
        indexQuery.limit(query.total());
        indexQuery.capacity(query.capacity());

        return indexQuery;
    }

    protected static List<Condition> constructShardConditions(
                                     ConditionQuery query,
                                     List<Id> fields,
                                     HugeKeys key) {
        List<Condition> conditions = new ArrayList<>(2);
        boolean hasRange = false;
        int processedCondCount = 0;
        List<Object> prefixes = new ArrayList<>();

        for (Id field : fields) {
            List<Condition> fieldConds = query.userpropConditions(field);
            processedCondCount += fieldConds.size();
            if (fieldConds.isEmpty()) {
                break;
            }

            RangeConditions range = new RangeConditions(fieldConds);
            if (!range.hasRange()) {
                E.checkArgument(range.keyEq() != null,
                                "Invalid query: %s", query);
                prefixes.add(range.keyEq());
                continue;
            }

            if (range.keyMin() != null) {
                RelationType type = range.keyMinEq() ?
                                    RelationType.GTE : RelationType.GT;
                conditions.add(shardFieldValuesCondition(key, prefixes,
                                                         range.keyMin(),
                                                         type));
            } else {
                assert range.keyMax() != null;
                Object num = range.keyMax();
                num = NumericUtil.minValueOf(NumericUtil.isNumber(num) ?
                                             num.getClass() : Long.class);
                conditions.add(shardFieldValuesCondition(key, prefixes, num,
                                                         RelationType.GTE));
            }

            if (range.keyMax() != null) {
                RelationType type = range.keyMaxEq() ?
                                    RelationType.LTE : RelationType.LT;
                conditions.add(shardFieldValuesCondition(key, prefixes,
                                                         range.keyMax(), type));
            } else {
                Object num = range.keyMin();
                num = NumericUtil.maxValueOf(NumericUtil.isNumber(num) ?
                                             num.getClass() : Long.class);
                conditions.add(shardFieldValuesCondition(key, prefixes, num,
                                                         RelationType.LTE));
            }
            hasRange = true;
            break;
        }

        /*
         * Can't have conditions after range condition for shard index,
         * but SORT_KEYS can have redundant conditions because upper
         * layer can do filter.
         */
        if (key == HugeKeys.FIELD_VALUES &&
            processedCondCount < query.userpropKeys().size()) {
            throw new HugeException("Invalid shard index query: %s", query);
        }
        // 1. First range condition processed, finish shard query conditions
        if (hasRange) {
            return conditions;
        }
        // 2. Shard query without range
        String joinedValues;
        // 2.1 All fields have equal-conditions
        if (prefixes.size() == fields.size()) {
            // Prefix numeric values should be converted to sortable string
            joinedValues = ConditionQuery.concatValues(prefixes);
            conditions.add(Condition.eq(key, joinedValues));
            return conditions;
        }
        // 2.2 Prefix fields have equal-conditions
        /*
         * Append "" to 'values' to ensure FIELD_VALUES suffix
         * with IdGenerator.NAME_SPLITOR
         */
        prefixes.add(Strings.EMPTY);
        joinedValues = ConditionQuery.concatValues(prefixes);
        Condition min = Condition.gte(key, joinedValues);
        conditions.add(min);

        // Increase one on prefix to get next
        Condition max = Condition.lt(key, increaseString(joinedValues));
        conditions.add(max);
        return conditions;
    }

    private static Relation shardFieldValuesCondition(HugeKeys key,
                                                      List<Object> prefixes,
                                                      Object number,
                                                      RelationType type) {
        String num = LongEncoding.encodeNumber(number);
        if (type == RelationType.LTE) {
            type = RelationType.LT;
            num = increaseString(num);
        } else if (type == RelationType.GT) {
            type = RelationType.GTE;
            num = increaseString(num);
        }
        List<Object> values = new ArrayList<>(prefixes);
        values.add(num);
        String value = ConditionQuery.concatValues(values);
        return new Condition.SyspropRelation(key, type, value);
    }

    private static String increaseString(String value) {
        int length = value.length();
        CharBuffer cbuf = CharBuffer.wrap(value.toCharArray());
        char last = cbuf.charAt(length - 1);
        E.checkArgument(last == '!' || LongEncoding.validB64Char(last),
                        "Invalid character '%s' for String index", last);
        cbuf.put(length - 1,  (char) (last + 1));
        return cbuf.toString();
    }

    private static boolean matchIndexFields(Set<Id> queryKeys,
                                            List<Id> indexFields) {
        if (queryKeys.size() > indexFields.size()) {
            return false;
        }

        // Is queryKeys the prefix of indexFields?
        List<Id> subFields = indexFields.subList(0, queryKeys.size());
        return subFields.containsAll(queryKeys);
    }

    private static boolean validQueryConditionValues(HugeGraph graph,
                                                     ConditionQuery query) {
        Set<Id> keys = query.userpropKeys();
        for (Id key : keys) {
            PropertyKey pk = graph.propertyKey(key);
            Set<Object> values = query.userpropValues(key);
            E.checkState(!values.isEmpty(),
                         "Expect user property values for key '%s', " +
                         "but got none", pk);
            boolean hasContains = query.containsContainsCondition(key);
            if (pk.cardinality().multiple()) {
                // If contains collection index, relation should be contains
                E.checkState(hasContains,
                             "The relation of property '%s' must be " +
                             "CONTAINS or TEXT_CONTAINS, but got %s",
                             pk.name(), query.relation(key).relation());
            }

            for (Object value : values) {
                if (hasContains) {
                    value = toCollectionIfNeeded(pk, value);
                }

                if (!pk.checkValueType(value)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Object toCollectionIfNeeded(PropertyKey pk, Object value) {
        switch (pk.cardinality()) {
            case SET:
                if (!(value instanceof Set)) {
                    value = CollectionUtil.toSet(value);
                }
                break;
            case LIST:
                if (!(value instanceof List)) {
                    value = CollectionUtil.toList(value);
                }
                break;
            default:
                break;
        }
        return value;
    }

    private static boolean isCollectionIndex(List<Object> propValues) {
        return propValues.size() == 1 &&
               propValues.get(0) instanceof Collection;
    }

    private static String propertyValueToString(Object value) {
        /*
         * Join collection items with white space if the value is Collection,
         * or else keep the origin value.
         */
        return value instanceof Collection ?
               StringUtils.join(((Iterable<?>) value), " ") : value.toString();
    }

    private static String escapeIndexValueIfNeeded(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch <= INDEX_SYM_MAX) {
                /*
                 * Escape symbols can't be used due to impossible to parse,
                 * and treat it as illegal value for the origin text property
                 */
                E.checkArgument(false, "Illegal char '\\u000%s' " +
                                "in index property: '%s'", (int) ch, value);
            }
        }
        if (value.isEmpty()) {
            // Escape empty String to INDEX_SYM_EMPTY (char `\u0002`)
            value = INDEX_SYM_EMPTY;
        }
        return value;
    }

    private static NoIndexException noIndexException(HugeGraph graph,
                                                     ConditionQuery query,
                                                     Id label) {
        String name = label == null ? "any label" : String.format("label '%s'",
                      query.resultType().isVertex() ?
                      graph.vertexLabel(label).name() :
                      graph.edgeLabel(label).name());
        List<String> mismatched = new ArrayList<>();
        if (query.hasSecondaryCondition()) {
            mismatched.add("secondary");
        }
        if (query.hasRangeCondition()) {
            mismatched.add("range");
        }
        if (query.hasSearchCondition()) {
            mismatched.add("search");
        }
        if (query.hasNeqCondition()) {
            mismatched.add("not-equal");
        }
        if (mismatched.isEmpty()) {
            mismatched.add(query.relations().toString());
        }
        return new NoIndexException("Don't accept query based on properties " +
                                    "%s that are not indexed in %s, " +
                                    "may not match %s condition",
                                    graph.mapPkId2Name(query.userpropKeys()),
                                    name, String.join("/", mismatched));
    }

    private static void validateIndexLabel(IndexLabel indexLabel) {
        E.checkArgument(indexLabel.status().ok(),
                        "Can't query by label index '%s' due to " +
                        "it's in status %s(CREATED expected)",
                        indexLabel, indexLabel.status());
    }

    private static boolean hasNullableProp(HugeElement element, Id key) {
        return element.schemaLabel().nullableKeys().contains(key);
    }

    private static Set<IndexLabel> relatedIndexLabels(HugeElement element) {
        Set<IndexLabel> indexLabels = InsertionOrderUtil.newSet();
        Set<Id> indexLabelIds = element.schemaLabel().indexLabels();

        for (Id id : indexLabelIds) {
            IndexLabel indexLabel = element.graph().indexLabel(id);
            indexLabels.add(indexLabel);
        }
        return indexLabels;
    }

    private static void increaseLimit(Query query) {
        assert !query.noLimit();
        /*
         * NOTE: in order to retain enough records after the intersection.
         * The parameters don't make much sense and need to be improved
         */
        if (!query.paging()) {
            long limit = Math.min(query.limit() * 10L + 8L,
                                  Query.DEFAULT_CAPACITY);
            query.limit(limit);
        }
    }

    protected void removeIndex(IndexLabel indexLabel) {
        HugeIndex index = new HugeIndex(this.graph(), indexLabel);
        this.doRemove(this.serializer.writeIndex(index));
    }

    private static class MatchedIndex {

        private SchemaLabel schemaLabel;
        private Set<IndexLabel> indexLabels;

        public MatchedIndex(SchemaLabel schemaLabel,
                            Set<IndexLabel> indexLabels) {
            this.schemaLabel = schemaLabel;
            this.indexLabels = indexLabels;
        }

        @SuppressWarnings("unused")
        public SchemaLabel schemaLabel() {
            return this.schemaLabel;
        }

        public Set<IndexLabel> indexLabels() {
            return Collections.unmodifiableSet(this.indexLabels);
        }

        public IndexQueries constructIndexQueries(ConditionQuery query) {
            // Condition query => Index Queries
            if (this.indexLabels().size() == 1) {
                /*
                 * Query by single index or composite index
                 */
                IndexLabel il = this.indexLabels().iterator().next();
                ConditionQuery indexQuery = constructQuery(query, il);
                assert indexQuery != null;
                return IndexQueries.of(il, indexQuery);
            } else {
                /*
                 * Query by joint indexes
                 */
                IndexQueries queries = buildJointIndexesQueries(query, this);
                assert !queries.isEmpty();
                return queries;
            }
        }

        public boolean containsSearchIndex() {
            for (IndexLabel il : this.indexLabels) {
                if (il.indexType().isSearch()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int hashCode() {
            return this.indexLabels.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof MatchedIndex)) {
                return false;
            }
            Set<IndexLabel> indexLabels = ((MatchedIndex) other).indexLabels;
            return Objects.equals(this.indexLabels, indexLabels);
        }
    }

    private static class IndexQueries
                   extends HashMap<IndexLabel, ConditionQuery> {

        private static final long serialVersionUID = 1400326138090922676L;

        public static final IndexQueries EMPTY = new IndexQueries();

        public static IndexQueries of(IndexLabel il, ConditionQuery query) {
            IndexQueries indexQueries = new IndexQueries();
            indexQueries.put(il, query);
            return indexQueries;
        }

        public boolean oomRisk() {
            for (Query subQuery : this.values()) {
                if (subQuery.bigCapacity() && subQuery.aggregate() != null) {
                    return true;
                }
            }
            return false;
        }

        public Map.Entry<IndexLabel, ConditionQuery> one() {
            E.checkState(this.size() == 1,
                         "Please ensure index queries only contains one entry");
            return this.entrySet().iterator().next();
        }

        public Query rootQuery() {
            if (this.size() > 0) {
                return this.values().iterator().next().rootOriginQuery();
            }
            return null;
        }

        public Query asJointQuery() {
            @SuppressWarnings({ "unchecked", "rawtypes" })
            Collection<Query> queries = (Collection) this.values();;
            return new JointQuery(this.rootQuery().resultType(), queries);
        }

        private static class JointQuery extends Query {

            private final Collection<Query> queries;

            public JointQuery(HugeType type, Collection<Query> queries) {
                super(type, parent(queries));
                this.queries = queries;
            }

            @Override
            public Query originQuery() {
                List<Query> origins = new ArrayList<>();
                for (Query q : this.queries) {
                    origins.add(q.originQuery());
                }
                return new JointQuery(this.resultType(), origins);
            }

            @Override
            public String toString() {
                return String.format("JointQuery %s", this.queries);
            }

            private static Query parent(Collection<Query> queries) {
                if (queries.size() > 0) {
                    // Chose the first one as origin query (any one is OK)
                    return queries.iterator().next();
                }
                return null;
            }
        }
    }

    public static class RemoveLeftIndexJob extends EphemeralJob<Object> {

        private static final String REMOVE_LEFT_INDEX = "remove_left_index";

        private final ConditionQuery query;
        private final HugeElement element;
        private GraphIndexTransaction tx;
        private Set<ConditionQuery.LeftIndex> leftIndexes;

        private RemoveLeftIndexJob(ConditionQuery query, HugeElement element) {
            E.checkArgumentNotNull(query, "query");
            E.checkArgumentNotNull(element, "element");
            this.query = query;
            this.element = element;
            this.tx = null;
            this.leftIndexes = query.getElementLeftIndex(element.id());
        }

        @Override
        public String type() {
            return REMOVE_LEFT_INDEX;
        }

        @Override
        public Object execute() {
            this.tx = this.element.schemaLabel().system() ?
                      this.params().systemTransaction().indexTransaction() :
                      this.params().graphTransaction().indexTransaction();
            return this.removeIndexLeft(this.query, this.element);
        }

        protected long removeIndexLeft(ConditionQuery query,
                                       HugeElement element) {
            if (element.type() != HugeType.VERTEX &&
                element.type() != HugeType.EDGE_OUT &&
                element.type() != HugeType.EDGE_IN) {
                throw new HugeException("Only accept element of type VERTEX " +
                                        "and EDGE to remove left index, " +
                                        "but got: '%s'", element.type());
            }

            // Check label is matched
            Id label = query.condition(HugeKeys.LABEL);
            // NOTE: original condition query may not have label condition,
            // which means possibly label == null.
            if (label != null && !element.schemaLabel().id().equals(label)) {
                String labelName = element.type().isVertex() ?
                                   this.graph().vertexLabel(label).name() :
                                   this.graph().edgeLabel(label).name();
                E.checkState(false,
                             "Found element %s with unexpected label '%s', " +
                             "expected label '%s', query: %s",
                             element, element.label(), labelName, query);
            }

            long rCount = 0;
            long sCount = 0;
            for (ConditionQuery cq: ConditionQueryFlatten.flatten(query)) {
                // Process range index
                rCount += this.processRangeIndexLeft(cq, element);
                // Process secondary index or search index
                sCount += this.processSecondaryOrSearchIndexLeft(cq, element);
            }
            this.tx.commit();
            return rCount + sCount;
        }

        private long processRangeIndexLeft(ConditionQuery query,
                                           HugeElement element) {
            long count = 0;
            if (this.leftIndexes == null) {
                return count;
            }

            for (ConditionQuery.LeftIndex leftIndex : this.leftIndexes) {
                Set<Object> indexValues = leftIndex.indexFieldValues();
                IndexLabel indexLabel = this.findMatchedIndexLabel(query,
                                                                   leftIndex);
                assert indexLabel != null;

                AbstractSerializer serializer = this.tx.serializer;
                for (Object value : indexValues) {
                    HugeIndex index = new HugeIndex(this.graph(), indexLabel);
                    index.elementIds(element.id());
                    index.fieldValues(value);
                    this.tx.doEliminate(serializer.writeIndex(index));
                    count++;
                }
            }
            // Remove LeftIndex after constructing remove job
            this.query.removeElementLeftIndex(element.id());
            this.tx.commit();
            return count;
        }

        private IndexLabel findMatchedIndexLabel(ConditionQuery query,
                                                 ConditionQuery.LeftIndex
                                                 leftIndex) {
            Set<MatchedIndex> matchedIndexes = this.tx.collectMatchedIndexes(query);
            for (MatchedIndex index : matchedIndexes) {
               for (IndexLabel label : index.indexLabels()){
                   if (label.indexField().equals(leftIndex.indexField())){
                       return label;
                   }
               }
            }
            return null;
        }

        private long processSecondaryOrSearchIndexLeft(ConditionQuery query,
                                                       HugeElement element) {
            Map<PropertyKey, Object> incorrectPKs = InsertionOrderUtil.newMap();
            HugeElement deletion = this.constructErrorElem(query, element,
                                                           incorrectPKs);
            if (deletion == null) {
                return 0;
            }

            // Delete unused index
            long count = 0;
            Set<Id> incorrectPkIds;
            for (IndexLabel il : relatedIndexLabels(deletion)) {
                incorrectPkIds = incorrectPKs.keySet().stream()
                                             .map(PropertyKey::id)
                                             .collect(Collectors.toSet());
                Collection<Id> incorrectIndexFields = CollectionUtil.intersect(
                                                      il.indexFields(),
                                                      incorrectPkIds);
                if (incorrectIndexFields.isEmpty()) {
                    continue;
                }
                // Skip if search index is not wrong
                if (il.indexType().isSearch()) {
                    Id field = il.indexField();
                    String cond = deletion.<String>getPropertyValue(field);
                    String actual = element.<String>getPropertyValue(field);
                    if (this.tx.matchSearchIndexWords(actual, cond)) {
                        /*
                         * If query by two search index, one is correct but
                         * the other is wrong, we should not delete the correct
                         */
                        continue;
                    }
                }
                // Delete index with error property
                this.tx.updateIndex(il.id(), deletion, true);
                // Rebuild index if delete correct index part
                if (il.indexType().isSecondary()) {
                    /*
                     * When it's a composite secondary index,
                     * if the suffix property is wrong and the prefix property
                     * is correct, the correct prefix part will be deleted,
                     * so rebuild the index again with the correct property.
                     */
                    this.tx.updateIndex(il.id(), element, false);
                }
                this.tx.commit();
                if (this.deletedByError(element, incorrectIndexFields,
                                        incorrectPKs)) {
                    this.tx.updateIndex(il.id(), deletion, false);
                    this.tx.commit();
                } else {
                    count++;
                }
            }
            return count;
        }

        private HugeElement constructErrorElem(
                            ConditionQuery query, HugeElement element,
                            Map<PropertyKey, Object> incorrectPKs) {
            HugeElement errorElem = element.copyAsFresh();
            Set<Id> propKeys = query.userpropKeys();
            for (Id key : propKeys) {
                Set<Object> conditionValues = query.userpropValues(key);
                E.checkState(!conditionValues.isEmpty(),
                             "Expect user property values for key '%s', " +
                             "but got none", key);
                if (conditionValues.size() > 1) {
                    // It's inside/between Query (processed in range index)
                    return null;
                }
                HugeProperty<?> prop = element.getProperty(key);
                Object errorValue = conditionValues.iterator().next();
                if (prop == null || !Objects.equals(prop.value(), errorValue)) {
                    PropertyKey pkey = this.graph().propertyKey(key);
                    errorElem.addProperty(pkey, errorValue);
                    incorrectPKs.put(pkey, errorValue);
                }
            }
            return errorElem;
        }

        @SuppressWarnings("unused")
        private boolean deletedByError(ConditionQuery query,
                                       HugeElement element) {
            HugeElement elem = this.newestElement(element);
            if (elem == null) {
                return false;
            }
            return query.test(elem);
        }

        private boolean deletedByError(HugeElement element,
                                       Collection<Id> ilFields,
                                       Map<PropertyKey, Object> incorrectPKs) {
            HugeElement elem = this.newestElement(element);
            if (elem == null) {
                return false;
            }
            for (Map.Entry<PropertyKey, Object> e : incorrectPKs.entrySet()) {
                PropertyKey pk = e.getKey();
                Object value = e.getValue();
                if (ilFields.contains(pk.id()) &&
                    value.equals(elem.getPropertyValue(pk.id()))) {
                    return true;
                }
            }
            return false;
        }

        private HugeElement newestElement(HugeElement element) {
            boolean isVertex = element instanceof HugeVertex;
            if (isVertex) {
                Iterator<Vertex> iter = this.graph().vertices(element.id());
                return (HugeVertex) QueryResults.one(iter);
            } else {
                assert element instanceof HugeEdge;
                Iterator<Edge> iter = this.graph().edges(element.id());
                return (HugeEdge) QueryResults.one(iter);
            }
        }
    }
}
