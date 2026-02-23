package activityreport;

import activityreport.cli.ActivityReportCommand;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import picocli.CommandLine;

@QuarkusMain
public class ActivityReport implements QuarkusApplication {

    @Override
    public int run(String... args) {
        return new CommandLine(new ActivityReportCommand()).execute(args);
    }

    public static void main(String... args) {
        Quarkus.run(ActivityReport.class, args);
    }
}
