package com.twitter.elephantbird.pig.store;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.pig.ExecType;
import org.apache.pig.PigServer;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigSplit;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;
import org.apache.pig.impl.logicalLayer.FrontendException;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.apache.pig.impl.plan.OperatorKey;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.twitter.elephantbird.mapreduce.input.RawSequenceFileInputFormat;
import com.twitter.elephantbird.mapreduce.input.RawSequenceFileRecordReader;
import com.twitter.elephantbird.pig.load.SequenceFileLoader;
import com.twitter.elephantbird.pig.util.AbstractWritableConverter;
import com.twitter.elephantbird.pig.util.GenericWritableConverter;
import com.twitter.elephantbird.pig.util.IntWritableConverter;
import com.twitter.elephantbird.pig.util.LoadFuncTupleIterator;
import com.twitter.elephantbird.pig.util.TextConverter;

/**
 * Tests for {@link SequenceFileStorage}.
 *
 * @author Andy Schlaikjer
 * @see SequenceFileStorage
 * @see SequenceFileLoader
 * @see RawSequenceFileInputFormat
 * @see RawSequenceFileRecordReader
 * @see AbstractWritableConverter
 * @see IntWritableConverter
 * @see TextWritableConverter
 */
public class TestSequenceFileStorage {
  private static final String LINE_ONE = "one, two, buckle my shoe";
  private static final String LINE_TWO = "three, four, shut the door";
  private static final String LINE_THREE = "five, six, something else";
  private static final String[] DATA = { LINE_ONE, LINE_TWO, LINE_THREE };
  private static final String[][] EXPECTED = { { "0", LINE_ONE }, { "1", LINE_TWO },
          { "2", LINE_THREE } };

  private PigServer pigServer;
  private String tempFilename;

  @Before
  public void setUp() throws Exception {
    // create local Pig server
    pigServer = new PigServer(ExecType.LOCAL);

    // create temp SequenceFile
    File tempFile = File.createTempFile("test", ".txt");
    tempFilename = tempFile.getAbsolutePath();
    Path path = new Path("file:///" + tempFilename);
    Configuration conf = new Configuration();
    FileSystem fs = FileSystem.get(path.toUri(), conf);
    IntWritable key = new IntWritable();
    Text value = new Text();
    SequenceFile.Writer writer = null;
    try {
      writer = SequenceFile.createWriter(fs, conf, path, key.getClass(), value.getClass());
      for (int i = 0; i < DATA.length; ++i) {
        key.set(i);
        value.set(DATA[i]);
        writer.append(key, value);
      }
    } finally {
      IOUtils.closeStream(writer);
    }
  }

  @Test
  public void readOutsidePig() throws ClassCastException, ParseException, ClassNotFoundException,
      InstantiationException, IllegalAccessException, IOException, InterruptedException {
    // simulate Pig front-end runtime
    final SequenceFileStorage<IntWritable, Text> storage =
        new SequenceFileStorage<IntWritable, Text>("-c " + IntWritableConverter.class.getName(),
            "-c " + TextConverter.class.getName());
    Job job = new Job();
    storage.setUDFContextSignature("12345");
    storage.setLocation(tempFilename, job);

    // simulate Pig back-end runtime
    RecordReader<DataInputBuffer, DataInputBuffer> reader = new RawSequenceFileRecordReader();
    FileSplit fileSplit =
        new FileSplit(new Path(tempFilename), 0, new File(tempFilename).length(),
            new String[] { "localhost" });
    TaskAttemptContext context =
        new TaskAttemptContext(job.getConfiguration(), new TaskAttemptID());
    reader.initialize(fileSplit, context);
    InputSplit[] wrappedSplits = new InputSplit[] { fileSplit };
    int inputIndex = 0;
    List<OperatorKey> targetOps = Arrays.asList(new OperatorKey("54321", 0));
    int splitIndex = 0;
    PigSplit split = new PigSplit(wrappedSplits, inputIndex, targetOps, splitIndex);
    split.setConf(job.getConfiguration());
    storage.prepareToRead(reader, split);

    // read tuples and validate
    validate(new LoadFuncTupleIterator(storage));
  }

  private void registerLoadQuery() throws IOException {
    pigServer.registerQuery(String.format(
        "A = LOAD 'file:%s' USING %s('-c %s', '-c %s') AS (key: int, val: chararray);",
        tempFilename, SequenceFileStorage.class.getName(), IntWritableConverter.class.getName(),
        TextConverter.class.getName()));
  }

  @Test
  public void read() throws IOException {
    registerLoadQuery();
    validate(pigServer.openIterator("A"));
  }

  @Test
  public void readWithWritableConverterArguments() throws IOException {
    pigServer.registerQuery(String.format(
        "A = LOAD 'file:%s' USING %s('-c %s %s %s', '-c %s') AS (key: int, val: chararray);",
        tempFilename, SequenceFileStorage.class.getName(),
        NoDefaultConstructorIntWritableConverter.class.getName(), "123", "456",
        TextConverter.class.getName()));
    validate(pigServer.openIterator("A"));
  }

  @Test(expected = Exception.class)
  public void readWithMissingWritableConverterArguments() throws IOException {
    pigServer.registerQuery(String.format(
        "A = LOAD 'file:%s' USING %s('-c %s', '-c %s') AS (key: int, val: chararray);",
        tempFilename, SequenceFileStorage.class.getName(),
        NoDefaultConstructorIntWritableConverter.class.getName(), TextConverter.class.getName()));
    validate(pigServer.openIterator("A"));
  }

  @Test
  public void readWithoutSchemaTestSchema() throws IOException {
    pigServer.registerQuery(String.format("A = LOAD 'file:%s' USING %s('-c %s', '-c %s');",
        tempFilename, SequenceFileStorage.class.getName(), IntWritableConverter.class.getName(),
        TextConverter.class.getName()));
    Schema schema = pigServer.dumpSchema("A");
    Assert.assertNotNull(schema);
    Assert.assertEquals("key", schema.getField(0).alias);
    Assert.assertEquals(DataType.INTEGER, schema.getField(0).type);
    Assert.assertEquals("value", schema.getField(1).alias);
    Assert.assertEquals(DataType.CHARARRAY, schema.getField(1).type);
  }

  @Test(expected = FrontendException.class)
  public void readWithBadSchema() throws IOException {
    pigServer.registerQuery(String.format(
        "A = LOAD 'file:%s' USING %s('-c %s', '-c %s') AS (key: int, val: chararray, bad: int);",
        tempFilename, SequenceFileStorage.class.getName(), IntWritableConverter.class.getName(),
        TextConverter.class.getName()));
    validate(pigServer.openIterator("A"));
  }

  @Test
  public void readPushKeyProjection() throws IOException {
    registerLoadQuery();
    pigServer.registerQuery("B = FOREACH A GENERATE key;");
    validateIndex(pigServer.openIterator("B"), 0);
  }

  @Test
  public void readPushValueProjection() throws IOException {
    registerLoadQuery();
    pigServer.registerQuery("B = FOREACH A GENERATE val;");
    validateIndex(pigServer.openIterator("B"), 1);
  }

  @Test
  public void readWriteRead() throws IOException {
    registerLoadQuery();
    tempFilename = tempFilename + "-2";
    pigServer.registerQuery(String.format(
        "STORE A INTO 'file:%s' USING %s('-t %s -c %s', '-t %s -c %s');", tempFilename,
        SequenceFileStorage.class.getName(), IntWritable.class.getName(),
        IntWritableConverter.class.getName(), Text.class.getName(), TextConverter.class.getName()));
    registerLoadQuery();
    validate(pigServer.openIterator("A"));
  }

  @Test
  public void readByteArraysWriteByteArraysRead() throws IOException {
    pigServer.registerQuery(String.format(
        "A = LOAD 'file:%s' USING %s('-c %s', '-c %s') AS (key:bytearray, val:bytearray);",
        tempFilename, SequenceFileStorage.class.getName(),
        GenericWritableConverter.class.getName(), GenericWritableConverter.class.getName()));
    tempFilename = tempFilename + "-2";
    pigServer.registerQuery(String.format(
        "STORE A INTO 'file:%s' USING %s('-t %s -c %s', '-t %s -c %s');", tempFilename,
        SequenceFileStorage.class.getName(), IntWritable.class.getName(),
        GenericWritableConverter.class.getName(), Text.class.getName(),
        GenericWritableConverter.class.getName()));
    registerLoadQuery();
    validate(pigServer.openIterator("A"));
  }

  @Test(expected = IOException.class)
  public void writeUnsupportedConversion() throws IOException {
    registerLoadQuery();
    // swap ordering of key and val
    pigServer.registerQuery("A = FOREACH A GENERATE TOTUPLE(key), val;");
    // the following should die because IntWritableConverter doesn't support conversion of Tuple to
    // IntWritable
    pigServer.registerQuery(String.format(
        "STORE A INTO 'file:%s-2' USING %s('-t %s -c %s', '-t %s -c %s');", tempFilename,
        SequenceFileStorage.class.getName(), IntWritable.class.getName(),
        IntWritableConverter.class.getName(), Text.class.getName(), TextConverter.class.getName()));
  }

  @Test
  public void writeTextConversion() throws IOException {
    registerLoadQuery();
    tempFilename = tempFilename + "-2";
    // rely on TextConverter for conversion of int to Text
    pigServer.registerQuery(String.format(
        "STORE A INTO 'file:%s' USING %s('-t %s -c %s', '-t %s -c %s');", tempFilename,
        SequenceFileStorage.class.getName(), Text.class.getName(), TextConverter.class.getName(),
        Text.class.getName(), TextConverter.class.getName()));
    pigServer.registerQuery(String.format(
        "A = LOAD 'file:%s' USING %s('-c %s', '-c %s') AS (key:chararray, value:chararray);",
        tempFilename, SequenceFileStorage.class.getName(), TextConverter.class.getName(),
        TextConverter.class.getName()));
    validate(pigServer.openIterator("A"));
  }

  protected void validate(Iterator<Tuple> it) throws ExecException {
    int tupleCount = 0;
    while (it.hasNext()) {
      Tuple tuple = it.next();
      Assert.assertNotNull(tuple);
      Assert.assertEquals(2, tuple.size());
      for (int i = 0; i < 2; ++i) {
        Object entry = tuple.get(i);
        Assert.assertNotNull(entry);
        Assert.assertEquals(EXPECTED[tupleCount][i], entry.toString());
      }
      tupleCount++;
    }
    Assert.assertEquals(DATA.length, tupleCount);
  }

  protected void validateIndex(Iterator<Tuple> it, int index) throws ExecException {
    int tupleCount = 0;
    while (it.hasNext()) {
      Tuple tuple = it.next();
      Assert.assertNotNull(tuple);
      Assert.assertEquals(1, tuple.size());
      Object entry = tuple.get(0);
      Assert.assertNotNull(entry);
      Assert.assertEquals(EXPECTED[tupleCount][index], entry.toString());
      tupleCount++;
    }
    Assert.assertEquals(DATA.length, tupleCount);
  }
}