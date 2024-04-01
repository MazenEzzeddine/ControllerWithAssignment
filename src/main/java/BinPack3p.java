
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

public class BinPack3p {

    //TODO give fup and fdown as paramters to the functions.
    private static final Logger log = LogManager.getLogger(BinPack3p.class);
    private int size = 1;
    public Instant LastUpScaleDecision = Instant.now();

    //0.5 WSLA is reached around 85 events/sec
    private final static double  wsla = 0.5;
    static boolean scaled;

    static List<Consumer> assignment = new ArrayList<Consumer>();
    static List<Consumer> currentAssignment = assignment;
    static List<Consumer> tempAssignment = assignment;



    private static KafkaConsumer<byte[], byte[]> metadataConsumer;

    static double mu = 200.0;




    static {
        currentAssignment.add(new Consumer("0", (long) (mu * wsla * .9),
                mu * .9));
        for (Partition p : ArrivalProducer.topicpartitions) {
            currentAssignment.get(0).assignPartition(p);
        }

       // k8s = new KubernetesClientBuilder().build();
    }





    public void scaleAsPerBinPack() {
        log.info("Currently we have this number of consumers group {} {}", "testgroup1", size);
        int neededsize = binPackAndScale();
        log.info("We currently need the following consumers for group1 (as per the bin pack) {}", neededsize);
        int replicasForscale = neededsize - size;
        if (replicasForscale > 0) {
            scaled = true;
            //TODO IF and Else IF can be in the same logic
            log.info("We have to upscale  group1 by {}", replicasForscale);
            size = neededsize;
            LastUpScaleDecision = Instant.now();
            currentAssignment = assignment;
            try (final KubernetesClient k8s = new KubernetesClientBuilder().build()) {
                k8s.apps().deployments().inNamespace("default").withName("latency").scale(neededsize);
                log.info("I have Upscaled group {} you should have {}", "testgroup11", neededsize);
            }
            return;
        } else {
            int neededsized = binPackAndScaled();
            int replicasForscaled = size - neededsized;
            if (replicasForscaled > 0) {
                log.info("We have to downscale  group by {} {}", "testgroup1", replicasForscaled);
                size = neededsized;
                LastUpScaleDecision = Instant.now();
                currentAssignment = assignment;
                try (final KubernetesClient k8s = new KubernetesClientBuilder().build()) {
                    k8s.apps().deployments().inNamespace("default").withName("latency").scale(neededsized);
                    log.info("I have downscaled group {} you should have {}", "testgroup11", neededsized);
                }
                return;
            }
        }
        if (assignmentViolatesTheSLA2()) {
            KafkaConsumerConfig config = KafkaConsumerConfig.fromEnv();
            Properties props = KafkaConsumerConfig.createProperties(config);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                    "org.apache.kafka.common.serialization.StringDeserializer");
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                    "org.apache.kafka.common.serialization.StringDeserializer");
            metadataConsumer = new KafkaConsumer<>(props);
            metadataConsumer.enforceRebalance();
            log.info("no");
        }
        log.info("===================================");
    }


    private int binPackAndScale() {
        log.info(" shall we upscale group {}", "testgroup1");
        List<Consumer> consumers = new ArrayList<>();
        int consumerCount = 1;
        List<Partition> parts = new ArrayList<>(ArrivalProducer.topicpartitions);
        float fraction = 0.9f;

        for (Partition partition : parts) {
            if (partition.getLag() > 200f * wsla * fraction) {
                log.info("Since partition {} has lag {} higher than consumer capacity times wsla {}" +
                        " we are truncating its lag", partition.getId(), partition.getLag(), 200f * wsla * fraction);
                partition.setLag((long) (200f * wsla * fraction));
            }
        }

        for (Partition partition : parts) {
            if (partition.getArrivalRate() > 200f ) {
                log.info("Since partition {} has arrival rate {} higher than consumer service rate {}" +
                                " we are truncating its arrival rate", partition.getId(),
                        String.format("%.2f", partition.getArrivalRate()),
                        String.format("%.2f", 200f * fraction ));
                partition.setArrivalRate(200f * fraction );
            }
        }
        //start the bin pack FFD with sort
        Collections.sort(parts, Collections.reverseOrder());
        while (true) {
            int j;
            consumers.clear();
            for (int t = 0; t < consumerCount; t++) {
                consumers.add(new Consumer((String.valueOf(t)), (long) (200f * wsla * fraction),
                        200f * fraction/*dynamicAverageMaxConsumptionRate*wsla*/));
            }
            for (j = 0; j < parts.size(); j++) {
                int i;
                Collections.sort(consumers, Collections.reverseOrder());
                for (i = 0; i < consumerCount; i++) {

                    if (consumers.get(i).getRemainingLagCapacity() >= parts.get(j).getLag() &&
                            consumers.get(i).getRemainingArrivalCapacity() >= parts.get(j).getArrivalRate()) {
                        consumers.get(i).assignPartition(parts.get(j));
                        break;
                    }
                }
                if (i == consumerCount) {
                    consumerCount++;
                    break;
                }
            }
            if (j == parts.size())
                break;
        }
        log.info(" The BP up scaler recommended for group {} {}", "testgroup1", consumers.size());

        assignment = consumers;


        tempAssignment = assignment;

        return consumers.size();
    }

    private int binPackAndScaled() {
        log.info(" shall we down scale group {} ", "testgroup1");
        List<Consumer> consumers = new ArrayList<>();
        int consumerCount = 1;
        List<Partition> parts = new ArrayList<>(ArrivalProducer.topicpartitions);
        double fractiondynamicAverageMaxConsumptionRate = 200f * 0.4;
        for (Partition partition : parts) {
            if (partition.getLag() > fractiondynamicAverageMaxConsumptionRate * wsla) {
                log.info("Since partition {} has lag {} higher than consumer capacity times wsla {}" +
                                " we are truncating its lag", partition.getId(), partition.getLag(),
                        fractiondynamicAverageMaxConsumptionRate * wsla);
                partition.setLag((long) (fractiondynamicAverageMaxConsumptionRate * wsla));
            }
        }
        for (Partition partition : parts) {
            if (partition.getArrivalRate() > fractiondynamicAverageMaxConsumptionRate) {
                log.info("Since partition {} has arrival rate {} higher than consumer service rate {}" +
                                " we are truncating its arrival rate", partition.getId(),
                        String.format("%.2f", partition.getArrivalRate()),
                        String.format("%.2f", fractiondynamicAverageMaxConsumptionRate));
                partition.setArrivalRate(fractiondynamicAverageMaxConsumptionRate);
            }
        }
        //start the bin pack FFD with sort
        Collections.sort(parts, Collections.reverseOrder());
        while (true) {
            int j;
            consumers.clear();
            for (int t = 0; t < consumerCount; t++) {
                consumers.add(new Consumer((String.valueOf(t)),
                        (long) (fractiondynamicAverageMaxConsumptionRate * wsla),
                        fractiondynamicAverageMaxConsumptionRate));
            }

            for (j = 0; j < parts.size(); j++) {
                int i;
                Collections.sort(consumers, Collections.reverseOrder());
                for (i = 0; i < consumerCount; i++) {

                    if (consumers.get(i).getRemainingLagCapacity() >= parts.get(j).getLag() &&
                            consumers.get(i).getRemainingArrivalCapacity() >= parts.get(j).getArrivalRate()) {
                        consumers.get(i).assignPartition(parts.get(j));
                        break;
                    }
                }
                if (i == consumerCount) {
                    consumerCount++;
                    break;
                }
            }
            if (j == parts.size())
                break;
        }
        log.info(" The BP down scaler recommended  for group {} {}", "testgroup1", consumers.size());
        assignment = consumers;

        return consumers.size();
    }

/*
    private boolean assignmentViolatesTheSLA() {
        for (Consumer cons : currentAssignment) {
            if (cons.getRemainingLagCapacity() <  (long) (wsla*200*.9f)||
                    cons.getRemainingArrivalCapacity() < 200f*0.9f){
                return false;
            }
        }
        return true;
    }
*/


    private static boolean assignmentViolatesTheSLA2() {

       List<Partition> partsReset = new ArrayList<>(ArrivalProducer.topicpartitions);
        for (Consumer cons : currentAssignment) {
            double sumPartitionsArrival = 0;
            double sumPartitionsLag = 0;
            for (Partition p : cons.getAssignedPartitions()) {
                sumPartitionsArrival += partsReset.get(p.getId()).getArrivalRate();
                sumPartitionsLag += partsReset.get(p.getId()).getLag();
            }

            if (sumPartitionsLag  > ( wsla * 200  * .9f)
                || sumPartitionsArrival > 200* 0.9f) {
                return true;
            }
        }
        return false;
    }

}
