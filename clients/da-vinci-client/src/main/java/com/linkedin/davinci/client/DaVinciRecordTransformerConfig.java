package com.linkedin.davinci.client;

import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.utils.lazy.Lazy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;


/**
 * Configuration class for {@link DaVinciRecordTransformer}, which is passed into {@link DaVinciConfig}.
 */
public class DaVinciRecordTransformerConfig {
  private final DaVinciRecordTransformerFunctionalInterface recordTransformerFunction;
  private final Class keyClass;
  private final Class outputValueClass;
  private final Schema outputValueSchema;
  private final boolean storeRecordsInDaVinci;
  private final boolean alwaysBootstrapFromVersionTopic;
  private final boolean skipCompatibilityChecks;
  private final boolean useSpecificRecordKeyDeserializer;
  private final boolean useSpecificRecordValueDeserializer;
  private AtomicInteger startConsumptionLatchCount;

  public DaVinciRecordTransformerConfig(Builder builder) {
    this.recordTransformerFunction = Optional.ofNullable(builder.recordTransformerFunction)
        .orElseThrow(() -> new VeniceException("recordTransformerFunction cannot be null"));

    this.keyClass = builder.keyClass;
    this.outputValueClass = builder.outputValueClass;
    this.outputValueSchema = builder.outputValueSchema;
    if ((this.outputValueClass != null && this.outputValueSchema == null)
        || (this.outputValueClass == null && this.outputValueSchema != null)) {
      throw new VeniceException("outputValueClass and outputValueSchema must be defined together");
    }

    this.useSpecificRecordKeyDeserializer = keyClass != null && SpecificRecord.class.isAssignableFrom(keyClass);
    this.useSpecificRecordValueDeserializer =
        outputValueClass != null && SpecificRecord.class.isAssignableFrom(outputValueClass);

    this.storeRecordsInDaVinci = builder.storeRecordsInDaVinci;
    this.alwaysBootstrapFromVersionTopic = builder.alwaysBootstrapFromVersionTopic;
    this.skipCompatibilityChecks = builder.skipCompatibilityChecks;

    // Default = 0 to guard against NPE downstream, which shouldn't be possible.
    this.startConsumptionLatchCount = new AtomicInteger(0);
  }

  /**
   * @return {@link #recordTransformerFunction}
   */
  public DaVinciRecordTransformerFunctionalInterface getRecordTransformerFunction() {
    return recordTransformerFunction;
  }

  /**
   * @return {@link #keyClass}
   */
  public Class getKeyClass() {
    return keyClass;
  }

  /**
   * @return Whether the {@link SpecificRecord} deserializer should be used for keys
   */
  public boolean useSpecificRecordKeyDeserializer() {
    return useSpecificRecordKeyDeserializer;
  }

  /**
   * @return {@link #outputValueClass}
   */
  public Class getOutputValueClass() {
    return outputValueClass;
  }

  /**
   * @return Whether the {@link SpecificRecord} deserializer should be used for values
   */
  public boolean useSpecificRecordValueDeserializer() {
    return useSpecificRecordValueDeserializer;
  }

  /**
   * @return {@link #outputValueSchema}
   */
  public Schema getOutputValueSchema() {
    return outputValueSchema;
  }

  /**
   * @return {@link #storeRecordsInDaVinci}
   */
  public boolean getStoreRecordsInDaVinci() {
    return storeRecordsInDaVinci;
  }

  /**
   * @return {@link #alwaysBootstrapFromVersionTopic}
   */
  public boolean getAlwaysBootstrapFromVersionTopic() {
    return alwaysBootstrapFromVersionTopic;
  }

  /**
   * @return {@link #skipCompatibilityChecks}
   */
  public boolean shouldSkipCompatibilityChecks() {
    return skipCompatibilityChecks;
  }

  /**
   * @return {@link #startConsumptionLatchCount}
   */
  synchronized public int getStartConsumptionLatchCount() {
    return startConsumptionLatchCount.get();
  }

  /**
   * @param startConsumptionLatchCount the count used for the latch to guarantee we finish scanning every RocksDB partition before starting remote consumption.
   */
  synchronized public void setStartConsumptionLatchCount(int startConsumptionLatchCount) {
    if (this.startConsumptionLatchCount.get() > 0) {
      throw new VeniceException("startConsumptionLatchCount should only be modified once");
    }
    this.startConsumptionLatchCount.set(startConsumptionLatchCount);
  }

  public static class Builder {
    private DaVinciRecordTransformerFunctionalInterface recordTransformerFunction;
    private Class keyClass;
    private Class outputValueClass;
    private Schema outputValueSchema;
    private Boolean storeRecordsInDaVinci = true;
    private Boolean alwaysBootstrapFromVersionTopic = false;
    private Boolean skipCompatibilityChecks = false;

    /**
     * @param recordTransformerFunction the functional interface for creating a {@link DaVinciRecordTransformer}
     */
    public Builder setRecordTransformerFunction(DaVinciRecordTransformerFunctionalInterface recordTransformerFunction) {
      this.recordTransformerFunction = recordTransformerFunction;
      return this;
    }

    /**
     * Set this if you want to deserialize keys into {@link org.apache.avro.specific.SpecificRecord}.
     * @param keyClass the class of the key
     */
    public Builder setKeyClass(Class keyClass) {
      this.keyClass = keyClass;
      return this;
    }

    /**
     * Set this if you modify the schema during transformation, or you want to deserialize values
     * into {@link org.apache.avro.specific.SpecificRecord}.
     * Must be used in conjunction with {@link #setOutputValueSchema(Schema)}
     * @param outputValueClass the class of the output value
     */
    public Builder setOutputValueClass(Class outputValueClass) {
      this.outputValueClass = outputValueClass;
      return this;
    }

    /**
     * Set this if you modify the schema during transformation, or you want to deserialize values
     * into {@link org.apache.avro.specific.SpecificRecord}.
     * Must be used in conjunction with {@link #setOutputValueClass(Class)}
     * @param outputValueSchema the schema of the output value
     */
    public Builder setOutputValueSchema(Schema outputValueSchema) {
      this.outputValueSchema = outputValueSchema;
      return this;
    }

    /**
     * @param storeRecordsInDaVinci set this to false if you intend to store records in a custom storage,
     *                              and not in the Da Vinci Client.
     *                              Default is true.
     */
    public Builder setStoreRecordsInDaVinci(boolean storeRecordsInDaVinci) {
      this.storeRecordsInDaVinci = storeRecordsInDaVinci;
      return this;
    }

    /**
     * @param alwaysBootstrapFromVersionTopic set this to true if {@link #storeRecordsInDaVinci} is false, and you're
     *                                        storing records in memory without being backed by disk.
     *                                        Default is false.
     */
    public Builder setAlwaysBootstrapFromVersionTopic(boolean alwaysBootstrapFromVersionTopic) {
      this.alwaysBootstrapFromVersionTopic = alwaysBootstrapFromVersionTopic;
      return this;
    }

    /**
     * @param skipCompatibilityChecks set this to true if {@link DaVinciRecordTransformer#transform(Lazy, Lazy, int)}
     *                                returns {@link DaVinciRecordTransformerResult.Result#UNCHANGED}.
     *                                Additionally, if you are making frequent changes to your
     *                                {@link DaVinciRecordTransformer} implementation without modifying the transform
     *                                logic, setting this to true will prevent your local data from being wiped
     *                                everytime a change is deployed. Remember to set this to false once your
     *                                changes have stabilized.
     *                                Default is false.
     */
    public Builder setSkipCompatibilityChecks(boolean skipCompatibilityChecks) {
      this.skipCompatibilityChecks = skipCompatibilityChecks;
      return this;
    }

    public DaVinciRecordTransformerConfig build() {
      return new DaVinciRecordTransformerConfig(this);
    }
  }
}
