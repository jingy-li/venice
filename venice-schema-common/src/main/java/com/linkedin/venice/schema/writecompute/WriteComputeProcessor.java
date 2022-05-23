package com.linkedin.venice.schema.writecompute;

import com.linkedin.venice.schema.merge.ValueAndReplicationMetadata;
import com.linkedin.venice.schema.merge.MergeRecordHelper;
import io.tehuti.utils.Utils;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;


/**
 * This class is able to read write-computed value and apply it to original value.
 *
 * Notice: though it's possible, we only support GenericRecord updating at this point. That's
 * being said, both original and write compute object need to be GenericRecord.
 *
 * Currently, Venice supports 3 kinds of updates.
 * 1. Record partial update.
 * 2. Collection merging.
 * 3. Value deletion.
 *
 * This class has the knowledge of different versions of write compute so that it can dispatch method calls to their
 * corresponding write compute logic.
 *
 * Note that write-compute is only available for the root level field. Parsing nested fields are not supported at this point.
 *
 * TODO: since this class is very performance sensitive, we should add metrics to measure the
 * TODO: time it spends and keep optimizing the operations
 */
@ThreadSafe
public class WriteComputeProcessor {
  /**
   * The purpose of versioning the handler logic is:
   *
   *  1. To make it possible to have incompatible changes in the future.
   *  2. To make the code cleaner and more hierarchical.
   *
   * V2 is backward compatible with V1. So we do not need another instance for V1 here. Instantiating more version objects
   * may be necessary in the future.
   */
  private final WriteComputeHandlerV2 writeComputeHandlerV2;

  public WriteComputeProcessor(MergeRecordHelper mergeRecordHelper) {
    this.writeComputeHandlerV2 = new WriteComputeHandlerV2(mergeRecordHelper);
  }

  /**
   * Apply write-compute operations on the given record.
   *
   * @param originalSchema the original schema that write compute schema is derived from
   * @param writeComputeSchema the write compute schema that is auto-generated and paired with original Schema.
   *                           See {@link WriteComputeSchemaConverter} for more details that how it's generated.
   * @return write-compute updated record
   */
  public GenericRecord updateRecord(
      Schema originalSchema,
      Schema writeComputeSchema,
      GenericRecord originalRecord,
      GenericRecord writeComputeRecord
  ) {
    return writeComputeHandlerV2.updateRecord(
        Utils.notNull(originalSchema),
        Utils.notNull(writeComputeSchema),
        originalRecord,
        Utils.notNull(writeComputeRecord)
    );
  }

  public ValueAndReplicationMetadata<GenericRecord> updateRecordWithRmd(
      Schema currValueSchema,
      ValueAndReplicationMetadata<GenericRecord> oldRecordAndReplicationMetadata,
      GenericRecord writeComputeRecord,
      long updateOperationTimestamp,
      int updateOperationColoID
  ) {
    return writeComputeHandlerV2.updateRecord(
        Utils.notNull(currValueSchema),
        Utils.notNull(oldRecordAndReplicationMetadata),
        Utils.notNull(writeComputeRecord),
        updateOperationTimestamp,
        updateOperationColoID
    );
  }
}
