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

package com.baidu.hugegraph.backend.store.mysql;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import org.apache.logging.log4j.util.Strings;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.slf4j.Logger;

import com.baidu.hugegraph.backend.BackendException;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.backend.page.PageState;
import com.baidu.hugegraph.backend.query.Aggregate;
import com.baidu.hugegraph.backend.query.Condition;
import com.baidu.hugegraph.backend.query.ConditionQuery;
import com.baidu.hugegraph.backend.query.Query;
import com.baidu.hugegraph.backend.store.BackendEntry;
import com.baidu.hugegraph.backend.store.BackendTable;
import com.baidu.hugegraph.backend.store.Shard;
import com.baidu.hugegraph.backend.store.TableDefine;
import com.baidu.hugegraph.backend.store.mysql.MysqlEntryIterator.PagePosition;
import com.baidu.hugegraph.backend.store.mysql.MysqlSessions.Session;
import com.baidu.hugegraph.exception.NotFoundException;
import com.baidu.hugegraph.iterator.ExtendableIterator;
import com.baidu.hugegraph.type.define.HugeKeys;
import com.baidu.hugegraph.util.E;
import com.baidu.hugegraph.util.Log;
import com.google.common.collect.ImmutableList;

public abstract class MysqlTable
                extends BackendTable<Session, MysqlBackendEntry.Row> {

    private static final Logger LOG = Log.logger(MysqlStore.class);

    private static final String DECIMAL = "DECIMAL";

    // The template for insert and delete statements
    private String insertTemplate;
    private String insertTemplateTtl;
    private String deleteTemplate;

    private final MysqlShardSpliter shardSpliter;

    public MysqlTable(String table) {
        super(table);
        this.insertTemplate = null;
        this.insertTemplateTtl = null;
        this.deleteTemplate = null;
        this.shardSpliter = new MysqlShardSpliter(this.table());
    }

    @Override
    protected void registerMetaHandlers() {
        this.registerMetaHandler("splits", (session, meta, args) -> {
            E.checkArgument(args.length == 1,
                            "The args count of %s must be 1", meta);
            long splitSize = (long) args[0];
            return this.shardSpliter.getSplits(session, splitSize);
        });
    }

    public abstract TableDefine tableDefine();

    @Override
    public void init(Session session) {
        this.createTable(session, this.tableDefine());
    }

    @Override
    public void clear(Session session) {
        this.dropTable(session);
    }

    public void truncate(Session session) {
        this.truncateTable(session);
    }

    protected void createTable(Session session, TableDefine tableDefine) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE IF NOT EXISTS ");
        sql.append(this.table()).append(" (");
        // Add columns
        for (Map.Entry<HugeKeys, String> entry :
             tableDefine.columns().entrySet()) {
            sql.append(formatKey(entry.getKey()));
            sql.append(" ");
            sql.append(entry.getValue());
            sql.append(", ");
        }
        // Specified primary keys
        sql.append(" PRIMARY KEY (");
        int i = 0;
        int size = tableDefine.keys().size();
        for (HugeKeys key : tableDefine.keys()) {
            sql.append(formatKey(key));
            if (++i != size) {
                sql.append(", ");
            }
        }
        sql.append("))");
        sql.append(this.engine(session));
        sql.append(";");

        LOG.debug("Create table: {}", sql);
        try {
            session.execute(sql.toString());
        } catch (SQLException e) {
            throw new BackendException("Failed to create table with '%s'",
                                       e, sql);
        }
    }

    protected String engine(Session session) {
        String engine = session.config().get(MysqlOptions.STORAGE_ENGINE);
        return " ENGINE=" + engine;
    }

    protected void dropTable(Session session) {
        LOG.debug("Drop table: {}", this.table());
        String sql = this.buildDropTemplate();
        try {
            session.execute(sql);
        } catch (SQLException e) {
            throw new BackendException("Failed to drop table with '%s'",
                                       e, sql);
        }
    }

    protected void truncateTable(Session session) {
        LOG.debug("Truncate table: {}", this.table());
        String sql = this.buildTruncateTemplate();
        try {
            session.execute(sql);
        } catch (SQLException e) {
            throw new BackendException("Failed to truncate table with '%s'",
                                       e, sql);
        }
    }

    protected List<HugeKeys> idColumnName() {
        return this.tableDefine().keys();
    }

    protected List<Long> idColumnValue(MysqlBackendEntry.Row entry) {
        return ImmutableList.of(entry.id().asLong());
    }

    protected List<Object> idColumnValue(Id id) {
        return ImmutableList.of(id.asObject());
    }

    protected String buildInsertTemplate(MysqlBackendEntry.Row entry) {
        if (entry.ttl() != 0L) {
            return this.buildInsertTemplateWithTtl(entry);
        }
        if (this.insertTemplate != null) {
            return this.insertTemplate;
        }

        this.insertTemplate = this.buildInsertTemplateForce(entry);
        return this.insertTemplate;
    }

    protected String buildInsertTemplateWithTtl(MysqlBackendEntry.Row entry) {
        assert entry.ttl() != 0L;
        if (this.insertTemplateTtl != null) {
            return this.insertTemplateTtl;
        }

        this.insertTemplateTtl = this.buildInsertTemplateForce(entry);
        return this.insertTemplateTtl;
    }

    protected String buildInsertTemplateForce(MysqlBackendEntry.Row entry) {
        StringBuilder insert = new StringBuilder();
        insert.append("REPLACE INTO ").append(this.table()).append(" (");

        int i = 0;
        int n = entry.columns().size();
        for (HugeKeys key : entry.columns().keySet()) {
            insert.append(formatKey(key));
            if (++i != n) {
                insert.append(", ");
            }
        }
        insert.append(") VALUES (");
        // Fill with '?'
        for (i = 0; i < n; i++) {
            insert.append("?");
            if (i != n - 1) {
                insert.append(", ");
            }
        }
        insert.append(")");

        return insert.toString();
    }

    protected String buildDeleteTemplate(List<HugeKeys> idNames) {
        if (this.deleteTemplate != null) {
            return this.deleteTemplate;
        }

        StringBuilder delete = new StringBuilder();
        delete.append("DELETE FROM ").append(this.table());
        this.appendPartition(delete);

        WhereBuilder where = this.newWhereBuilder();
        where.and(formatKeys(idNames), "=");
        delete.append(where.build());

        this.deleteTemplate = delete.toString();
        return this.deleteTemplate;
    }

    protected String buildDropTemplate() {
        return String.format("DROP TABLE IF EXISTS %s;", this.table());
    }

    protected String buildTruncateTemplate() {
        return String.format("TRUNCATE TABLE %s;", this.table());
    }

    protected void appendPartition(StringBuilder sb) {
        // pass
    }

    /**
     * Insert an entire row
     */
    @Override
    public void insert(Session session, MysqlBackendEntry.Row entry) {
        String template = this.buildInsertTemplate(entry);

        PreparedStatement insertStmt;
        try {
            // Create or get insert prepare statement
            insertStmt = session.prepareStatement(template);
            int i = 1;
            for (Object object : this.buildInsertObjects(entry)) {
                insertStmt.setObject(i++, object);
            }
        } catch (SQLException e) {
            throw new BackendException("Failed to prepare statement '%s'" +
                                       "for entry: %s", template, entry);
        }
        session.add(insertStmt);
    }

    protected List<Object> buildInsertObjects(MysqlBackendEntry.Row entry) {
        List<Object> objects = new ArrayList<>();
        for (Map.Entry<HugeKeys, Object> e : entry.columns().entrySet()) {
            Object value = e.getValue();
            String type = this.tableDefine().columns().get(e.getKey());
            if (type.startsWith(DECIMAL)) {
                value = new BigDecimal(value.toString());
            }
            objects.add(value);
        }
        return objects;
    }

    @Override
    public void delete(Session session, MysqlBackendEntry.Row entry) {
        List<HugeKeys> idNames = this.idColumnName();
        String template = this.buildDeleteTemplate(idNames);
        PreparedStatement deleteStmt;
        try {
            deleteStmt = session.prepareStatement(template);
            if (entry.columns().isEmpty()) {
                // Delete just by id
                List<Long> idValues = this.idColumnValue(entry);
                assert idNames.size() == idValues.size();

                for (int i = 0, n = idNames.size(); i < n; i++) {
                    deleteStmt.setObject(i + 1, idValues.get(i));
                }
            } else {
                // Delete just by column keys(must be id columns)
                for (int i = 0, n = idNames.size(); i < n; i++) {
                    HugeKeys key = idNames.get(i);
                    Object value = entry.column(key);

                    deleteStmt.setObject(i + 1, value);
                }
            }
        } catch (SQLException e) {
            throw new BackendException("Failed to prepare statement '%s'" +
                                       "with entry columns %s",
                                       template, entry.columns().values());
        }
        session.add(deleteStmt);
    }

    @Override
    public void append(Session session, MysqlBackendEntry.Row entry) {
        this.insert(session, entry);
    }

    @Override
    public void eliminate(Session session, MysqlBackendEntry.Row entry) {
        this.delete(session, entry);
    }

    @Override
    public Number queryNumber(Session session, Query query) {
        Aggregate aggregate = query.aggregateNotNull();

        Iterator<Number> results = this.query(session, query, (q, rs) -> {
            try {
                if (!rs.resultSet().next()) {
                    return IteratorUtils.of(aggregate.defaultValue());
                }
                return IteratorUtils.of(rs.resultSet().getLong(1));
            } catch (SQLException e) {
                throw new BackendException(e);
            } finally {
                rs.close();
            }
        });
        return aggregate.reduce(results);
    }

    @Override
    public Iterator<BackendEntry> query(Session session, Query query) {
        return this.query(session, query, this::results2Entries);
    }

    protected <R> Iterator<R> query(Session session, Query query,
                                    BiFunction<Query, ResultSetWrapper, Iterator<R>>
                                    parser) {
        ExtendableIterator<R> rs = new ExtendableIterator<>();

        if (query.limit() == 0L && !query.noLimit()) {
            LOG.debug("Return empty result(limit=0) for query {}", query);
            return rs;
        }

        List<StringBuilder> selections = this.query2Select(this.table(), query);
        try {
            for (StringBuilder selection : selections) {
                ResultSetWrapper results = session.select(selection.toString());
                rs.extend(parser.apply(query, results));
            }
        } catch (SQLException e) {
            throw new BackendException("Failed to query [%s]", e, query);
        }

        LOG.debug("Return {} for query {}", rs, query);
        return rs;
    }

    protected List<StringBuilder> query2Select(String table, Query query) {
        // Build query
        StringBuilder select = new StringBuilder(64);
        select.append("SELECT ");

        // Set aggregate
        Aggregate aggregate = query.aggregate();
        if (aggregate != null) {
            select.append(aggregate.toString());
        } else {
            select.append("*");
        }

        // Set table
        select.append(" FROM ").append(table);

        // Is query by id?
        List<StringBuilder> ids = this.queryId2Select(query, select);

        List<StringBuilder> selections;

        if (query.conditions().isEmpty()) {
            // Query only by id
            LOG.debug("Query only by id(s): {}", ids);
            selections = ids;
        } else {
            ConditionQuery condQuery = (ConditionQuery) query;
            if (condQuery.containsScanRelation()) {
                assert ids.size() == 1;
                return ImmutableList.of(queryByRange(condQuery, ids.get(0)));
            }

            selections = new ArrayList<>(ids.size());
            for (StringBuilder selection : ids) {
                // Query by condition
                selections.addAll(this.queryCondition2Select(query, selection));
            }
            LOG.debug("Query by conditions: {}", selections);
        }
        // Set page, order-by and limit
        for (StringBuilder selection : selections) {
            boolean hasOrder = !query.orders().isEmpty();
            if (hasOrder) {
                this.wrapOrderBy(selection, query);
            }
            if (query.paging()) {
                this.wrapPage(selection, query, false);
                wrapLimit(selection, query);
            } else {
                if (aggregate == null && !hasOrder) {
                    select.append(this.orderByKeys());
                }
                if (!query.noLimit() || query.offset() > 0L) {
                    this.wrapOffset(selection, query);
                }
            }
        }

        return selections;
    }

    protected StringBuilder queryByRange(ConditionQuery query,
                                         StringBuilder select) {
        E.checkArgument(query.relations().size() == 1,
                        "Invalid scan with multi conditions: %s", query);
        Condition.Relation scan = query.relations().iterator().next();
        Shard shard = (Shard) scan.value();

        String page = query.page();
        if (MysqlShardSpliter.START.equals(shard.start()) &&
            MysqlShardSpliter.END.equals(shard.end()) &&
            (page == null || page.isEmpty())) {
            this.wrapLimit(select, query);
            return select;
        }

        HugeKeys partitionKey = this.idColumnName().get(0);

        if (page != null && !page.isEmpty()) {
            // >= page
            this.wrapPage(select, query, true);
            // < end
            WhereBuilder where = this.newWhereBuilder(false);
            if (!MysqlShardSpliter.END.equals(shard.end())) {
                where.and();
                where.lt(formatKey(partitionKey), shard.end());
            }
            select.append(where.build());
        } else {
            // >= start
            WhereBuilder where = this.newWhereBuilder();
            boolean hasStart = false;
            if (!MysqlShardSpliter.START.equals(shard.start())) {
                where.gte(formatKey(partitionKey), shard.start());
                hasStart = true;
            }
            // < end
            if (!MysqlShardSpliter.END.equals(shard.end())) {
                if (hasStart) {
                    where.and();
                }
                where.lt(formatKey(partitionKey), shard.end());
            }
            select.append(where.build());
        }
        this.wrapLimit(select, query);

        return select;
    }

    protected List<StringBuilder> queryId2Select(Query query,
                                                 StringBuilder select) {
        // Query by id(s)
        if (query.ids().isEmpty()) {
            return ImmutableList.of(select);
        }

        List<HugeKeys> nameParts = this.idColumnName();

        List<List<Object>> ids = new ArrayList<>(query.ids().size());
        for (Id id : query.ids()) {
            List<Object> idParts = this.idColumnValue(id);
            if (nameParts.size() != idParts.size()) {
                throw new NotFoundException(
                          "Unsupported ID format: '%s' (should contain %s)",
                          id, nameParts);
            }
            ids.add(idParts);
        }

        // Query only by partition-key
        if (nameParts.size() == 1) {
            List<Object> values = new ArrayList<>(ids.size());
            for (List<Object> objects : ids) {
                assert objects.size() == 1;
                values.add(objects.get(0));
            }

            WhereBuilder where = this.newWhereBuilder();
            where.in(formatKey(nameParts.get(0)), values);
            select.append(where.build());
            return ImmutableList.of(select);
        }

        /*
         * Query by partition-key + clustering-key
         * NOTE: Error if multi-column IN clause include partition key:
         * error: multi-column relations can only be applied to clustering
         * columns when using: select.where(QueryBuilder.in(names, idList));
         * So we use multi-query instead of IN
         */
        List<StringBuilder> selections = new ArrayList<>(ids.size());
        for (List<Object> objects : ids) {
            assert nameParts.size() == objects.size();
            StringBuilder idSelection = new StringBuilder(select);
            /*
             * NOTE: concat with AND relation, like:
             * "pk = id and ck1 = v1 and ck2 = v2"
             */
            WhereBuilder where = this.newWhereBuilder();
            where.and(formatKeys(nameParts), objects);

            idSelection.append(where.build());
            selections.add(idSelection);
        }
        return selections;
    }

    protected List<StringBuilder> queryCondition2Select(Query query,
                                                        StringBuilder select) {
        // Query by conditions
        Set<Condition> conditions = query.conditions();
        List<StringBuilder> clauses = new ArrayList<>(conditions.size());
        for (Condition condition : conditions) {
            clauses.add(this.condition2Sql(condition));
        }
        WhereBuilder where = this.newWhereBuilder();
        where.and(clauses);
        select.append(where.build());
        return ImmutableList.of(select);
    }

    protected StringBuilder condition2Sql(Condition condition) {
        switch (condition.type()) {
            case AND:
                Condition.And and = (Condition.And) condition;
                StringBuilder left = this.condition2Sql(and.left());
                StringBuilder right = this.condition2Sql(and.right());
                int size = left.length() + right.length() + " AND ".length();
                StringBuilder sql = new StringBuilder(size);
                sql.append(left).append(" AND ").append(right);
                return sql;
            case OR:
                throw new BackendException("Not support OR currently");
            case RELATION:
                Condition.Relation r = (Condition.Relation) condition;
                return this.relation2Sql(r);
            default:
                final String msg = "Unsupported condition: " + condition;
                throw new AssertionError(msg);
        }
    }

    protected StringBuilder relation2Sql(Condition.Relation relation) {
        String key = relation.serialKey().toString();
        Object value = relation.serialValue();

        WhereBuilder sql = this.newWhereBuilder(false);
        sql.relation(key, relation.relation(), value);
        return sql.build();
    }

    protected WhereBuilder newWhereBuilder() {
        return this.newWhereBuilder(true);
    }

    protected WhereBuilder newWhereBuilder(boolean startWithWhere) {
        return new WhereBuilder(startWithWhere);
    }

    protected void wrapOrderBy(StringBuilder select, Query query) {
        int size = query.orders().size();
        assert size > 0;

        int i = 0;
        // Set order-by
        select.append(" ORDER BY ");
        for (Map.Entry<HugeKeys, Query.Order> order :
             query.orders().entrySet()) {
            String key = formatKey(order.getKey());
            Query.Order value = order.getValue();
            select.append(key).append(" ");
            if (value == Query.Order.ASC) {
                select.append("ASC");
            } else {
                assert value == Query.Order.DESC;
                select.append("DESC");
            }
            if (++i != size) {
                select.append(", ");
            }
        }
    }

    protected void wrapPage(StringBuilder select, Query query, boolean scan) {
        String page = query.page();
        // It's the first time if page is empty
        if (!page.isEmpty()) {
            byte[] position = PageState.fromString(page).position();
            Map<HugeKeys, Object> columns = PagePosition.fromBytes(position)
                                                        .columns();

            List<HugeKeys> idColumnNames = this.idColumnName();
            List<Object> values = new ArrayList<>(idColumnNames.size());
            for (HugeKeys key : idColumnNames) {
                values.add(columns.get(key));
            }

            // Need add `where` to `select` when query is IdQuery
            boolean expectWhere = scan || query.conditions().isEmpty();
            WhereBuilder where = this.newWhereBuilder(expectWhere);
            if (!expectWhere) {
                where.and();
            }
            where.gte(formatKeys(idColumnNames), values);
            select.append(where.build());
        }
    }

    private void wrapLimit(StringBuilder select, Query query) {
        select.append(this.orderByKeys());
        if (!query.noLimit()) {
            // Fetch `limit + 1` rows for judging whether reached the last page
            select.append(" limit ");
            select.append(query.limit() + 1);
        }
        select.append(";");
    }

    protected String orderByKeys() {
        return Strings.EMPTY;
    }

    protected void wrapOffset(StringBuilder select, Query query) {
        assert query.limit() >= 0;
        assert query.offset() >= 0;
        // Set limit and offset
        select.append(" limit ");
        select.append(query.limit());
        select.append(" offset ");
        select.append(query.offset());
        select.append(";");

        query.goOffset(query.offset());
    }

    protected Iterator<BackendEntry> results2Entries(Query query,
                                                     ResultSetWrapper results) {
        return new MysqlEntryIterator(results, query, this::mergeEntries);
    }

    protected BackendEntry mergeEntries(BackendEntry e1, BackendEntry e2) {
        // Return the next entry (not merged)
        return e2;
    }

    public static String formatKey(HugeKeys key) {
        return key.name();
    }

    public static HugeKeys parseKey(String name) {
        return HugeKeys.valueOf(name.toUpperCase());
    }

    public static List<String> formatKeys(List<HugeKeys> keys) {
        List<String> names = new ArrayList<>(keys.size());
        for (HugeKeys key : keys) {
            names.add(formatKey(key));
        }
        return names;
    }

    private static class MysqlShardSpliter extends ShardSpliter<Session> {

        private static final String BASE64 =
                "0123456789=?ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
                "abcdefghijklmnopqrstuvwxyz";
        private static final int COUNT = 64;

        public MysqlShardSpliter(String table) {
            super(table);
        }

        @Override
        public List<Shard> getSplits(Session session, long splitSize) {
            E.checkArgument(splitSize >= MIN_SHARD_SIZE,
                            "The split-size must be >= %s bytes, but got %s",
                            MIN_SHARD_SIZE, splitSize);
            List<Shard> splits = new ArrayList<>(COUNT);
            splits.add(new Shard(START, BASE64.substring(0, 1), 0));
            for (int i = 0; i < COUNT - 1; i++) {
                splits.add(new Shard(BASE64.substring(i, i + 1),
                                     BASE64.substring(i + 1, i + 2), 0));
            }
            splits.add(new Shard(BASE64.substring(COUNT - 1, COUNT), END, 0));
            return splits;
        }

        @Override
        protected long estimateDataSize(Session session) {
            return 0L;
        }

        @Override
        protected long estimateNumKeys(Session session) {
            return 0L;
        }
    }
}
