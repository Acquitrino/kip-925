package kip925;

import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.ProcessorSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;



public class ProcessorApiStdoutApp {

    private static final String INPUT_TOPIC = "input-topic";
    private static final String OUTPUT_TOPIC = "output-topic";
    private static final String BOOTSTRAP_SERVERS = "localhost:9092"; // Replace with your Kafka broker addresses
    private static final String APPLICATION_ID = "processor-api-stdout";

    private static ArrayList<String> plracks;
    private static String CLIENT_ID;
    private static String CLIENT_RACK;

    public static class PrintAndForwardProcessor implements Processor<String, String> {

        private ProcessorContext context;

        @Override
        public void init(ProcessorContext context) {
            this.context = context;
        }

        @Override
        public void process(String key, String value) {
            // Get metadata
            long offset = context.offset();
            int partition = context.partition();
            long timestamp = context.timestamp();
            String taskId = context.taskId().toString();

            // Print a bunch of info to standard out
            System.out.printf("Received message: Key=[%s], Value=[%s], Partition=[%d], Offset=[%d], Timestamp=[%d], TaskId=[%s]%n",
                    key, value, partition, offset, timestamp, taskId);

            //let's alter the value with partition, PL rack and client rack
            String pl_rack = plracks.get(partition);

            String strpartition = String.valueOf(partition);

            value = strpartition + "_" + pl_rack + "_" + CLIENT_RACK + "_____________:" +  pl_rack.equals(CLIENT_RACK);

            context.forward(key, value);
        }

        @Override
        public void close() {
            // Cleanup resources if needed
        }
    }


    public static class PrintAndForwardProcessorSupplier implements ProcessorSupplier<String, String> {
        @Override
        public Processor<String, String> get() {
            return new PrintAndForwardProcessor();
        }
    }

    public static void main(String[] args) {

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, APPLICATION_ID);

        if (args.length!=2){
            System.err.println("Pass as arguments the client_id and client_rack.");
        }
        CLIENT_ID = args[0];
        CLIENT_RACK = args[1];

        props.put(StreamsConfig.CLIENT_ID_CONFIG, CLIENT_ID);
        props.put(ConsumerConfig.CLIENT_RACK_CONFIG, CLIENT_RACK);

        //props.put(StreamsConfig.RACK_AWARE_ASSIGNMENT_TAGS_CONFIG, "client.tag.rack="+CLIENT_RACK);
        //props.put(StreamsConfig.CLIENT_TAG_PREFIX, "rack");

        //props.put(StreamsConfig.RACK_AWARE_ASSIGNMENT_TAGS_CONFIG, "rack");
        //props.put(StreamsConfig.CLIENT_TAG_PREFIX, "rack="+CLIENT_RACK);

        props.put("rack.aware.assignment.tags", "rack");
        props.put("client.tag.rack", CLIENT_RACK);

        //HA is the default...
        //props.put(StreamsConfig.TASK_ASSIGNOR_CLASS_CONFIG, "org.apache.kafka.streams.processor.internals.assignment.HighAvailabilityTaskAssignor");
        props.put(StreamsConfig.RACK_AWARE_ASSIGNMENT_STRATEGY_CONFIG, "balance_subtopology");
        //props.put(StreamsConfig.RACK_AWARE_ASSIGNMENT_STRATEGY_CONFIG, "min_traffic");
        props.put(StreamsConfig.RACK_AWARE_ASSIGNMENT_TRAFFIC_COST_CONFIG, "1000");
        props.put(StreamsConfig.RACK_AWARE_ASSIGNMENT_NON_OVERLAP_COST_CONFIG, "1");



        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());

        // initialize admin client
        Properties propsa = new Properties();
        propsa.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        propsa.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
        propsa.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 1000);
        AdminClient admin = AdminClient.create(props);

        DescribeTopicsResult describeTopicsResult;
        TopicDescription topicDescription = null;


        describeTopicsResult = admin.describeTopics(Collections.singleton(INPUT_TOPIC));
        try{
            topicDescription = describeTopicsResult.values().get(INPUT_TOPIC).get();
        } catch (Throwable e) {
            System.err.println("Error while fetching input topic description:" +e.getMessage());
        }

        plracks = new ArrayList<String>();
        for (TopicPartitionInfo partitionInfo: topicDescription.partitions()){
         plracks.add(partitionInfo.leader().rack());
        }

        System.out.println("Fetched the partition Leaders' rack");
        admin.close();



        Topology topology = new Topology();

        final String SOURCE_NODE = "InputSource";
        final String PROCESS_NODE = "PrintAndForward";
        final String SINK_NODE = "OutputSink";

        topology.addSource(SOURCE_NODE,
                Serdes.String().deserializer(), // Key Deserializer
                Serdes.String().deserializer(), // Value Deserializer
                INPUT_TOPIC);                  // Topic Name(s)

        topology.addProcessor(PROCESS_NODE,
                new PrintAndForwardProcessorSupplier(), // Our custom processor supplier
                SOURCE_NODE);                         // Parent node name

        topology.addSink(SINK_NODE,
                OUTPUT_TOPIC,                      // Output topic name
                Serdes.String().serializer(),      // Key Serializer
                Serdes.String().serializer(),      // Value Serializer
                PROCESS_NODE);                     // Parent node name

        System.out.println("Topology description:\n" + topology.describe());


        final KafkaStreams streams = new KafkaStreams(topology, props);
        final CountDownLatch latch = new CountDownLatch(1);


        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread("streams-shutdown-hook") {
            @Override
            public void run() {
                System.out.println("Shutting down Kafka Streams application...");
                streams.close();
                latch.countDown();
                System.out.println("Kafka Streams application shut down.");
            }
        });

        try {
            System.out.println("Starting Kafka Streams application...");
            streams.start();
            latch.await(); // Wait until shutdown hook is called
        } catch (Throwable e) {
            System.err.println("Error starting Kafka Streams application: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }
}
