/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.hadoop.hdds.utils.db;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.rocksdb.ColumnFamilyOptions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Tests RDBStore creation.
 */
public class TestDBStoreBuilder {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws Exception {
    System.setProperty(DBConfigFromFile.CONFIG_DIR,
        folder.newFolder().toString());
  }

  @Test
  public void builderWithoutAnyParams() throws IOException {
    OzoneConfiguration conf = new OzoneConfiguration();
    thrown.expect(IOException.class);
    DBStoreBuilder.newBuilder(conf).build();
  }

  @Test
  public void builderWithOneParamV1() throws IOException {
    OzoneConfiguration conf = new OzoneConfiguration();
    thrown.expect(IOException.class);
    DBStoreBuilder.newBuilder(conf)
        .setName("Test.db")
        .build();
  }

  @Test
  public void builderWithOneParamV2() throws IOException {
    OzoneConfiguration conf = new OzoneConfiguration();
    File newFolder = folder.newFolder();
    if (!newFolder.exists()) {
      Assert.assertTrue(newFolder.mkdirs());
    }
    thrown.expect(IOException.class);
    DBStoreBuilder.newBuilder(conf)
        .setPath(newFolder.toPath())
        .build();
  }

  @Test
  public void builderWithOpenClose() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    File newFolder = folder.newFolder();
    if (!newFolder.exists()) {
      Assert.assertTrue(newFolder.mkdirs());
    }
    DBStore dbStore = DBStoreBuilder.newBuilder(conf)
        .setName("Test.db")
        .setPath(newFolder.toPath())
        .build();
    // Nothing to do just open and Close.
    dbStore.close();
  }

  @Test
  public void builderWithDoubleTableName() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    File newFolder = folder.newFolder();
    if (!newFolder.exists()) {
      Assert.assertTrue(newFolder.mkdirs());
    }
    // Registering a new table with the same name should replace the previous
    // one.
    DBStore dbStore = DBStoreBuilder.newBuilder(conf)
        .setName("Test.db")
        .setPath(newFolder.toPath())
        .addTable("FIRST")
        .addTable("FIRST", new ColumnFamilyOptions())
        .build();
    // Building should succeed without error.

    try (Table<byte[], byte[]> firstTable = dbStore.getTable("FIRST")) {
      byte[] key =
          RandomStringUtils.random(9).getBytes(StandardCharsets.UTF_8);
      byte[] value =
          RandomStringUtils.random(9).getBytes(StandardCharsets.UTF_8);
      firstTable.put(key, value);
      byte[] temp = firstTable.get(key);
      Assert.assertArrayEquals(value, temp);
    }

    dbStore.close();
  }

  @Test
  public void builderWithDataWrites() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    File newFolder = folder.newFolder();
    if (!newFolder.exists()) {
      Assert.assertTrue(newFolder.mkdirs());
    }
    try (DBStore dbStore = DBStoreBuilder.newBuilder(conf)
        .setName("Test.db")
        .setPath(newFolder.toPath())
        .addTable("First")
        .addTable("Second")
        .build()) {
      try (Table<byte[], byte[]> firstTable = dbStore.getTable("First")) {
        byte[] key =
            RandomStringUtils.random(9).getBytes(StandardCharsets.UTF_8);
        byte[] value =
            RandomStringUtils.random(9).getBytes(StandardCharsets.UTF_8);
        firstTable.put(key, value);
        byte[] temp = firstTable.get(key);
        Assert.assertArrayEquals(value, temp);
      }

      try (Table secondTable = dbStore.getTable("Second")) {
        Assert.assertTrue(secondTable.isEmpty());
      }
    }
  }

  @Test
  public void builderWithDiskProfileWrites() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    File newFolder = folder.newFolder();
    if (!newFolder.exists()) {
      Assert.assertTrue(newFolder.mkdirs());
    }
    try (DBStore dbStore = DBStoreBuilder.newBuilder(conf)
        .setName("Test.db")
        .setPath(newFolder.toPath())
        .addTable("First")
        .addTable("Second")
        .setProfile(DBProfile.DISK)
        .build()) {
      try (Table<byte[], byte[]> firstTable = dbStore.getTable("First")) {
        byte[] key =
            RandomStringUtils.random(9).getBytes(StandardCharsets.UTF_8);
        byte[] value =
            RandomStringUtils.random(9).getBytes(StandardCharsets.UTF_8);
        firstTable.put(key, value);
        byte[] temp = firstTable.get(key);
        Assert.assertArrayEquals(value, temp);
      }

      try (Table secondTable = dbStore.getTable("Second")) {
        Assert.assertTrue(secondTable.isEmpty());
      }
    }
  }


}