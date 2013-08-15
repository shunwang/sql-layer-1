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
package com.foundationdb.server.service.restdml;

import com.foundationdb.ais.model.TableName;
import com.foundationdb.qp.operator.SimpleQueryContext;
import com.foundationdb.qp.operator.StoreAdapter;

public class RestQueryContext extends SimpleQueryContext {

    public RestQueryContext(StoreAdapter adapter) {
        super(adapter);
    }
    
    @Override
    public long sequenceNextValue(TableName sequence) {
        return getStore().sequenceNextValue(sequence);

    }

    @Override
    public long sequenceCurrentValue(TableName sequence) {
        return getStore().sequenceCurrentValue(sequence);
    }
}