/*
 * Copyright (c) 2017-2020 "Neo4j,"
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
package org.neo4j.graphalgo.impl.betweenness;

import com.carrotsearch.hppc.IntStack;
import com.carrotsearch.hppc.LongIntScatterMap;
import com.carrotsearch.hppc.procedures.LongIntProcedure;
import org.neo4j.graphalgo.Algorithm;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.core.utils.AtomicDoubleArray;
import org.neo4j.graphalgo.core.utils.container.Paths;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;
import org.neo4j.graphalgo.impl.msbfs.BfsConsumer;
import org.neo4j.graphalgo.impl.msbfs.BfsSources;
import org.neo4j.graphalgo.impl.msbfs.BfsWithPredecessorConsumer;
import org.neo4j.graphalgo.impl.msbfs.MultiSourceBFS;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public class MSBetweennessCentrality extends Algorithm<MSBetweennessCentrality, AtomicDoubleArray> {

    private final Graph graph;
    private final int nodeCount;
    private final boolean undirected;
    private final int bfsCount;
    private final int concurrency;
    private final ExecutorService executorService;
    private final AllocationTracker tracker;

    private final AtomicDoubleArray centrality;

    public MSBetweennessCentrality(
        Graph graph,
        boolean undirected,
        int bfsCount,
        ExecutorService executorService,
        int concurrency,
        AllocationTracker tracker
    ) {
        this.graph = graph;
        this.nodeCount = Math.toIntExact(graph.nodeCount());
        this.undirected = undirected;
        this.bfsCount = bfsCount;
        this.concurrency = concurrency;
        this.executorService = executorService;
        this.tracker = tracker;
        this.centrality = new AtomicDoubleArray(nodeCount);
    }

    @Override
    public AtomicDoubleArray compute() {
        var consumer = new MSBCBFSConsumer(bfsCount, nodeCount, centrality, undirected ? 2.0 : 1.0);

        for (long offset = 0; offset < nodeCount; offset += bfsCount) {
            var limit = Math.min(offset + bfsCount, nodeCount);
            var startNodes = LongStream.range(offset, limit).toArray();
            consumer.init(startNodes, offset > 0);
            // forward traversal for all start nodes
            MultiSourceBFS
                .predecessorProcessing(graph, graph, consumer, consumer, tracker, startNodes)
                .run();
            // backward traversal for all start nodes
            consumer.updateCentrality();
        }

        return centrality;
    }

    @Override
    public MSBetweennessCentrality me() {
        return this;
    }

    @Override
    public void release() {

    }

    public AtomicDoubleArray getCentrality() {
        return centrality;
    }

    public Stream<BetweennessCentrality.Result> resultStream() {
        return IntStream
            .range(0, nodeCount)
            .mapToObj(nodeId ->
                new BetweennessCentrality.Result(
                    graph.toOriginalNodeId(nodeId),
                    centrality.get(nodeId)));
    }

    static class MSBCBFSConsumer implements BfsConsumer, BfsWithPredecessorConsumer {

        private final LongIntScatterMap idMapping;
        private final double divisor;
        private final AtomicDoubleArray centrality;

        private final Paths[] paths;
        private final IntStack[] stacks;
        private final double[][] deltas;
        private final int[][] sigmas;
        private final int[][] distances;

        MSBCBFSConsumer(int bfsCount, int nodeCount, AtomicDoubleArray centrality, double divisor) {
            this.centrality = centrality;

            this.idMapping = new LongIntScatterMap(bfsCount);
            this.paths = new Paths[bfsCount];
            this.stacks = new IntStack[bfsCount];
            this.deltas = new double[bfsCount][];
            this.sigmas = new int[bfsCount][];
            this.distances = new int[bfsCount][];
            this.divisor = divisor;

            for (int i = 0; i < bfsCount; i++) {
                paths[i] = new Paths();
                stacks[i] = new IntStack();
                deltas[i] = new double[nodeCount];
                sigmas[i] = new int[nodeCount];
                distances[i] = new int[nodeCount];
            }
        }

        void init(long[] startNodes, boolean clear) {
            for (int i = 0; i < startNodes.length; i++) {
                if (clear) {
                    Arrays.fill(sigmas[i], 0);
                    Arrays.fill(deltas[i], 0);
                    idMapping.clear();
                    paths[i].clear();
                    stacks[i].clear();
                }
                idMapping.put(startNodes[i], i);
                Arrays.fill(distances[i], -1);
                sigmas[i][(int) startNodes[i]] = 1;
                distances[i][(int) startNodes[i]] = 0;
            }
        }

        void updateCentrality() {
            idMapping.forEach((LongIntProcedure) (sourceNodeId, localNodeId) -> {
                var localStack = stacks[localNodeId];
                var localPaths = paths[localNodeId];
                var localDelta = deltas[localNodeId];
                var localSigma = sigmas[localNodeId];

                while (!localStack.isEmpty()) {
                    int node = localStack.pop();
                    localPaths.forEach(node, predecessor -> {
                        localDelta[predecessor] += (double) localSigma[predecessor] / (double) localSigma[node] * (localDelta[node] + 1.0);
                        return true;
                    });
                    if (node != sourceNodeId) {
                        centrality.add(node, localDelta[node] / divisor);
                    }
                }
            });
        }

        // Called exactly once if `node` is visited for the first time.
        @Override
        public void accept(long node, int depth, BfsSources startNodes) {
            while (startNodes.hasNext()) {
                stacks[idMapping.get(startNodes.next())].push((int) node);
            }
        }

        // Called if `node` has been discovered by at least one BFS via the `predecessor`.
        // Might be called multiple times for the same BFS, but with different predecessors.
        @Override
        public void accept(long node, long predecessor, int depth, BfsSources startNodes) {
            while (startNodes.hasNext()) {
                accept(node, predecessor, depth, idMapping.get(startNodes.next()));
            }
        }

        private void accept(long node, long predecessor, int depth, int startNode) {
            int source = (int) predecessor;
            int target = (int) node;
            // target found for the first time?
            int[] distance = distances[startNode];
            if (distance[target] < 0) {
                distance[target] = depth;
            }
            // shortest path to target via source?
            if (distance[target] == distance[source] + 1) {
                sigmas[startNode][target] += sigmas[startNode][source];
                paths[startNode].append(target, source);
            }
        }
    }
}