/*
 * Copyright (c) 2017-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.core.heavyweight;

import org.neo4j.graphalgo.PropertyMapping;
import org.neo4j.graphalgo.core.IntIdMap;
import org.neo4j.graphalgo.core.WeightMap;
import org.neo4j.graphdb.Result;

import java.util.Map;

class NodeRowCountingVisitor implements Result.ResultVisitor<RuntimeException> {
    private long rows;

    @Override
    public boolean visit(Result.ResultRow row) throws RuntimeException {
        rows++;


        return true;
    }

    long rows() {
        return rows;
    }
}
