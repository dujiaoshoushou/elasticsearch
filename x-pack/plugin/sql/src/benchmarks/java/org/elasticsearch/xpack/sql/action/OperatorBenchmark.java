/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.sql.action;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Scorable;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.LongHash;
import org.elasticsearch.xpack.sql.action.compute.Block;
import org.elasticsearch.xpack.sql.action.compute.Driver;
import org.elasticsearch.xpack.sql.action.compute.LongBlock;
import org.elasticsearch.xpack.sql.action.compute.LongGroupingOperator;
import org.elasticsearch.xpack.sql.action.compute.LongMaxOperator;
import org.elasticsearch.xpack.sql.action.compute.LongTransformer;
import org.elasticsearch.xpack.sql.action.compute.NumericDocValuesExtractor;
import org.elasticsearch.xpack.sql.action.compute.Operator;
import org.elasticsearch.xpack.sql.action.compute.Page;
import org.elasticsearch.xpack.sql.action.compute.PageConsumerOperator;
import org.elasticsearch.xpack.sql.action.compute.exchange.ExchangeSink;
import org.elasticsearch.xpack.sql.action.compute.exchange.ExchangeSource;
import org.elasticsearch.xpack.sql.action.compute.exchange.ExchangeSourceOperator;
import org.elasticsearch.xpack.sql.action.compute.LuceneCollector;
import org.elasticsearch.xpack.sql.action.compute.exchange.PassthroughExchanger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Fork(value = 1)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class OperatorBenchmark {

    Directory dir;
    IndexReader indexReader;

    @Param({ "100000000" }) // 100 million
    int numDocs;

    @Setup
    public void setup() throws IOException {
        Path path = Files.createTempDirectory("test");
        dir = new MMapDirectory(path);
        try (IndexWriter indexWriter = new IndexWriter(dir, new IndexWriterConfig())) {
            Document doc = new Document();
            NumericDocValuesField docValuesField = new NumericDocValuesField("value", 0);
            Random r = new Random(0);
            for (int i = 0; i < numDocs; i++) {
                doc.clear();
                docValuesField.setLongValue(r.nextLong());
                doc.add(docValuesField);
                indexWriter.addDocument(doc);
            }
            indexWriter.commit();
            indexWriter.forceMerge(1);
            indexWriter.flush();
        }
        indexReader = DirectoryReader.open(dir);
    }

    @TearDown
    public void tearDown() throws IOException {
        indexReader.close();
        dir.close();
    }

    private static class SimpleXORValueCollector implements Collector {

        long[] coll = new long[1];

        @Override
        public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
            SortedNumericDocValues sortedNumericDocValues = DocValues.getSortedNumeric(context.reader(), "value");
            NumericDocValues numericDocValues = DocValues.unwrapSingleton(sortedNumericDocValues);
            return new LeafCollector() {
                @Override
                public void setScorer(Scorable scorer) {
                    // ignore
                }

                @Override
                public void collect(int doc) throws IOException {
                    if (numericDocValues.advance(doc) == doc) {
                        coll[0] = numericDocValues.longValue() ^ coll[0];
                    }
                }
            };
        }

        long getVal() {
            return coll[0];
        }

        @Override
        public ScoreMode scoreMode() {
            return ScoreMode.COMPLETE_NO_SCORES;
        }
    }

    private static class SimpleGroupCollector implements Collector {

        LongHash longHash = new LongHash(1, BigArrays.NON_RECYCLING_INSTANCE);

        @Override
        public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
            SortedNumericDocValues sortedNumericDocValues = DocValues.getSortedNumeric(context.reader(), "value");
            NumericDocValues numericDocValues = DocValues.unwrapSingleton(sortedNumericDocValues);
            return new LeafCollector() {
                @Override
                public void setScorer(Scorable scorer) {
                    // ignore
                }

                @Override
                public void collect(int doc) throws IOException {
                    if (numericDocValues.advance(doc) == doc) {
                        longHash.add(numericDocValues.longValue());
                    }
                }
            };
        }

        long getVal() {
            return longHash.size();
        }

        @Override
        public ScoreMode scoreMode() {
            return ScoreMode.COMPLETE_NO_SCORES;
        }
    }



    private static class SimpleXOROperator implements Operator {

        private int channel;

        boolean finished;
        boolean returnedResult;

        long val;

        SimpleXOROperator(int channel) {
            this.channel = channel;
        }

        @Override
        public Page getOutput() {
            if (finished && returnedResult == false) {
                returnedResult = true;
                return new Page(new LongBlock(new long[] {val}, 1));
            }
            return null;
        }

        @Override
        public boolean isFinished() {
            return finished && returnedResult;
        }

        @Override
        public void finish() {
            finished = true;
        }

        @Override
        public boolean needsInput() {
            return true;
        }

        @Override
        public void addInput(Page page) {
            Block block = page.getBlock(channel);
            for (int i = 0; i < block.getPositionCount(); i++) {
                val = val ^ block.getLong(i);
            }
        }

        @Override
        public void close() {

        }
    }

    private static class SimpleDocsCollector implements Collector {

        long[] coll = new long[1];

        @Override
        public LeafCollector getLeafCollector(LeafReaderContext context) {
            return new LeafCollector() {
                @Override
                public void setScorer(Scorable scorer) {
                    // ignore
                }

                @Override
                public void collect(int doc) {
                    coll[0] = doc ^ coll[0];
                }
            };
        }

        long getVal() {
            return coll[0];
        }

        @Override
        public ScoreMode scoreMode() {
            return ScoreMode.COMPLETE_NO_SCORES;
        }
    }

    @Benchmark
    public long testVisitAllDocs() throws IOException {
        IndexSearcher searcher = new IndexSearcher(indexReader);
        SimpleDocsCollector simpleDocsCollector = new SimpleDocsCollector();
        searcher.search(new MatchAllDocsQuery(), simpleDocsCollector);
        return simpleDocsCollector.getVal();
    }

    @Benchmark
    public long testVisitAllNumbers() throws IOException {
        IndexSearcher searcher = new IndexSearcher(indexReader);
        SimpleXORValueCollector simpleValueCollector = new SimpleXORValueCollector();
        searcher.search(new MatchAllDocsQuery(), simpleValueCollector);
        return simpleValueCollector.getVal();
    }

    @Benchmark
    public long testGroupAllNumbers() throws IOException {
        IndexSearcher searcher = new IndexSearcher(indexReader);
        SimpleGroupCollector simpleGroupCollector = new SimpleGroupCollector();
        searcher.search(new MatchAllDocsQuery(), simpleGroupCollector);
        return simpleGroupCollector.getVal();
    }

    private int runWithDriver(int pageSize, Operator... operators) throws InterruptedException {
        IndexSearcher searcher = new IndexSearcher(indexReader);
        ExchangeSource luceneExchangeSource = new ExchangeSource();
        LuceneCollector luceneCollector = new LuceneCollector(
            new ExchangeSink(new PassthroughExchanger(luceneExchangeSource, 100), sink -> luceneExchangeSource.finish()),
            pageSize
        );
        Thread t = new Thread(() -> {
            try {
                searcher.search(new MatchAllDocsQuery(), luceneCollector);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            luceneCollector.finish();
        });
        t.start();
        AtomicInteger rowCount = new AtomicInteger();

        List<Operator> operatorList = new ArrayList<>();
        operatorList.add(new ExchangeSourceOperator(luceneExchangeSource));
        operatorList.addAll(List.of(operators));
        operatorList.add(new PageConsumerOperator(page -> rowCount.addAndGet(page.getPositionCount())));
        Driver driver = new Driver(operatorList, () -> {
        });
        driver.run();
        t.join();
        return rowCount.get();
    }

    @Benchmark
    public long testVisitAllNumbersBatched4K() throws InterruptedException {
        return runWithDriver(
            ByteSizeValue.ofKb(4).bytesAsInt(),
            new NumericDocValuesExtractor(indexReader, 0, 1, "value"),
            new SimpleXOROperator(2)
        );
    }

    @Benchmark
    public long testVisitAllNumbersBatched16K() throws InterruptedException {
        return runWithDriver(
            ByteSizeValue.ofKb(16).bytesAsInt(),
            new NumericDocValuesExtractor(indexReader, 0, 1, "value"),
            new SimpleXOROperator(2)
        );
    }

    @Benchmark
    public long testVisitAllDocsBatched4K() throws InterruptedException {
        return runWithDriver(ByteSizeValue.ofKb(4).bytesAsInt());
    }

    @Benchmark
    public long testVisitAllDocsBatched16K() throws InterruptedException {
        return runWithDriver(ByteSizeValue.ofKb(16).bytesAsInt());
    }

    @Benchmark
    public long testOperatorsWithLucene() throws InterruptedException {
        return runWithDriver(
            ByteSizeValue.ofKb(16).bytesAsInt(),
            new NumericDocValuesExtractor(indexReader, 0, 1, "value"),
            new LongGroupingOperator(2, BigArrays.NON_RECYCLING_INSTANCE),
            new LongMaxOperator(3), // returns largest group number
            new LongTransformer(0, i -> i + 1) // adds +1 to group number (which start with 0) to get group count
        );
    }

//    public long testOperatorsWithLuceneParallel() throws InterruptedException {
//        IndexSearcher searcher = new IndexSearcher(indexReader);
//        LucenePageCollector pageCollector = new LucenePageCollector(ByteSizeValue.ofKb(16).bytesAsInt());
//        Thread t = new Thread(() -> {
//            try {
//                searcher.search(new MatchAllDocsQuery(), pageCollector);
//            } catch (IOException e) {
//                throw new UncheckedIOException(e);
//            }
//            pageCollector.finish();
//        });
//        t.start();
//        AtomicInteger rowCount = new AtomicInteger();
//
//        // implements cardinality on value field
//        List<Operator> operatorList = new ArrayList<>();
//        operatorList.add(pageCollector);
//        operatorList.addAll(List.of(new NumericDocValuesExtractor(indexReader, 0, 1, "value"),
//            new LongGroupingOperator(2, BigArrays.NON_RECYCLING_INSTANCE),
//            new LongMaxOperator(3), // returns largest group number
//            new LongTransformer(0, i -> i + 1))); // adds +1 to group number (which start with 0) to get group count));
//        operatorList.add(new PageConsumerOperator(page -> rowCount.addAndGet(page.getPositionCount())));
//
//        Driver driver1 = new Driver(operatorList, () -> {
//        });
//        Thread t1 = new Thread(driver1::run);
//
//        Driver driver2 = new Driver(operatorList, () -> {
//        });
//        Thread t2 = new Thread(driver2::run);
//
//        t1.start();
//        t2.start();
//        t.join();
//        t1.join();
//        t2.join();
//        return rowCount.get();
//    }
}
