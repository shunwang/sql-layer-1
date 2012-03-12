/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.sql.optimizer.rule;

import static com.akiban.sql.optimizer.rule.BranchJoiner_CBO.*;
import com.akiban.sql.optimizer.rule.costmodel.CostModel;
import com.akiban.sql.optimizer.rule.costmodel.TableRowCounts;

import com.akiban.sql.optimizer.plan.*;
import com.akiban.sql.optimizer.plan.TableGroupJoinTree.TableGroupJoinNode;

import com.akiban.ais.model.Group;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.Join;
import com.akiban.ais.model.Table;
import com.akiban.ais.model.UserTable;
import com.akiban.qp.rowtype.Schema;
import com.akiban.qp.rowtype.UserTableRowType;
import com.akiban.server.PersistitKeyValueTarget;
import com.akiban.server.expression.Expression;
import com.akiban.server.expression.std.Expressions;
import com.akiban.server.store.statistics.IndexStatistics;
import static com.akiban.server.store.statistics.IndexStatistics.*;
import com.akiban.server.types.ValueSource;
import com.akiban.server.types.conversion.Converters;
import com.persistit.Key;
import com.persistit.Persistit;

import com.google.common.primitives.UnsignedBytes;

import java.util.*;

public abstract class CostEstimator implements TableRowCounts
{
    private final Schema schema;
    private final CostModel model;
    private final Key key;
    private final PersistitKeyValueTarget keyTarget;
    private final Comparator<byte[]> bytesComparator;

    protected CostEstimator(Schema schema) {
        this.schema = schema;
        model = CostModel.newCostModel(schema, this);
        key = new Key((Persistit)null);
        keyTarget = new PersistitKeyValueTarget();
        bytesComparator = UnsignedBytes.lexicographicalComparator();
    }

    protected CostEstimator(SchemaRulesContext rulesContext) {
        this(rulesContext.getSchema());
    }

    public abstract IndexStatistics getIndexStatistics(Index index);

    protected boolean scaleIndexStatistics() {
        return true;
    }

    /** Estimate cost of scanning from this index. */
    public CostEstimate costIndexScan(Index index,
                                      List<ExpressionNode> equalityComparands,
                                      ExpressionNode lowComparand, boolean lowInclusive,
                                      ExpressionNode highComparand, boolean highInclusive) {
        if (index.isUnique()) {
            if ((equalityComparands != null) &&
                (equalityComparands.size() == index.getKeyColumns().size())) {
                // Exact match from unique index; probably one row.
                return indexAccessCost(1, index);
            }
        }
        long rowCount = getTableRowCount(index.leafMostTable());
        long statsCount = 0;
        IndexStatistics indexStats = getIndexStatistics(index);
        if (indexStats != null)
            statsCount = indexStats.getRowCount();
        int columnCount = 0;
        if (equalityComparands != null) {
            columnCount = Math.min(equalityComparands.size(), // No histogram for value cols.
                                   index.getKeyColumns().size());
        }
        if ((lowComparand != null) || (highComparand != null))
            columnCount++;
        Histogram histogram;
        if ((statsCount == 0) ||
            (columnCount == 0) ||
            ((histogram = indexStats.getHistogram(columnCount)) == null)) {
            // No stats or just used for ordering.
            // TODO: Is this too conservative?
            return indexAccessCost(rowCount, index);
        }
        boolean scaleCount = scaleIndexStatistics();
        long nrows;
        if ((lowComparand == null) && (highComparand == null)) {
            // Equality lookup.

            // If a histogram is almost unique, and in particular unique but
            // not so declared, then the result size doesn't scale up from
            // when it was analyzed.
            long totalDistinct = histogram.totalDistinctCount();
            boolean mostlyDistinct = totalDistinct * 9 > statsCount * 10; // > 90% distinct
            if (mostlyDistinct) scaleCount = false;
            byte[] keyBytes = encodeKeyBytes(index, equalityComparands, null);
            if (keyBytes == null) {
                // Variable.
                nrows = (mostlyDistinct) ? 1 : statsCount / totalDistinct;
            }
            else {
                nrows = rowsEqual(histogram, keyBytes);
            }
        }
        else {
            byte[] lowBytes = encodeKeyBytes(index, equalityComparands, lowComparand);
            byte[] highBytes = encodeKeyBytes(index, equalityComparands, highComparand);
            if ((lowBytes == null) && (highBytes == null)) {
                // Range completely unknown.
                nrows = indexStats.getRowCount();
            }
            else {
                nrows = rowsBetween(histogram, lowBytes, lowInclusive, highBytes, highInclusive);
            }
        }
        if (scaleCount)
            nrows = simpleRound((nrows * rowCount), statsCount);
        return indexAccessCost(nrows, index);
    }

    // Estimate cost of fetching nrows from index.
    // One random access to get there, then nrows-1 sequential accesses following,
    // Plus a surcharge for copying something as wide as the index.
    private CostEstimate indexAccessCost(long nrows, Index index) {
        return new CostEstimate(nrows, 
                                model.indexScan(schema.indexRowType(index), (int)nrows));
    }

    protected long rowsEqual(Histogram histogram, byte[] keyBytes) {
        // TODO; Could use Collections.binarySearch if we had
        // something that looked like a HistogramEntry.
        List<HistogramEntry> entries = histogram.getEntries();
        for (HistogramEntry entry : entries) {
            int compare = bytesComparator.compare(keyBytes, entry.getKeyBytes());
            if (compare == 0)
                return entry.getEqualCount();
            else if (compare < 0) {
                long d = entry.getDistinctCount();
                if (d == 0)
                    return 1;
                return simpleRound(entry.getLessCount(), d);
            }
        }
        HistogramEntry lastEntry = entries.get(entries.size() - 1);
        long d = lastEntry.getDistinctCount();
        if (d == 0)
            return 1;
        d++;
        return simpleRound(lastEntry.getLessCount(), d);
    }
    
    protected long rowsBetween(Histogram histogram, 
                               byte[] lowBytes, boolean lowInclusive,
                               byte[] highBytes, boolean highInclusive) {
        boolean before = (lowBytes != null);
        long rowCount = 0;
        byte[] entryStartBytes, entryEndBytes = null;
        for (HistogramEntry entry : histogram.getEntries()) {
            entryStartBytes = entryEndBytes;
            entryEndBytes = entry.getKeyBytes();
            long portionStart = 0;
            if (before) {
                int compare = bytesComparator.compare(lowBytes, entryEndBytes);
                if (compare > 0)
                    continue;
                before = false;
                if (compare == 0) {
                    if (lowInclusive)
                        rowCount += entry.getEqualCount();
                    continue;
                }
                portionStart = uniformPortion(entryStartBytes, entryEndBytes, lowBytes,
                                              entry.getLessCount());
                // Fall through to check high in same entry.
            }
            if (highBytes != null) {
                int compare = bytesComparator.compare(highBytes, entryEndBytes);
                if (compare == 0) {
                    rowCount += entry.getLessCount() - portionStart;
                    if (highInclusive)
                        rowCount += entry.getEqualCount();
                    break;
                }
                if (compare < 0) {
                    rowCount += uniformPortion(entryStartBytes, entryEndBytes, highBytes,
                                               entry.getLessCount()) - portionStart;
                    break;
                }
            }
            rowCount += entry.getLessCount() + entry.getEqualCount() - portionStart;
        }
        return Math.max(rowCount, 1);
    }
    
    /** Assuming that byte strings are uniformly distributed, what
     * would be given position correspond to?
     */
    protected static long uniformPortion(byte[] start, byte[] end, byte[] middle,
                                         long total) {
        int idx = 0;
        while (safeByte(start, idx) == safeByte(end, idx)) idx++; // First mismatch.
        long lstart = 0, lend = 0, lmiddle = 0;
        for (int i = 0; i < 4; i++) {
            lstart = (lstart << 8) + safeByte(start, idx+i);
            lend = (lend << 8) + safeByte(end, idx+i);
            lmiddle = (lmiddle << 8) + safeByte(middle, idx+i);
        }
        return simpleRound((lmiddle - lstart) * total, lend - lstart);
    }

    private static int safeByte(byte[] ba, int idx) {
        if ((ba != null) && (idx < ba.length))
            return ba[idx] & 0xFF;
        else
            return 0;
    }

    protected static long simpleRound(long n, long d) {
        return (n + d / 2) / d;
    }

    /** Encode the given field expressions a comparable key byte array.
     * Or return <code>null</code> if some field is not a constant.
     */
    protected byte[] encodeKeyBytes(Index index, 
                                    List<ExpressionNode> fields,
                                    ExpressionNode anotherField) {
        key.clear();
        keyTarget.attach(key);
        int i = 0;
        if (fields != null) {
            for (ExpressionNode field : fields) {
                if (!encodeKeyValue(field, index, i++)) {
                    return null;
                }
            }
        }
        if (anotherField != null) {
            if (!encodeKeyValue(anotherField, index, i++)) {
                return null;
            }
        }
        byte[] keyBytes = new byte[key.getEncodedSize()];
        System.arraycopy(key.getEncodedBytes(), 0, keyBytes, 0, keyBytes.length);
        return keyBytes;
    }

    protected boolean encodeKeyValue(ExpressionNode node, Index index, int column) {
        Expression expr = null;
        if (node instanceof ConstantExpression) {
            if (node.getAkType() == null)
                expr = Expressions.literal(((ConstantExpression)node).getValue());
            else
                expr = Expressions.literal(((ConstantExpression)node).getValue(),
                                           node.getAkType());
        }
        if (expr == null)
            return false;
        ValueSource valueSource = expr.evaluation().eval();
        keyTarget.expectingType(index.getKeyColumns().get(column).getColumn().getType().akType());
        Converters.convert(valueSource, keyTarget);
        return true;
    }

    /** Estimate the cost of starting at the given table's index and
     * fetching the given tables, then joining them with Flatten and
     * Product. */
    public CostEstimate costFlatten(TableGroupJoinTree tableGroup,
                                    TableSource indexTable,
                                    Set<TableSource> requiredTables) {
        TableGroupJoinNode startNode = tableGroup.getRoot().findTable(indexTable);
        coverBranches(tableGroup, startNode, requiredTables);
        long rowCount = 1;
        double cost = 0.0;
        List<UserTableRowType> ancestorTypes = new ArrayList<UserTableRowType>();
        for (TableGroupJoinNode ancestorNode = startNode;
             ancestorNode != null;
             ancestorNode = ancestorNode.getParent()) {
            if (isRequired(ancestorNode)) {
                if ((ancestorNode == startNode) &&
                    (getSideBranches(ancestorNode) != 0)) {
                    continue;   // Branch, not ancestor.
                }
                ancestorTypes.add(schema.userTableRowType(ancestorNode.getTable().getTable().getTable()));
            }
        }
        // Cost to get main branch.
        cost += model.ancestorLookup(ancestorTypes);
        for (TableGroupJoinNode branchNode : tableGroup) {
            if (isSideBranchLeaf(branchNode)) {
                int branch = Long.numberOfTrailingZeros(getBranches(branchNode));
                TableGroupJoinNode branchRoot = branchNode, nextToRoot = null;
                while (true) {
                    TableGroupJoinNode parent = branchRoot.getParent();
                    if (parent == startNode) {
                        // Different kind of BranchLookup.
                        nextToRoot = branchRoot = parent;
                        break;
                    }
                    if ((parent == null) || !onBranch(parent, branch))
                        break;
                    nextToRoot = branchRoot;
                    branchRoot = parent;
                }
                assert (nextToRoot != null);
                // Multiplier from this branch.
                rowCount *= descendantCardinality(branchNode, branchRoot);
                // Cost to get side branch.
                cost += model.branchLookup(schema.userTableRowType(nextToRoot.getTable().getTable().getTable()));
            }
        }
        for (TableGroupJoinNode node : tableGroup) {
            if (isFlattenable(node)) {
                long nrows = tableCardinality(node);
                // Cost of flattening these children with their ancestor.
                cost += model.flatten((int)nrows);
            }
        }
        if (rowCount > 1)
            cost += model.product((int)rowCount);
        return new CostEstimate(rowCount, cost);
    }

    /** This table needs to be included in flattens. */
    protected static final long REQUIRED = 1;
    /** This table is on the main branch. */
    protected static final long ANCESTOR = 2;
    protected static final int ANCESTOR_BRANCH = 1;
    /** Mask for main or side branch. */
    protected static final long BRANCH_MASK = ~1;
    /** Mask for side branch. */
    protected static final long SIDE_BRANCH_MASK = ~3;

    protected static boolean isRequired(TableGroupJoinNode table) {
        return ((table.getState() & REQUIRED) != 0);
    }
    protected static void setRequired(TableGroupJoinNode table) {
        table.setState(table.getState() | REQUIRED);
    }
    protected static boolean isAncestor(TableGroupJoinNode table) {
        return ((table.getState() & ANCESTOR) != 0);
    }
    protected static long getBranches(TableGroupJoinNode table) {
        return (table.getState() & BRANCH_MASK);
    }
    protected static long getSideBranches(TableGroupJoinNode table) {
        return (table.getState() & SIDE_BRANCH_MASK);
    }
    protected static boolean onBranch(TableGroupJoinNode table, int b) {
        return ((table.getState() & (1 << b)) != 0);
    }
    protected void setBranch(TableGroupJoinNode table, int b) {
        table.setState(table.getState() | (1 << b));
    }

    /** Like {@link BranchJoiner_CBO#markBranches} but simpler without
     * having to worry about the exact <em>order</em> in which
     * operations are performed.
     */
    protected void coverBranches(TableGroupJoinTree tableGroup, 
                                 TableGroupJoinNode startNode,
                                 Set<TableSource> requiredTables) {
        for (TableGroupJoinNode table : tableGroup) {
            table.setState(requiredTables.contains(table.getTable()) ? REQUIRED : 0);
        }
        int nbranches = ANCESTOR_BRANCH;
        boolean anyAncestorRequired = false;
        for (TableGroupJoinNode table = startNode; table != null; table = table.getParent()) {
            setBranch(table, nbranches);
            if (isRequired(table))
                anyAncestorRequired = true;
        }
        nbranches++;
        for (TableGroupJoinNode table : tableGroup) {
            if (isSideBranchLeaf(table)) {
                // This is the leaf of a new side branch.
                while (true) {
                    boolean onBranchAlready = (getBranches(table) != 0);
                    setBranch(table, nbranches);
                    if (onBranchAlready) {
                        if (!isRequired(table)) {
                            // Might become required for joining of branches.
                            if (Long.bitCount(anyAncestorRequired ?
                                              getBranches(table) :
                                              getSideBranches(table)) > 1)
                                setRequired(table);
                        }
                        break;
                    }
                    table = table.getParent();
                }
                nbranches++;
            }
        }
    }
    
    /** A table is the leaf of some side branch if it's required but
     * none of its descendants are. */
    protected boolean isSideBranchLeaf(TableGroupJoinNode table) {
        if (!isRequired(table) || isAncestor(table))
            return false;
        for (TableGroupJoinNode descendant : table) {
            if ((descendant != table) && isRequired(descendant))
                return false;
        }
        return true;
    }

    /** A table is flattened in if it's required and one of its
     * ancestors is as well. */
    protected boolean isFlattenable(TableGroupJoinNode table) {
        if (!isRequired(table))
            return false;
        while (true) {
            table = table.getParent();
            if (table == null) break;
            if (isRequired(table))
                return true;
        }
        return false;
    }

    /** Number of rows of given table, total per index row. */
    protected long tableCardinality(TableGroupJoinNode table) {
        if (isAncestor(table))
            return 1;
        TableGroupJoinNode parent = table;
        while (true) {
            parent = parent.getParent();
            if (isAncestor(parent))
                return descendantCardinality(table, parent);
        }
    }

    /** Number of child rows per ancestor. */
    protected long descendantCardinality(TableGroupJoinNode childNode, 
                                         TableGroupJoinNode ancestorNode) {
        return simpleRound(getTableRowCount(childNode.getTable().getTable().getTable()),
                           getTableRowCount(ancestorNode.getTable().getTable().getTable()));
    }

    /** Estimate the cost of testing some conditions. */
    // TODO: Assumes that each condition turns into a separate select.
    public CostEstimate costSelect(Collection<ConditionExpression> conditions,
                                   long size) {
        return new CostEstimate(size, model.select((int)size) * conditions.size());
    }

    /** Estimate the cost of a sort of the given size. */
    public CostEstimate costSort(long size) {
        return new CostEstimate(size, model.sort((int)size, false));
    }

    /** Estimate cost of scanning the whole group. */
    // TODO: Need to account for tables actually wanted?
    public CostEstimate costGroupScan(Group group) {
        long nrows = 0;
        double cost = 0.0;
        UserTable root = null;
        for (UserTable table : group.getGroupTable().getAIS().getUserTables().values()) {
            if (table.getGroup() == group) {
                if (table.getParentJoin() == null)
                    root = table;
                nrows += getTableRowCount(table);
            }
        }
        return new CostEstimate(nrows, model.fullGroupScan(schema.userTableRowType(root)));
    }

}
