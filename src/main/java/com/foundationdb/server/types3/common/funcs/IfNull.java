/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.foundationdb.server.types3.common.funcs;

import com.foundationdb.server.types3.LazyList;
import com.foundationdb.server.types3.TClass;
import com.foundationdb.server.types3.TExecutionContext;
import com.foundationdb.server.types3.TPreptimeValue;
import com.foundationdb.server.types3.TScalar;
import com.foundationdb.server.types3.TOverloadResult;
import com.foundationdb.server.types3.common.util.IsCandidatePredicates;
import com.foundationdb.server.types3.mcompat.mtypes.MApproximateNumber;
import com.foundationdb.server.types3.mcompat.mtypes.MDatetimes;
import com.foundationdb.server.types3.mcompat.mtypes.MNumeric;
import com.foundationdb.server.types3.mcompat.mtypes.MString;
import com.foundationdb.server.types3.pvalue.PValueSource;
import com.foundationdb.server.types3.pvalue.PValueTarget;
import com.foundationdb.server.types3.pvalue.PValueTargets;
import com.foundationdb.server.types3.texpressions.TInputSetBuilder;
import com.foundationdb.server.types3.texpressions.TScalarBase;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public final class IfNull extends TScalarBase {
    // If either side is a string type, result is a string type.
    // Else, if either side is an approximate number, the result is an approximate double.
    // Else, if either side is an exact number, the result is an exact number
    // Else, if both sides are date/time, the result is a date/time.
    // Else, the result is a varchar.
    public static final TScalar[] instances = createInstances();

    private static TScalar[] createInstances() {
        List<TScalar> resultsList = new ArrayList<>();
        Collection<? extends TClass> strings = Arrays.asList(
                MString.VARCHAR, MString.CHAR, MString.TINYTEXT, MString.TEXT, MString.MEDIUMTEXT, MString.LONGTEXT
        );
        Collection<? extends TClass> datetimes = Arrays.asList(
                MDatetimes.DATETIME, MDatetimes.DATE, MDatetimes.TIME, MDatetimes.TIMESTAMP
        );
        Collection<? extends TClass> approx = Arrays.asList(
                MApproximateNumber.DOUBLE, MApproximateNumber.DOUBLE_UNSIGNED,
                MApproximateNumber.FLOAT, MApproximateNumber.FLOAT_UNSIGNED,
                MNumeric.DECIMAL, MNumeric.DECIMAL_UNSIGNED,
                MNumeric.TINYINT, MNumeric.SMALLINT, MNumeric.INT, MNumeric.MEDIUMINT, MNumeric.BIGINT,
                MNumeric.TINYINT_UNSIGNED, MNumeric.SMALLINT_UNSIGNED, MNumeric.INT_UNSIGNED,
                MNumeric.MEDIUMINT_UNSIGNED, MNumeric.BIGINT_UNSIGNED
        );

        // Strings go first, each in its own group to get around the fact that there is no strong casts to strings
        for (TClass  tClass : strings) {
            Predicate<List<? extends TPreptimeValue>> containsTClass = IsCandidatePredicates.contains(tClass);
            Predicate<List<? extends TPreptimeValue>> predicate =
                    Predicates.and(containsTClass, IsCandidatePredicates.allTypesKnown);
            resultsList.add(new IfNull(tClass, resultsList.size(), predicate));
        }

        // Next, date/times go, grouped together. After them come numbers. In both cases, strong casts work as needed.
        createAsGroup(resultsList, datetimes);
        createAsGroup(resultsList, approx);

        // Finally, put another varchar as a catch-all
        resultsList.add(new IfNull(MString.VARCHAR, resultsList.size(), null));

        return resultsList.toArray(new TScalar[resultsList.size()]);
    }

    private static void createAsGroup(List<TScalar> resultsList, Collection<? extends TClass> group) {
        Predicate<List<? extends TPreptimeValue>> containsAny = IsCandidatePredicates.containsOnly(group);
        Predicate<List<? extends TPreptimeValue>> predicate =
                Predicates.and(containsAny, IsCandidatePredicates.allTypesKnown);
        resultsList.add(new IfNull(null, resultsList.size(), predicate));
    }

    @Override
    protected void buildInputSets(TInputSetBuilder builder) {
        builder.pickingCovers(targetClass, 0, 1);
    }

    @Override
    protected void doEvaluate(TExecutionContext context, LazyList<? extends PValueSource> inputs, PValueTarget output) {
        PValueSource arg0 = inputs.get(0);
        PValueSource out = arg0.isNull() ? inputs.get(1) : arg0;
        PValueTargets.copyFrom(out, output);
    }

    @Override
    public String displayName() {
        return "ifnull";
    }

    @Override
    public TOverloadResult resultType() {
        return TOverloadResult.picking();
    }

    @Override
    public int[] getPriorities() {
        return new int[] { priority };
    }

    @Override
    public Predicate<List<? extends TPreptimeValue>> isCandidate() {
        return isCandidatePredicate;
    }

    @Override
    protected boolean nullContaminates(int inputIndex) {
        // Neither arg contaminates by itself. If both are null, then the result is null, but it's just as easy
        // to figure this out in doEvaluate as anywhere else (since PValueTargets.copyFrom does the actual checking).
        return false;
    }

    private IfNull(TClass targetClass, int priority, Predicate<List<? extends TPreptimeValue>> isCandidatePredicate) {
        this.targetClass = targetClass;
        this.priority = priority;
        this.isCandidatePredicate = isCandidatePredicate;
    }

    private final TClass targetClass;
    private final int priority;
    private final Predicate<List<? extends TPreptimeValue>> isCandidatePredicate;
}