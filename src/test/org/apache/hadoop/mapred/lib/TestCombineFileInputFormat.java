/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.mapred.lib;

import java.io.IOException;
import java.io.DataOutputStream;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;

import junit.framework.TestCase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.MiniMRCluster;

public class TestCombineFileInputFormat extends TestCase{

  private static final String rack1[] = new String[] {
    "/r1"
  };
  private static final String hosts1[] = new String[] {
    "host1.rack1.com"
  };
  private static final String rack2[] = new String[] {
    "/r2"
  };
  private static final String hosts2[] = new String[] {
    "host2.rack2.com"
  };
  private static final String rack3[] = new String[] {
    "/r3"
  };
  private static final String hosts3[] = new String[] {
    "host3.rack3.com"
  };
  final Path inDir = new Path("/racktesting");
  final Path outputPath = new Path("/output");
  final Path dir1 = new Path(inDir, "/dir1");
  final Path dir2 = new Path(inDir, "/dir2");
  final Path dir3 = new Path(inDir, "/dir3");
  final Path dir4 = new Path(inDir, "/dir4");

  static final int BLOCKSIZE = 1024;
  static final byte[] databuf = new byte[BLOCKSIZE];

  private static final Log LOG = LogFactory.getLog(TestCombineFileInputFormat.class);
  
  /** Dummy class to extend CombineFileInputFormat*/
  private class DummyInputFormat extends CombineFileInputFormat<Text, Text> {
    @Override
    public RecordReader<Text,Text> getRecordReader(InputSplit split, JobConf job
        , Reporter reporter) throws IOException {
      return null;
    }
  }

  public void testSplitPlacement() throws IOException {
    String namenode = null;
    MiniDFSCluster dfs = null;
    MiniMRCluster mr = null;
    FileSystem fileSys = null;
    String testName = "TestSplitPlacement";
    try {
      /* Start 3 datanodes, one each in rack r1, r2, r3. Create three files
       * 1) file1, just after starting the datanode on r1, with 
       *    a repl factor of 1, and,
       * 2) file2, just after starting the datanode on r2, with 
       *    a repl factor of 2, and,
       * 3) file3 after starting the all three datanodes, with a repl 
       *    factor of 3.
       * At the end, file1 will be present on only datanode1, file2 will be
       * present on datanode 1 and datanode2 and 
       * file3 will be present on all datanodes. 
       */
      JobConf conf = new JobConf();
      conf.setBoolean("dfs.replication.considerLoad", false);
      dfs = new MiniDFSCluster(conf, 1, true, rack1, hosts1);
      dfs.waitActive();

      namenode = (dfs.getFileSystem()).getUri().getHost() + ":" +
                 (dfs.getFileSystem()).getUri().getPort();

      fileSys = dfs.getFileSystem();
      if (!fileSys.mkdirs(inDir)) {
        throw new IOException("Mkdirs failed to create " + inDir.toString());
      }
      writeFile(conf, new Path(dir1 + "/file1"), (short)1, 1);
      dfs.startDataNodes(conf, 1, true, null, rack2, hosts2, null);
      dfs.waitActive();

      // create file on two datanodes.
      writeFile(conf, new Path(dir2 + "/file2"), (short)2, 2);

      // split it using a CombinedFile input format
      DummyInputFormat inFormat = new DummyInputFormat();
      inFormat.setInputPaths(conf, dir1 + "," + dir2);
      inFormat.setMinSplitSizeRack(BLOCKSIZE);
      InputSplit[] splits = inFormat.getSplits(conf, 1);
      System.out.println("Made splits(Test1): " + splits.length);

      // make sure that each split has different locations
      for (int i = 0; i < splits.length; ++i) {
        CombineFileSplit fileSplit = (CombineFileSplit) splits[i];
        System.out.println("File split(Test1): " + fileSplit);
      }
      assertEquals(splits.length, 2);

      // create another file on 3 datanodes and 3 racks.
      dfs.startDataNodes(conf, 1, true, null, rack3, hosts3, null);
      dfs.waitActive();
      writeFile(conf, new Path(dir3 + "/file3"), (short)3, 3);
      inFormat = new DummyInputFormat();
      inFormat.setInputPaths(conf, dir1 + "," + dir2 + "," + dir3);
      inFormat.setMinSplitSizeRack(BLOCKSIZE);
      splits = inFormat.getSplits(conf, 1);
      for (int i = 0; i < splits.length; ++i) {
        CombineFileSplit fileSplit = (CombineFileSplit) splits[i];
        System.out.println("File split(Test2): " + fileSplit);
      }
      assertEquals(splits.length, 3);

      // create file4 on all three racks
      writeFile(conf, new Path(dir4 + "/file4"), (short)3, 3);
      inFormat = new DummyInputFormat();
      inFormat.setInputPaths(conf, dir1 + "," + dir2 + "," + dir3 + "," + dir4);
      inFormat.setMinSplitSizeRack(BLOCKSIZE);
      splits = inFormat.getSplits(conf, 1);
      for (int i = 0; i < splits.length; ++i) {
        CombineFileSplit fileSplit = (CombineFileSplit) splits[i];
        System.out.println("File split(Test3): " + fileSplit);
      }
      assertEquals(splits.length, 3);

      // maximum split size is 2 blocks 
      inFormat = new DummyInputFormat();
      inFormat.setMinSplitSizeNode(BLOCKSIZE);
      inFormat.setMaxSplitSize(2*BLOCKSIZE);
      inFormat.setInputPaths(conf, dir1 + "," + dir2 + "," + dir3 + "," + dir4);
      splits = inFormat.getSplits(conf, 1);
      for (int i = 0; i < splits.length; ++i) {
        CombineFileSplit fileSplit = (CombineFileSplit) splits[i];
        System.out.println("File split(Test4): " + fileSplit);
      }
      assertEquals(splits.length, 5);

      // maximum split size is 3 blocks 
      inFormat = new DummyInputFormat();
      inFormat.setMinSplitSizeNode(BLOCKSIZE);
      inFormat.setMaxSplitSize(3*BLOCKSIZE);
      inFormat.setInputPaths(conf, dir1 + "," + dir2 + "," + dir3 + "," + dir4);
      splits = inFormat.getSplits(conf, 1);
      for (int i = 0; i < splits.length; ++i) {
        CombineFileSplit fileSplit = (CombineFileSplit) splits[i];
        System.out.println("File split(Test5): " + fileSplit);
      }
      assertEquals(splits.length, 4);

      // maximum split size is 4 blocks 
      inFormat = new DummyInputFormat();
      inFormat.setMaxSplitSize(4*BLOCKSIZE);
      inFormat.setInputPaths(conf, dir1 + "," + dir2 + "," + dir3 + "," + dir4);
      splits = inFormat.getSplits(conf, 1);
      for (int i = 0; i < splits.length; ++i) {
        CombineFileSplit fileSplit = (CombineFileSplit) splits[i];
        System.out.println("File split(Test6): " + fileSplit);
      }
      assertEquals(splits.length, 3);

      // maximum split size is 7 blocks and min is 3 blocks
      inFormat = new DummyInputFormat();
      inFormat.setMaxSplitSize(7*BLOCKSIZE);
      inFormat.setMinSplitSizeNode(3*BLOCKSIZE);
      inFormat.setMinSplitSizeRack(3*BLOCKSIZE);
      inFormat.setInputPaths(conf, dir1 + "," + dir2 + "," + dir3 + "," + dir4);
      splits = inFormat.getSplits(conf, 1);
      for (int i = 0; i < splits.length; ++i) {
        CombineFileSplit fileSplit = (CombineFileSplit) splits[i];
        System.out.println("File split(Test7): " + fileSplit);
      }
      assertEquals(splits.length, 2);

      // Rack 1 has file1, file2 and file3 and file4
      // Rack 2 has file2 and file3 and file4
      // Rack 3 has file3 and file4
      Path file1 = new Path(dir1 + "/file1");
      file1 = new Path(conf.getWorkingDirectory(), file1);
      Path file2 = new Path(dir2 + "/file2");
      file2 = new Path(conf.getWorkingDirectory(), file2);
      Path file3 = new Path(dir3 + "/file3");
      file3 = new Path(conf.getWorkingDirectory(), file3);
      Path file4 = new Path(dir4 + "/file4");
      file4 = new Path(conf.getWorkingDirectory(), file3);

      // setup a filter so that only file1 and file2 can be combined
      inFormat = new DummyInputFormat();
      inFormat.addInputPath(conf, inDir);
      inFormat.setMinSplitSizeRack(1); // everything is at least rack local
      inFormat.createPool(conf, new TestFilter(dir1), 
                          new TestFilter(dir2));
      splits = inFormat.getSplits(conf, 1);
      for (int i = 0; i < splits.length; ++i) {
        CombineFileSplit fileSplit = (CombineFileSplit) splits[i];
        System.out.println("File split(TestPool1): " + fileSplit);
      }
      assertEquals(splits.length, 3);
    } finally {
      if (dfs != null) {
        dfs.shutdown();
      }
    }
  }

  static void writeFile(Configuration conf, Path name,
      short replication, int numBlocks) throws IOException {
    FileSystem fileSys = FileSystem.get(conf);

    FSDataOutputStream stm = fileSys.create(name, true,
                                            conf.getInt("io.file.buffer.size", 4096),
                                            replication, (long)BLOCKSIZE);
    for (int i = 0; i < numBlocks; i++) {
      stm.write(databuf);
    }
    stm.close();
    DFSTestUtil.waitReplication(fileSys, name, replication);
  }

  static class TestFilter implements PathFilter {
    private Path p;

    // store a path prefix in this TestFilter
    public TestFilter(Path p) {
      this.p = p;
    }

    // returns true if the specified path matches the prefix stored
    // in this TestFilter.
    public boolean accept(Path path) {
      if (path.toString().indexOf(p.toString()) == 0) {
        return true;
      }
      return false;
    }

    public String toString() {
      return "PathFilter:" + p;
    }
  }

  public static void main(String[] args) throws Exception{
    TestCombineFileInputFormat test = new TestCombineFileInputFormat();
    test.testSplitPlacement();
  }
}
