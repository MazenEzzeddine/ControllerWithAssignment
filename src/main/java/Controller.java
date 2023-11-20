import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;

public class Controller implements Runnable {

    private static final Logger log = LogManager.getLogger(Controller.class);
    static BinPack3p bp;

    private static void initialize() throws InterruptedException, ExecutionException {
        bp = new BinPack3p();

      Lag.readEnvAndCrateAdminClient();

        while (true) {
            log.info("Querying Prometheus");
           ArrivalProducer.callForArrivals();
          Lag.getCommittedLatestOffsetsAndLag();
          log.info("--------------------");
          log.info("--------------------");

          scaleLogic();
            log.info("Sleeping for 1 seconds");
            log.info("******************************************");
            log.info("******************************************");
            Thread.sleep(1000);
        }
    }

    private static void scaleLogic() throws InterruptedException {

        if  (Duration.between(bp.LastUpScaleDecision, Instant.now()).getSeconds() >3){
            bp.scaleAsPerBinPack();
        } else {
            log.info("No scale ");
        }
    }


    @Override
    public void run() {

        log.info("Warm up completed");

        try {
            initialize();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
    }
}
