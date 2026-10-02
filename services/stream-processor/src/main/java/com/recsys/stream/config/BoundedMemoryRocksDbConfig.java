package com.recsys.stream.config;

import java.util.Map;
import org.apache.kafka.streams.state.RocksDBConfigSetter;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.Cache;
import org.rocksdb.LRUCache;
import org.rocksdb.Options;
import org.rocksdb.WriteBufferManager;

/**
 * Caps RocksDB's off-heap memory for all state stores of this instance with one shared block cache
 * and write-buffer manager (otherwise every store/segment allocates its own, and memory grows with
 * the number of stores and partitions). Sizes via env RECS_ROCKSDB_CACHE_MB /
 * RECS_ROCKSDB_MEMTABLE_MB.
 */
public class BoundedMemoryRocksDbConfig implements RocksDBConfigSetter {
  private static final long MB = 1024L * 1024L;
  private static final long CACHE = mb("RECS_ROCKSDB_CACHE_MB", 256) * MB;
  private static final long MEMTABLES = mb("RECS_ROCKSDB_MEMTABLE_MB", 64) * MB;
  private static final Cache SHARED_CACHE = new LRUCache(CACHE, -1, false, 0.1);
  private static final WriteBufferManager WRITE_BUFFERS =
      new WriteBufferManager(MEMTABLES, SHARED_CACHE);

  @Override
  public void setConfig(String storeName, Options options, Map<String, Object> configs) {
    BlockBasedTableConfig table = (BlockBasedTableConfig) options.tableFormatConfig();
    table.setBlockCache(SHARED_CACHE);
    table.setCacheIndexAndFilterBlocks(true);
    table.setCacheIndexAndFilterBlocksWithHighPriority(true);
    table.setPinTopLevelIndexAndFilter(true);
    options.setWriteBufferManager(WRITE_BUFFERS);
    options.setMaxWriteBufferNumber(2);
    options.setWriteBufferSize(8 * MB);
    options.setTableFormatConfig(table);
  }

  @Override
  public void close(String storeName, Options options) {
    // Shared cache and write-buffer manager live for the process; do not close them per store.
  }

  private static long mb(String env, long def) {
    String v = System.getenv(env);
    return v == null || v.isBlank() ? def : Long.parseLong(v);
  }
}
