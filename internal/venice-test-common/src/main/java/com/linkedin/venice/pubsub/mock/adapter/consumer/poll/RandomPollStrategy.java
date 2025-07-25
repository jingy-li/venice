package com.linkedin.venice.pubsub.mock.adapter.consumer.poll;

import com.linkedin.venice.pubsub.api.PubSubTopicPartition;
import com.linkedin.venice.pubsub.mock.InMemoryPubSubPosition;
import com.linkedin.venice.pubsub.mock.adapter.MockInMemoryPartitionPosition;
import com.linkedin.venice.utils.Utils;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


/**
 * A simple {@link PollStrategy} which delivers messages from any partition, while respecting the
 * ordering guarantee of individual partitions.
 *
 * The resulting consumption order is non-deterministic.
 */
public class RandomPollStrategy extends AbstractPollStrategy {
  public RandomPollStrategy() {
    super(true); // TODO: Change default to false once tests are ensured to be deterministic...
  }

  public RandomPollStrategy(int maxMessagePerPoll) {
    super(true, maxMessagePerPoll);
  }

  public RandomPollStrategy(boolean keepPollingWhenEmpty) {
    super(keepPollingWhenEmpty);
  }

  @Override
  protected MockInMemoryPartitionPosition getNextPoll(Map<PubSubTopicPartition, InMemoryPubSubPosition> offsets) {
    if (offsets.isEmpty()) {
      Utils.sleep(50); // So that keepPollingWhenEmpty doesn't lead to 10 null polls per ms
      return null;
    }
    List<PubSubTopicPartition> pubSubTopicPartitions =
        Arrays.asList(offsets.keySet().toArray(new PubSubTopicPartition[] {}));
    int numberOfPubSubTopicPartitions = offsets.size();
    PubSubTopicPartition pubSubTopicPartition =
        pubSubTopicPartitions.get((int) Math.round(Math.random() * (numberOfPubSubTopicPartitions - 1)));
    InMemoryPubSubPosition offset = offsets.get(pubSubTopicPartition);

    return new MockInMemoryPartitionPosition(pubSubTopicPartition, offset);
  }
}
